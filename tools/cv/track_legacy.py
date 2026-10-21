"""LEGACY 4 Hz tracker (pre section 6.2), kept only as the baseline in cv_eval --scenarios --legacy."""
from __future__ import annotations

import time
from dataclasses import dataclass, field

import cv2
import numpy as np

from .heading import HeadingSmoother, estimate_heading
from .match import Matcher, MatchResult
from .preprocess import Calib, crop_minimap


@dataclass
class TrackCfg:
    vmax: float = 150.0         # max plausible speed, z0 px / s
    local_min_radius: float = 20.0
    local_max_radius: float = 90.0
    tau_local: float = 0.40     # min NCC to accept a local match
    tau_margin: float = 0.02    # min peak-vs-runner-up margin for local (relaxed: window is tiny)
    tau_global: float = 0.50
    tau_global_margin: float = 0.08
    max_miss: int = 3           # consecutive local misses before LOST
    alpha: float = 0.85         # alpha-beta gains (measurements are precise, motion is not)
    beta: float = 0.25
    global_on_miss: bool = True  # try a whole-map search right after the first local miss (teleports)
    global_confirm: int = 1     # consecutive consistent global hits needed (1 = accept immediately)
    ring_tol: float = 40.0      # mean abs colour distance for the "minimap visible" ring test


@dataclass
class Pose:
    state: str = "OFF"          # TRACKING / LOST / HIDDEN / OFF
    px0: float = 0.0
    py0: float = 0.0
    x_pos: float = 0.0
    y_pos: float = 0.0
    heading: float | None = None
    confidence: float = 0.0
    score: float = 0.0
    mode: str = ""              # local / global / coast / none
    ms: float = 0.0
    measured: tuple | None = None   # raw measurement (px0, py0) if any


class RingDetector:
    """Is the minimap on screen?  Compares the colour of the border ring with a calibration reference."""

    def __init__(self, calib: Calib, tol: float = 40.0):
        self.calib, self.tol, self.ref = calib, tol, None
        D = 2 * int(np.ceil(calib.r)) + 8
        yy, xx = np.mgrid[0:D, 0:D].astype(np.float32)
        rr = np.hypot(xx + 0.5 - D / 2, yy + 0.5 - D / 2)
        self.D = D
        self.band = (np.abs(rr - calib.r) <= 1.2)

    def _ring(self, frame):
        c = self.calib
        crop = cv2.getRectSubPix(frame, (self.D, self.D), (c.cx - 0.5, c.cy - 0.5))
        return crop[self.band].astype(np.float32)

    def fit(self, frame):
        self.ref = np.median(self._ring(frame), axis=0)

    def visible(self, frame) -> bool:
        if self.ref is None:
            return True
        ring = self._ring(frame)
        d = np.abs(ring - self.ref).mean(axis=1)
        return float((d < self.tol).mean()) > 0.6


class Tracker:
    def __init__(self, matcher: Matcher, calib: Calib | None = None, cfg: TrackCfg = TrackCfg(),
                 detector: RingDetector | None = None):
        self.m, self.calib, self.cfg = matcher, calib or matcher.calib, cfg
        self.det = detector
        self.reset()

    def reset(self):
        self.state = "OFF"
        self.pos = None
        self.vel = np.zeros(2)
        self.t_last = None
        self.miss = 0
        self.after_hidden = False
        self._pending = []          # unconfirmed global hits
        self.hs = HeadingSmoother(0.5)

    def set_prior(self, px0, py0):
        self.pos, self.state, self.miss = np.array([px0, py0], float), "TRACKING", 0

    # ------------------------------------------------------------------
    def update(self, frame, t: float | None = None) -> Pose:
        t0 = time.perf_counter()
        cfg = self.cfg
        t = t if t is not None else (self.t_last + 0.25 if self.t_last is not None else 0.0)
        dt = 0.25 if self.t_last is None else max(1e-3, t - self.t_last)
        self.t_last = t
        info = self.m.info
        if self.det is not None and not self.det.visible(frame):
            self.state = "HIDDEN" if self.pos is not None else "OFF"
            self.after_hidden = self.pos is not None
            return self._pose("none", 0.0, 0.0, None, t0)
        s = self.calib.s
        meas, score, mode, conf = None, 0.0, "none", 0.0
        res = None
        if self.pos is not None and self.state in ("TRACKING", "HIDDEN"):
            pred = self.pos + self.vel * dt
            rad = float(np.clip(cfg.vmax * dt + 8.0 * (1 + self.miss), cfg.local_min_radius, cfg.local_max_radius))
            if self.after_hidden:
                rad = cfg.local_max_radius * 1.5
            res = self.m.local_match(frame, tuple(pred), rad, s=s)
            score, mode = (res.score if res.ok else 0.0), "local"
            ok = res.ok and res.score >= cfg.tau_local and res.margin >= cfg.tau_margin
            if ok:
                meas, conf = np.array([res.px0, res.py0]), res.confidence
                self.miss, self.after_hidden = 0, False
                self.state = "TRACKING"
            else:
                self.miss += 1
                if self.miss > cfg.max_miss or self.after_hidden:
                    self.state = "LOST"
        if meas is None and (self.state in ("LOST", "OFF") or (cfg.global_on_miss and self.miss >= 1)):
            g = self.m.global_match(frame, s=s)
            score, mode = g.score, "global"
            if g.ok and g.score >= cfg.tau_global and g.margin >= cfg.tau_global_margin:
                p = np.array([g.px0, g.py0])
                self._pending = [q for q in self._pending if np.hypot(*(q - p)) < 40] + [p]
                if len(self._pending) >= cfg.global_confirm:
                    meas, conf = p, g.confidence
                    self.vel[:] = 0
                    self.pos = p.copy()
                    self.state, self.miss, self.after_hidden = "TRACKING", 0, False
                    self._pending = []
            else:
                self._pending = []
        # filter update
        if meas is not None and self.pos is not None:
            pred = self.pos + self.vel * dt
            if mode == "local":
                r = meas - pred
                self.pos = pred + cfg.alpha * r
                self.vel = self.vel + cfg.beta * r / dt
                sp = np.hypot(*self.vel)
                if sp > cfg.vmax:
                    self.vel *= cfg.vmax / sp
            else:
                self.pos = meas.copy()
        elif self.pos is not None and self.state == "TRACKING":
            self.pos = self.pos + self.vel * dt          # coast on the prediction
            mode = "coast"
        hd, _ = estimate_heading(crop_minimap(frame, self.calib))
        hd = self.hs.update(hd)
        return self._pose(mode, score, conf, hd, t0, tuple(meas) if meas is not None else None)

    def _pose(self, mode, score, conf, hd, t0, measured=None) -> Pose:
        p = self.pos if self.pos is not None else np.zeros(2)
        x, y = self.m.info.to_pos(p[0], p[1])
        return Pose(self.state, float(p[0]), float(p[1]), float(x), float(y), hd, float(conf), float(score),
                    mode, (time.perf_counter() - t0) * 1e3, measured)
