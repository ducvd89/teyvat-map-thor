"""Run the prototype Tracker (tools/cv/track.py, unmodified) on the real sequence with the real geometry/scale and
compare with the labels.  Variants: prototype ring test vs a proposed local-contrast ring test; per-region scale (oracle
region table) vs one fixed scale."""
from __future__ import annotations

import json
import time
from contextlib import contextmanager
from pathlib import Path

import cv2
import numpy as np

from .. import screen
from ..mapdata import load_info
from ..match import Matcher
from ..preprocess import Calib, Params
from ..targets import load_targets
from ..track import TrackCfg, Tracker
from . import geom
from .data import RAW, Frames, secs, ts, frame_names

LABELS = Path(__file__).resolve().parents[3] / "tools" / "dataset" / "labels.json"


# ------------------------------------------------------------------------------------------ proposed ring test
_TH = np.linspace(0, 2 * np.pi, 360, endpoint=False)
_CT, _ST = np.cos(_TH), np.sin(_TH)


def ring_contrast(frame, cx=geom.CX_IDX, cy=geom.CY_IDX, R=geom.R_RING, off=4.5):
    """Median over 360 angles of  max(I(R-.5), I(R+.5)) - mean(I(R-off), I(R+off))  (gray).  Present: >= ~6 (real: 11-21
    with the HUD, ~0 on loading / big map)."""
    g = frame if frame.ndim == 2 else cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    g = g.astype(np.float32)

    def samp(r):
        X = (cx + r * _CT).astype(np.float32).reshape(1, -1)
        Y = (cy + r * _ST).astype(np.float32).reshape(1, -1)
        return cv2.remap(g, X, Y, cv2.INTER_LINEAR)[0]

    c = np.maximum(samp(R - 0.5), samp(R + 0.5)) - 0.5 * (samp(R - off) + samp(R + off))
    return float(np.median(c))


RING_THR = 6.0


def ring_present_proposed(frame, calib=None, cfg=None):
    return ring_contrast(frame) >= RING_THR


@contextmanager
def patched_ring(proposed: bool):
    old = screen.ring_present
    if proposed:
        screen.ring_present = ring_present_proposed
    try:
        yield
    finally:
        screen.ring_present = old


# ------------------------------------------------------------------------------------------ driver
def region_scale(i, s_town=2.222, s_snow=0.752):
    return s_town if i <= 48 else s_snow       # oracle region table: town until the loading screen, tundra after


def run(variant: dict, start=0, stop=450, names=None, prior=None, verbose=False, cfg=None):
    """variant: dict(ring='proto'|'new', scale='region'|float, tracker_cfg=TrackCfg)"""
    names = names or frame_names()
    info = load_info(2)
    calib = Calib(cx=geom.CX, cy=geom.CY, r=geom.R_RING, s=2.222)
    # ring reference for the prototype ring test: one HUD frame at calibration time (snow frame 60 = what a user would calibrate on)
    ref_frame = cv2.imread(str(RAW / names[60]))
    screen.fit_ring_ref(ref_frame, calib)
    m = Matcher(info, Params(), calib, coarse_z=-2, threads=8)
    targets = load_targets(info)
    trk = Tracker(m, calib, cfg or TrackCfg(), layers=None, refs=None, targets=targets)
    labels = json.loads(LABELS.read_text())
    t0 = secs(names[start])
    rows = []
    with patched_ring(variant.get("ring") == "new"):
        trk.start(prior=prior)
        for i in range(start, stop):
            fr = cv2.imread(str(RAW / names[i]))
            sc = variant["scale"]
            calib.s = region_scale(i) if sc == "region" else float(sc)
            t = float(secs(names[i]) - t0)
            tt = time.perf_counter()
            pose = trk.update(fr, t)
            ms = (time.perf_counter() - tt) * 1e3
            lab = labels.get(names[i])
            err = None
            if lab is not None:
                err = float(np.hypot(pose.x_pos - lab["x_pos"], pose.y_pos - lab["y_pos"]))
            rows.append(dict(i=i, t=t, state=pose.state, mode=pose.mode, acc=bool(pose.accepted), score=float(pose.score),
                             x=pose.x_pos, y=pose.y_pos, err=err, ms=ms, path=pose.path, s=float(calib.s),
                             heading=pose.heading, ring=(ring_contrast(fr[:340, :440]))))
            if verbose:
                print(i, ts(names[i]), pose.state, pose.mode, f"{pose.score:.2f}", None if err is None else f"{err:.1f}", f"{ms:.0f}ms", flush=True)
    return rows, trk
