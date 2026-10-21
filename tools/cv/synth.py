"""Synthetic minimap frames generated from the reference tiles.

Optimistic by construction (same imagery as the reference), so we add the
degradations a real minimap would have: icons, centre arrow + view cone, rim
fade, semi-transparency over a game background, blur, colour/contrast jitter,
low-frequency illumination, noise and JPEG artefacts.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from .mapdata import MapInfo, level_geom, load_mosaic
from .preprocess import Calib

FRAME_W, FRAME_H = 1920, 1080


@dataclass
class SynthCfg:
    cx: float = 138.0
    cy: float = 132.0
    r: float = 108.0
    s: float = 0.8
    blur: float = 0.8           # extra gaussian blur sigma (native px)
    noise: float = 3.0          # gaussian noise std (gray levels)
    jpeg: int = 85              # JPEG quality (0 = off)
    gain: float = 1.0
    bias: float = 0.0
    gamma: float = 1.0
    tint: tuple = (1.0, 1.0, 1.0)
    illum: float = 0.08         # amplitude of low-frequency illumination field
    alpha: float = 0.95         # map opacity over the background
    n_icons: int = 6
    arrow: bool = True
    cone: bool = True
    heading: float = 0.0        # deg, 0 = up (north), clockwise
    quest_ring: bool = False
    rim_fade: float = 0.88      # alpha starts fading at this fraction of R


_SRC = {}


def _src(info: MapInfo):
    """(color z0 mosaic or z-1 fallback, level z, level geom)."""
    key = info.version
    if key not in _SRC:
        try:
            m = load_mosaic(info, 0, color=True, build=False)
            z = 0
        except FileNotFoundError:
            m = load_mosaic(info, -1, color=True)
            z = -1
        _SRC[key] = (m, z, level_geom(info, z))
    return _SRC[key]


def render_crop(info: MapInfo, px0: float, py0: float, s: float, D: int, blur: float = 0.0) -> np.ndarray:
    """D x D BGR native-minimap crop centred at continuous z0 position (px0, py0) with scale s."""
    m, z, lv = _src(info)
    f = 2.0 ** z
    # source patch in level px
    half = D / 2.0 / s * f
    u0, v0 = px0 * f - lv.ox, py0 * f - lv.oy        # level coords of centre
    x0, y0 = int(np.floor(u0 - half)) - 4, int(np.floor(v0 - half)) - 4
    n = int(np.ceil(2 * half)) + 10
    x0c, y0c = max(0, x0), max(0, y0)
    x1c, y1c = min(m.shape[1], x0 + n), min(m.shape[0], y0 + n)
    patch = np.full((n, n, 3), (31, 23, 16), np.uint8)
    if x1c > x0c and y1c > y0c:
        patch[y0c - y0:y1c - y0, x0c - x0:x1c - x0] = m[y0c:y1c, x0c:x1c]
    k = s / f                                         # output px per source px (<1 => downsample)
    if k < 1:
        patch = cv2.GaussianBlur(patch, (0, 0), 0.45 / k)
    # dst idx -> src idx: src = (dst + 0.5 - D/2)/k + (u0 - x0) - 0.5
    cxs, cys = u0 - x0, v0 - y0
    # simplify: dst_idx = k*(src_idx + 0.5 - cs) + D/2 - 0.5
    Mx = np.array([[k, 0, k * (0.5 - cxs) + D / 2 - 0.5],
                   [0, k, k * (0.5 - cys) + D / 2 - 0.5]], np.float32)
    out = cv2.warpAffine(patch, Mx, (D, D), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
    if blur > 0:
        out = cv2.GaussianBlur(out, (0, 0), blur)
    return out


# ------------------------------------------------------------------------------ drawing helpers
def _bg(rng, h=FRAME_H, w=FRAME_W):
    """Fake game scene: smooth colour field + random blobs."""
    small = rng.uniform(30, 200, (6, 8, 3)).astype(np.float32)
    bg = cv2.resize(small, (w, h), interpolation=cv2.INTER_CUBIC)
    for _ in range(20):
        c = tuple(int(v) for v in rng.uniform(0, 255, 3))
        cv2.circle(bg, (int(rng.integers(0, w)), int(rng.integers(0, h))), int(rng.integers(10, 120)), c, -1)
    bg = cv2.GaussianBlur(bg, (0, 0), 12)
    return np.clip(bg, 0, 255).astype(np.uint8)


def _draw_icons(img, rng, cfg: SynthCfg, R):
    D = img.shape[0]
    for _ in range(cfg.n_icons):
        ang, rad = rng.uniform(0, 2 * np.pi), np.sqrt(rng.uniform(0.02, 0.85)) * R
        x, y = int(R + rad * np.cos(ang)), int(R + rad * np.sin(ang))
        sz = int(rng.integers(8, 14))
        kind = rng.integers(0, 5)
        if kind == 0:    # blue/white waypoint
            cv2.circle(img, (x, y), sz, (255, 255, 255), -1, cv2.LINE_AA)
            cv2.circle(img, (x, y), sz - 3, (255, 190, 60), -1, cv2.LINE_AA)
        elif kind == 1:  # gold quest marker
            cv2.circle(img, (x, y), sz, (30, 200, 255), -1, cv2.LINE_AA)
            cv2.circle(img, (x, y), sz, (255, 255, 255), 2, cv2.LINE_AA)
            cv2.rectangle(img, (x - 1, y - sz // 2), (x + 1, y + 2), (255, 255, 255), -1)
        elif kind == 2:  # red mob
            cv2.circle(img, (x, y), sz // 2 + 2, (40, 40, 230), -1, cv2.LINE_AA)
        elif kind == 3:  # white statue/diamond
            pts = np.array([[x, y - sz], [x + sz, y], [x, y + sz], [x - sz, y]], np.int32)
            cv2.fillPoly(img, [pts], (250, 250, 250), cv2.LINE_AA)
        else:            # chest-ish saturated square
            cv2.rectangle(img, (x - sz // 2, y - sz // 3), (x + sz // 2, y + sz // 3), (200, 120, 20), -1)
            cv2.rectangle(img, (x - sz // 2, y - sz // 3), (x + sz // 2, y + sz // 3), (255, 255, 255), 1)
    if cfg.quest_ring:
        ang = rng.uniform(0, 2 * np.pi)
        c = (int(R + 0.4 * R * np.cos(ang)), int(R + 0.4 * R * np.sin(ang)))
        ov = img.copy()
        cv2.circle(ov, c, int(rng.integers(30, 55)), (60, 220, 255), 2, cv2.LINE_AA)
        img[:] = cv2.addWeighted(ov, 0.6, img, 0.4, 0)


def _draw_arrow(img, cfg: SynthCfg, R):
    c = np.array([R, R], np.float32)
    th = np.deg2rad(cfg.heading)
    d = np.array([np.sin(th), -np.cos(th)], np.float32)
    n = np.array([-d[1], d[0]], np.float32)
    if cfg.cone:
        ov = img.copy()
        r1 = 0.5 * R
        a0, a1 = np.degrees(np.arctan2(d[1], d[0])) - 35, np.degrees(np.arctan2(d[1], d[0])) + 35
        cv2.ellipse(ov, (int(R), int(R)), (int(r1), int(r1)), 0, a0, a1, (255, 230, 150), -1, cv2.LINE_AA)
        img[:] = cv2.addWeighted(ov, 0.35, img, 0.65, 0)
    if cfg.arrow:
        L, W = 0.11 * R, 0.06 * R
        tip, l, r_, tail = c + d * L, c - d * L * 0.7 + n * W, c - d * L * 0.7 - n * W, c - d * L * 0.3
        pts = np.array([tip, l, tail, r_], np.float32)
        cv2.fillPoly(img, [np.round(pts * 16).astype(np.int32)], (60, 215, 255), cv2.LINE_AA, shift=4)
        cv2.polylines(img, [np.round(pts * 16).astype(np.int32)], True, (255, 255, 255), 1, cv2.LINE_AA, shift=4)


def draw_ring(frame, cx, cy, R):
    """Synthetic minimap border: dark inner/outer outline, light body (R..R+4) with 8 dark notches."""
    c = (int(round(cx)), int(round(cy)))
    Ri = int(round(R))
    cv2.circle(frame, c, Ri + 5, (28, 30, 34), 1, cv2.LINE_AA)
    cv2.circle(frame, c, Ri + 2, (205, 218, 228), 4, cv2.LINE_AA)
    cv2.circle(frame, c, Ri - 1, (60, 70, 80), 1, cv2.LINE_AA)
    for k in range(8):
        a = k * np.pi / 4 + 0.2
        p0 = (int(round(c[0] + (Ri + 0.5) * np.cos(a))), int(round(c[1] + (Ri + 0.5) * np.sin(a))))
        p1 = (int(round(c[0] + (Ri + 4.5) * np.cos(a))), int(round(c[1] + (Ri + 4.5) * np.sin(a))))
        cv2.line(frame, p0, p1, (40, 45, 55), 2, cv2.LINE_AA)


def make_frame(info: MapInfo, px0: float, py0: float, cfg: SynthCfg, rng: np.random.Generator,
               with_map: bool = True) -> np.ndarray:
    """Full 1920x1080 BGR frame with a synthetic minimap whose centre is at z0 (px0, py0)."""
    frame = _bg(rng)
    if not with_map:
        return frame
    R = cfg.r
    Ri = int(np.ceil(R))
    D = 2 * Ri
    crop = render_crop(info, px0, py0, cfg.s, D, cfg.blur).astype(np.float32)
    # photometric jitter (map side)
    crop = crop * np.array(cfg.tint, np.float32)
    crop = 255.0 * np.power(np.clip(crop / 255.0, 0, 1), cfg.gamma)
    crop = crop * cfg.gain + cfg.bias
    if cfg.illum > 0:
        fld = cv2.resize(rng.uniform(1 - cfg.illum, 1 + cfg.illum, (3, 3)).astype(np.float32), (D, D),
                         interpolation=cv2.INTER_CUBIC)
        crop *= fld[..., None]
    crop = np.clip(crop, 0, 255).astype(np.uint8)
    _draw_icons(crop, rng, cfg, Ri)
    cfg_h = cfg
    _draw_arrow(crop, cfg_h, Ri)
    # alpha: rim fade + global opacity
    yy, xx = np.mgrid[0:D, 0:D].astype(np.float32)
    rr = np.hypot(xx + 0.5 - Ri, yy + 0.5 - Ri) / R
    a = np.clip((1.0 - rr) / (1.0 - cfg.rim_fade), 0, 1) * cfg.alpha
    a[rr > 1.0] = 0
    x0, y0 = int(round(cfg.cx)) - Ri, int(round(cfg.cy)) - Ri
    roi = frame[y0:y0 + D, x0:x0 + D].astype(np.float32)
    comp = roi * (1 - a[..., None]) + crop.astype(np.float32) * a[..., None]
    frame[y0:y0 + D, x0:x0 + D] = comp.astype(np.uint8)
    # ring
    draw_ring(frame, cfg.cx, cfg.cy, R)
    if cfg.noise > 0:
        frame = np.clip(frame.astype(np.float32) + rng.normal(0, cfg.noise, frame.shape), 0, 255).astype(np.uint8)
    if cfg.jpeg:
        ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, int(cfg.jpeg)])
        frame = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    return frame


# ------------------------------------------------------------------------------ sampling
def random_cfg(rng: np.random.Generator, s: float | None = None, level: str = "mixed",
               cx: float = 138.0, cy: float = 132.0, r: float = 108.0) -> SynthCfg:
    """level: 'clean' | 'mixed' | 'hard' controls the degradation strength."""
    k = {"clean": 0.3, "mixed": 1.0, "hard": 1.8}[level]
    night = rng.random() < 0.2 * k
    c = SynthCfg(cx=cx, cy=cy, r=r)
    c.s = float(s if s is not None else np.exp(rng.uniform(np.log(0.4), np.log(1.3))))
    c.blur = float(rng.uniform(0.3, 0.8 + 0.6 * k))
    c.noise = float(rng.uniform(0, 2 + 3 * k))
    c.jpeg = int(rng.integers(max(40, int(85 - 25 * k)), 96)) if level != "clean" else 0
    c.gain = float(rng.uniform(1 - 0.15 * k, 1 + 0.15 * k)) * (0.55 if night else 1.0)
    c.bias = float(rng.uniform(-12 * k, 12 * k))
    c.gamma = float(np.exp(rng.uniform(-0.2 * k, 0.2 * k)))
    c.tint = tuple(float(v) for v in rng.uniform(1 - 0.08 * k, 1 + 0.08 * k, 3) * ((0.9, 1.0, 1.15) if night else (1, 1, 1)))
    c.illum = float(rng.uniform(0, 0.1 * k))
    c.alpha = float(rng.uniform(1.0 - 0.15 * k, 1.0))
    c.n_icons = int(rng.integers(0, 4 + int(6 * k)))
    c.heading = float(rng.uniform(0, 360))
    c.quest_ring = bool(rng.random() < 0.15 * k)
    return c


def sample_position(info: MapInfo, rng: np.random.Generator, s: float, r: float = 108.0,
                    min_tex: float = 9.0, min_edge: float = 0.30) -> tuple[float, float]:
    """Random z0 position on land with plenty of texture (not sea / blank)."""
    g = load_mosaic(info, -2, color=False)
    lv = level_geom(info, -2)
    half = int(r / s * 0.25) + 2
    for _ in range(500):
        u = int(rng.integers(half, g.shape[1] - half))
        v = int(rng.integers(half, g.shape[0] - half))
        p = np.asarray(g[v - half:v + half, u - half:u + half], np.float32)
        if p.std() < min_tex:
            continue
        gx = cv2.Sobel(p, cv2.CV_32F, 1, 0)
        gy = cv2.Sobel(p, cv2.CV_32F, 0, 1)
        if (np.hypot(gx, gy) > 8).mean() < min_edge:
            continue
        # jitter at sub-pixel level, convert to z0 coordinates
        px0, py0 = lv.to_z0(u + rng.uniform(0, 1), v + rng.uniform(0, 1))
        return float(px0), float(py0)
    raise RuntimeError("no land position found")


def make_sample(info: MapInfo, rng: np.random.Generator, s: float | None = None, level: str = "mixed",
                min_edge: float = 0.30, **cfg_kw):
    """min_edge: fraction of strongly textured pixels required around the position (0.12 admits open sea)."""
    cfg = random_cfg(rng, s=s, level=level, **cfg_kw)
    px0, py0 = sample_position(info, rng, cfg.s, cfg.r, min_edge=min_edge)
    frame = make_frame(info, px0, py0, cfg, rng)
    return frame, dict(px0=px0, py0=py0, x_pos=px0 - info.origin[0], y_pos=py0 - info.origin[1],
                       s=cfg.s, heading=cfg.heading, cfg=cfg)


def make_trajectory(info: MapInfo, rng: np.random.Generator, n: int = 60, s: float = 0.8,
                    level: str = "mixed", speed: tuple = (1.0, 8.0), hidden_at: tuple = (),
                    teleport_at: tuple = (), **cfg_kw):
    """Yield (frame, truth) along a smooth path; frames in hidden_at have no minimap; teleport jumps."""
    cfg0 = random_cfg(rng, s=s, level=level, **cfg_kw)
    px, py = sample_position(info, rng, s, cfg0.r)
    th = rng.uniform(0, 2 * np.pi)
    v = rng.uniform(*speed)
    out = []
    for i in range(n):
        if i in teleport_at:
            px, py = sample_position(info, rng, s, cfg0.r)
        th += rng.normal(0, 0.15)
        v = float(np.clip(v + rng.normal(0, 0.5), speed[0], speed[1]))
        px, py = px + v * np.cos(th), py + v * np.sin(th)
        cfg = SynthCfg(**{**cfg0.__dict__})
        cfg.heading = float(np.rad2deg(th) + 90) % 360
        cfg.n_icons = max(0, cfg0.n_icons + int(rng.integers(-1, 2)))
        hidden = i in hidden_at
        frame = make_frame(info, px, py, cfg, rng, with_map=not hidden)
        out.append((frame, dict(px0=px, py0=py, x_pos=px - info.origin[0], y_pos=py - info.origin[1],
                                s=s, heading=cfg.heading, hidden=hidden, teleport=i in teleport_at)))
    return out


# ============================================================================== scenarios (PLAN.md 6.2)
# A Scenario is a continuous-time timeline; frames are rendered on demand at whatever instants the tracker
# asks for (simulated time).  Ground truth carries the expected tracker state.
from dataclasses import dataclass as _dc


class Terrain:
    """Texture queries on the z-2 mosaic (land / sea tests used to build paths)."""

    def __init__(self, info: MapInfo):
        self.g = load_mosaic(info, -2, color=False)
        self.lv = level_geom(info, -2)

    def stats(self, px0, py0, half_z0):
        u, v = self.lv.from_z0(px0, py0)
        h = int(half_z0 * 0.25) + 2
        u, v = int(u), int(v)
        if u - h < 0 or v - h < 0 or u + h >= self.g.shape[1] or v + h >= self.g.shape[0]:
            return 0.0, 0.0
        p = np.asarray(self.g[v - h:v + h, u - h:u + h], np.float32)
        gx, gy = cv2.Sobel(p, cv2.CV_32F, 1, 0), cv2.Sobel(p, cv2.CV_32F, 0, 1)
        return float(p.std()), float((np.hypot(gx, gy) > 8).mean())

    def land_ok(self, px0, py0, half_z0, min_edge=0.25):
        sd, ed = self.stats(px0, py0, half_z0)
        return sd >= 9.0 and ed >= min_edge

    def flat(self, px0, py0, half_z0):
        sd, ed = self.stats(px0, py0, half_z0)
        return sd < 3.0 and ed < 0.02


def _walk_path(terr: Terrain, rng, start, dur, v0, half, dt=0.1, turn=0.25):
    n = int(round(dur / dt)) + 1
    x, y = start
    th = rng.uniform(0, 2 * np.pi)
    v = v0
    P, H = np.zeros((n, 2)), np.zeros(n)
    for i in range(n):
        P[i] = (x, y)
        H[i] = th
        th += rng.normal(0, turn * np.sqrt(dt))
        v = float(np.clip(v + rng.normal(0, 0.15 * v0 * np.sqrt(dt)) + 0.2 * (v0 - v) * dt, 0.5 * v0, 1.5 * v0))
        nx, ny = x, y
        for _ in range(14):
            nx, ny = x + v * dt * np.cos(th), y + v * dt * np.sin(th)
            if terr.land_ok(nx, ny, half):
                break
            th += rng.uniform(0.7, 2.4)
            nx, ny = x, y
        x, y = nx, ny
    return P, np.degrees(H) + 90.0


def _coast_path(terr: Terrain, rng, half, length=550.0, speed=25.0, tries=600):
    """Round trip land -> open sea -> land along a straight line.  Returns (start, direction angle)."""
    px_all = terr.lv
    H, W = terr.g.shape
    for _ in range(tries):
        u, v = int(rng.integers(40, W - 40)), int(rng.integers(40, H - 40))
        x0, y0 = px_all.to_z0(u, v)
        if not terr.land_ok(x0, y0, half):
            continue
        for a in rng.permutation(16):
            ang = a * np.pi / 8
            pts = [(x0 + k * 25.0 * np.cos(ang), y0 + k * 25.0 * np.sin(ang)) for k in range(int(length / 25) + 1)]
            fl = np.array([terr.flat(px, py, half) for px, py in pts])
            n = len(fl)
            if fl[: n // 5].any():
                continue
            if fl[n // 2:].mean() >= 0.85 and fl[n * 2 // 3:].all():
                return (x0, y0), float(ang)
    raise RuntimeError("no coast path found")


def _icon_draw(img, x, y, kind, sz):
    if kind == 0:
        cv2.circle(img, (x, y), sz, (255, 255, 255), -1, cv2.LINE_AA)
        cv2.circle(img, (x, y), sz - 3, (255, 190, 60), -1, cv2.LINE_AA)
    elif kind == 1:
        cv2.circle(img, (x, y), sz, (30, 200, 255), -1, cv2.LINE_AA)
        cv2.circle(img, (x, y), sz, (255, 255, 255), 2, cv2.LINE_AA)
    elif kind == 2:
        cv2.circle(img, (x, y), sz // 2 + 2, (40, 40, 230), -1, cv2.LINE_AA)
    elif kind == 3:
        pts = np.array([[x, y - sz], [x + sz, y], [x, y + sz], [x - sz, y]], np.int32)
        cv2.fillPoly(img, [pts], (250, 250, 250), cv2.LINE_AA)
    else:
        cv2.rectangle(img, (x - sz // 2, y - sz // 3), (x + sz // 2, y + sz // 3), (200, 120, 20), -1)
        cv2.rectangle(img, (x - sz // 2, y - sz // 3), (x + sz // 2, y + sz // 3), (255, 255, 255), 1)


_FLOOR_CACHE: dict = {}


def render_floor_crop(fl, px0, py0, s, D, blur=0.0):
    """Underground minimap: the floor overlay (over the dark background) around (px0, py0); dark outside it."""
    key = (fl.floor_id, round(s, 3))
    img = _FLOOR_CACHE.get(key)
    if img is None:
        bgr, _ = fl.composite_z0()
        img = cv2.GaussianBlur(bgr, (0, 0), 0.45 / s) if s < 1 else bgr
        _FLOOR_CACHE[key] = img
    from .layers import DARK_BGR
    cxs, cys = px0 - fl.l, py0 - fl.t
    Mx = np.array([[s, 0, s * (0.5 - cxs) + D / 2 - 0.5], [0, s, s * (0.5 - cys) + D / 2 - 0.5]], np.float32)
    out = cv2.warpAffine(img, Mx, (D, D), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=DARK_BGR)
    return cv2.GaussianBlur(out, (0, 0), blur) if blur > 0 else out


_OPAQUE: dict = {}


def floor_opaque(fl, px0, py0, dil=12):
    """True where the (dilated) overlay has content: ground truth for 'the minimap shows this floor'."""
    m = _OPAQUE.get(fl.floor_id)
    if m is None:
        _, al = fl.composite_z0()
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * dil + 1, 2 * dil + 1))
        m = _OPAQUE[fl.floor_id] = (cv2.dilate((al > 0.15).astype(np.uint8), k) > 0, (al > 0.15))
    x, y = int(px0 - fl.l), int(py0 - fl.t)
    return 0 <= y < m[0].shape[0] and 0 <= x < m[0].shape[1] and bool(m[0][y, x])


class Scene:
    """Static look of one area: background, photometrics, illumination, world-anchored icons."""
    CELL = 90.0

    def __init__(self, seed: int, cfg: SynthCfg):
        rng = np.random.default_rng(seed)
        self.seed, self.cfg = seed, cfg
        bg = _bg(rng).astype(np.float32)
        tex = cv2.resize(rng.normal(0, 9, (54, 96)).astype(np.float32), (FRAME_W, FRAME_H), interpolation=cv2.INTER_CUBIC)
        tex += rng.normal(0, 4, (FRAME_H, FRAME_W)).astype(np.float32)
        self.bg = np.clip(bg + tex[..., None], 0, 255).astype(np.uint8)
        self.bg_dark = (self.bg.astype(np.float32) * 0.22).astype(np.uint8)
        D = 2 * int(np.ceil(cfg.r))
        self.illum = cv2.resize(rng.uniform(1 - cfg.illum, 1 + cfg.illum, (3, 3)).astype(np.float32), (D, D),
                                interpolation=cv2.INTER_CUBIC)

    def icons_near(self, px0, py0, radius):
        out = []
        i0, i1 = int(np.floor((px0 - radius) / self.CELL)), int(np.floor((px0 + radius) / self.CELL))
        j0, j1 = int(np.floor((py0 - radius) / self.CELL)), int(np.floor((py0 + radius) / self.CELL))
        dens = self.cfg.n_icons / 8.0
        for i in range(i0, i1 + 1):
            for j in range(j0, j1 + 1):
                r = np.random.default_rng([self.seed, i + 100000, j + 100000])
                for _ in range(int(r.poisson(dens))):
                    out.append((i * self.CELL + r.uniform(0, self.CELL), j * self.CELL + r.uniform(0, self.CELL),
                                int(r.integers(0, 5)), int(r.integers(8, 14))))
        return out


def render_world(info, scene: Scene, px0, py0, heading, rng, black=False, dark_bg=False, noise=None, floor=None):
    """Full frame of the open world (or a black domain minimap) from a persistent Scene."""
    cfg = scene.cfg
    frame = (scene.bg_dark if dark_bg else scene.bg).copy()
    R = cfg.r
    Ri = int(np.ceil(R))
    D = 2 * Ri
    if black:
        crop = np.full((D, D, 3), 5, np.uint8)
        a_glob = 1.0
    else:
        if floor is not None:
            crop = render_floor_crop(floor, px0, py0, cfg.s, D, cfg.blur).astype(np.float32)
        else:
            crop = render_crop(info, px0, py0, cfg.s, D, cfg.blur).astype(np.float32)
        crop = crop * np.array(cfg.tint, np.float32)
        crop = 255.0 * np.power(np.clip(crop / 255.0, 0, 1), cfg.gamma)
        crop = np.clip(crop * cfg.gain + cfg.bias, 0, 255) * scene.illum[..., None]
        crop = np.clip(crop, 0, 255).astype(np.uint8)
        a_glob = cfg.alpha
        for (ix, iy, kind, sz) in ([] if floor is not None else scene.icons_near(px0, py0, R / cfg.s * 1.1)):
            x = int(round(Ri + (ix - px0) * cfg.s))
            y = int(round(Ri + (iy - py0) * cfg.s))
            if np.hypot(x - Ri, y - Ri) < 0.9 * R:
                _icon_draw(crop, x, y, kind, sz)
    c2 = SynthCfg(**{**cfg.__dict__})
    c2.heading = float(heading)
    _draw_arrow(crop, c2, Ri)
    yy, xx = np.mgrid[0:D, 0:D].astype(np.float32)
    rr = np.hypot(xx + 0.5 - Ri, yy + 0.5 - Ri) / R
    a = np.clip((1.0 - rr) / (1.0 - cfg.rim_fade), 0, 1) * a_glob
    a[rr > 1.0] = 0
    x0, y0 = int(round(cfg.cx)) - Ri, int(round(cfg.cy)) - Ri
    roi = frame[y0:y0 + D, x0:x0 + D].astype(np.float32)
    frame[y0:y0 + D, x0:x0 + D] = (roi * (1 - a[..., None]) + crop.astype(np.float32) * a[..., None]).astype(np.uint8)
    draw_ring(frame, cfg.cx, cfg.cy, R)
    # sensor noise + JPEG only on the minimap region (the rest of the frame does not matter for the tests)
    m = 12
    ry0, rx0 = max(0, y0 - m), max(0, x0 - m)
    patch = frame[ry0:y0 + D + m, rx0:x0 + D + m]
    nz = cfg.noise if noise is None else noise
    if nz > 0:
        patch = np.clip(patch.astype(np.float32) + rng.normal(0, nz, patch.shape), 0, 255).astype(np.uint8)
    if cfg.jpeg:
        ok, buf = cv2.imencode(".jpg", patch, [cv2.IMWRITE_JPEG_QUALITY, int(cfg.jpeg)])
        patch = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    frame[ry0:y0 + D + m, rx0:x0 + D + m] = patch
    return frame


_TIPS = ("Tip: Elemental reactions can be triggered by combining different elements in battle.",
         "Tip: Standing near a Statue of the Seven restores HP for the whole party.",
         "Tip: Hold Alt to free the mouse cursor and use the Paimon menu.",
         "Tip: Adventurers can glide to cross wide gaps and cliffs quickly.")


def _finish_ui(frame, rng, noise=1.0, jpeg=90):
    if noise > 0:
        frame = np.clip(frame.astype(np.float32) + rng.normal(0, noise, frame.shape), 0, 255).astype(np.uint8)
    ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, jpeg])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def loading_frame(rng, kind="white", tips=None, vignette=None):
    """Plain white / black loading screen with a small bottom icon, progress bar and (optionally) tips text."""
    white = kind == "white"
    base = rng.uniform(238, 252) if white else rng.uniform(0, 7)
    frame = np.full((FRAME_H, FRAME_W, 3), base, np.float32)
    vig = rng.uniform(0, 0.06) if vignette is None else vignette
    yy, xx = np.mgrid[0:FRAME_H, 0:FRAME_W].astype(np.float32)
    rr = np.hypot((xx - FRAME_W / 2) / (FRAME_W / 2), (yy - FRAME_H / 2) / (FRAME_H / 2))
    frame *= (1 - vig * np.clip(rr - 0.6, 0, 1))[..., None]
    frame = np.clip(frame, 0, 255).astype(np.uint8)
    fg = (110, 105, 100) if white else (170, 165, 160)
    prog = int(rng.uniform(0.05, 1.0) * 520)
    cv2.rectangle(frame, (700, 1010), (1220, 1016), fg if not white else (200, 200, 200), -1)
    cv2.rectangle(frame, (700, 1010), (700 + prog, 1016), fg, -1)
    cx = int(rng.integers(900, 1020))
    cv2.circle(frame, (cx, 950), int(rng.integers(20, 34)), fg, 3, cv2.LINE_AA)
    cv2.circle(frame, (cx, 950), 8, fg, -1, cv2.LINE_AA)
    if (rng.random() < 0.5) if tips is None else tips:
        cv2.putText(frame, _TIPS[int(rng.integers(0, len(_TIPS)))], (180, 1050), cv2.FONT_HERSHEY_SIMPLEX, 0.9, fg, 2,
                    cv2.LINE_AA)
    return _finish_ui(frame, rng, noise=1.0)


def bigmap_frame(info, rng, px0, py0):
    """Big map screen: z-1 colour map around the player + dark UI chrome.  No minimap ring."""
    m = load_mosaic(info, -1, color=True)
    lv = level_geom(info, -1)
    u, v = lv.from_z0(px0, py0)
    x0 = int(np.clip(u - FRAME_W / 2, 0, max(0, m.shape[1] - FRAME_W)))
    y0 = int(np.clip(v - FRAME_H / 2, 0, max(0, m.shape[0] - FRAME_H)))
    frame = np.full((FRAME_H, FRAME_W, 3), (31, 23, 16), np.uint8)
    p = np.asarray(m[y0:y0 + FRAME_H, x0:x0 + FRAME_W])
    frame[:p.shape[0], :p.shape[1]] = p
    cv2.rectangle(frame, (0, 0), (FRAME_W, 70), (40, 45, 55), -1)
    cv2.rectangle(frame, (0, 0), (90, FRAME_H), (35, 40, 48), -1)
    for i in range(8):
        cv2.circle(frame, (45, 140 + i * 90), 26, (190, 200, 210), 3, cv2.LINE_AA)
    cv2.putText(frame, "Teyvat", (860, 48), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (230, 235, 240), 2, cv2.LINE_AA)
    for _ in range(25):
        _icon_draw(frame, int(rng.integers(150, FRAME_W - 50)), int(rng.integers(100, FRAME_H - 50)),
                   int(rng.integers(0, 5)), int(rng.integers(10, 16)))
    return _finish_ui(frame, rng, noise=1.0)


def menu_frame(rng):
    """Dark inventory / character menu: dark panels, tile grid, text-like bars.  No minimap."""
    frame = np.full((FRAME_H, FRAME_W, 3), rng.uniform(30, 55), np.uint8)
    for _ in range(14):
        c = int(rng.uniform(45, 90))
        x, y = int(rng.integers(0, 1500)), int(rng.integers(0, 800))
        cv2.rectangle(frame, (x, y), (x + int(rng.integers(150, 520)), y + int(rng.integers(100, 300))), (c, c, c + 8), -1)
    x0 = 1150
    for i in range(6):
        for j in range(7):
            c = int(rng.uniform(70, 150))
            cv2.rectangle(frame, (x0 + j * 105, 120 + i * 130), (x0 + j * 105 + 92, 120 + i * 130 + 112), (c, c - 10, c - 25), -1)
            cv2.rectangle(frame, (x0 + j * 105, 120 + i * 130), (x0 + j * 105 + 92, 120 + i * 130 + 112), (210, 215, 220), 1)
    for k in range(18):
        cv2.line(frame, (120, 160 + k * 38), (120 + int(rng.integers(150, 700)), 160 + k * 38), (200, 205, 210), 3)
    cv2.circle(frame, (600, 520), 230, (90, 80, 70), -1, cv2.LINE_AA)
    return _finish_ui(frame, rng, noise=1.5)


def cutscene_frame(scene: Scene, rng):
    """Letterboxed cut-scene: textured scene between black bars, no HUD."""
    frame = scene.bg.copy()
    frame[:135] = 0
    frame[-135:] = 0
    return _finish_ui(frame, rng, noise=1.0)


@_dc
class Seg:
    kind: str                 # walk stand sea bigmap menu cutscene loading fadein domain
    t0: float
    t1: float
    state: str                # expected tracker state ("SEA" = TRACKING or LOST accepted)
    area: int = 0
    path: object = None       # (P[n,2], H[n], dt) arrays or None
    pos: tuple | None = None  # constant position (px0, py0) when path is None
    meta: dict = None


class Scenario:
    """Timeline of gameplay events rendered on demand.

    plan items: (kind, duration[, arg]) with kind in
      walk(arg=speed z0px/s) stand bigmap menu cutscene
      teleport(arg='white'|'black'|None)   loading, then a far new position (new area)
      teleport_coast                         loading, then land position next to open sea (for 'sea')
      sea                                    land -> open sea -> land round trip (duration = fixed by geometry)
      domain_in / domain(dur) / domain_out   loading, black minimap, loading + return near the entrance
    """
    FADE = 0.8

    def __init__(self, info: MapInfo, seed: int, s: float = 0.8, plan=None, level: str = "mixed",
                 cx=138.0, cy=132.0, r=108.0):
        self.info, self.seed = info, seed
        rng = np.random.default_rng(seed)
        self.rng = rng
        self.terr = Terrain(info)
        from .layers import LayerIndex
        from .targets import load_targets, KINDS
        self.layers = LayerIndex(info)
        self.targets = load_targets(info)
        self._kinds = KINDS
        cfg = random_cfg(rng, s=s, level=level, cx=cx, cy=cy, r=r)
        cfg.noise = min(cfg.noise, 2.5)
        cfg.jpeg = max(cfg.jpeg, 75) if cfg.jpeg else 0
        cfg.n_icons = int(np.clip(cfg.n_icons, 3, 9))
        cfg.quest_ring = False
        self.cfg = cfg
        self.half = r / s
        self.s = s
        self.scenes: dict = {}
        self.segs: list[Seg] = []
        self._build(plan if plan is not None else master_plan(rng))
        self.duration = self.segs[-1].t1

    # ------------------------------------------------------------------ building
    def _scene(self, area):
        if area not in self.scenes:
            self.scenes[area] = Scene(self.seed * 1000 + area, self.cfg)
        return self.scenes[area]

    def _build(self, plan):
        rng, terr = self.rng, self.terr
        t = 0.0
        area = 0
        sx, sy = sample_position(self.info, rng, self.s, self.cfg.r)
        cur = (sx, sy)
        heading = float(rng.uniform(0, 360))
        pending_pos = None   # position after the current loading screen (None = domain)
        entrance = None
        for item in plan:
            kind, dur = item[0], item[1]
            arg = item[2] if len(item) > 2 else None
            if kind in ("walk", "stand"):
                if kind == "walk":
                    P, H = _walk_path(terr, rng, cur, dur, float(arg or 12.0), self.half)
                    cur, heading = tuple(P[-1]), float(H[-1])
                    self.segs.append(Seg("walk", t, t + dur, "TRACKING", area, (P, H, 0.1), None, {"v0": arg}))
                else:   # stand: camera slowly rotates, minimap content static
                    n = int(dur / 0.1) + 1
                    H = heading + np.linspace(0, float(rng.uniform(-60, 60)), n)
                    P = np.tile(np.array(cur), (n, 1))
                    self.segs.append(Seg("stand", t, t + dur, "TRACKING", area, (P, H, 0.1)))
                    heading = float(H[-1])
                t += dur
            elif kind in ("bigmap", "menu", "cutscene"):
                self.segs.append(Seg(kind, t, t + dur, "HIDDEN", area, None, cur))
                t += dur
            elif kind == "sea":
                (x0, y0), ang = self._coast
                L = 550.0
                spd = 25.0
                n_half = int(L / spd / 0.1)
                k = np.concatenate([np.linspace(0, L, n_half), np.linspace(L, 0, n_half)])
                P = np.stack([x0 + k * np.cos(ang), y0 + k * np.sin(ang)], 1)
                Hd = np.where(np.arange(len(k)) < n_half, np.degrees(ang) + 90, np.degrees(ang) + 270)
                d = 2 * L / spd
                self.segs.append(Seg("sea", t, t + d, "SEA", area, (P, Hd, 0.1)))
                cur, heading = (x0, y0), float(Hd[-1])
                t += d
            elif kind == "cave":
                cur, heading, t = self._cave(t, area, dur, arg == "multi")
            elif kind in ("teleport", "teleport_coast", "domain_in", "domain_out"):
                lk = arg if arg in ("white", "black") else ("white" if rng.random() < 0.5 else "black")
                dest = item[3] if len(item) > 3 else ("random" if kind == "teleport" else kind)
                meta = {"kind": lk, "dest": dest, "src": cur, "domain_exit": kind == "domain_out"}
                self.segs.append(Seg("loading", t, t + dur, "LOADING", area, None, None, meta))
                t += dur
                if kind == "teleport":
                    area += 1
                    cur = self._dest(cur, dest)
                elif kind == "teleport_coast":
                    area += 1
                    self._coast = _coast_path(terr, rng, self.half)
                    cur = self._coast[0]
                elif kind == "domain_in":
                    entrance = cur
                else:   # domain_out: back near the entrance
                    area += 1
                    a, d = rng.uniform(0, 2 * np.pi), rng.uniform(0, 60)
                    cur = (entrance[0] + d * np.cos(a), entrance[1] + d * np.sin(a))
                    entrance = None
                meta["dst"] = cur if kind != "domain_in" else None
                if kind == "domain_in":
                    self.segs.append(Seg("fadein", t, t + self.FADE, "RELOCATING", area, None, None, {"domain": True}))
                else:
                    self.segs.append(Seg("fadein", t, t + self.FADE, "RELOCATING", area, None, cur))
                t += self.FADE
            elif kind == "domain":
                self.segs.append(Seg("domain", t, t + dur, "DOMAIN", area, None, entrance))
                t += dur
            else:
                raise ValueError(kind)

    def _dest(self, cur, dest):
        rng = self.rng
        if dest == "same":
            a, d = rng.uniform(0, 2 * np.pi), rng.uniform(0, 30)
            return (cur[0] + d * np.cos(a), cur[1] + d * np.sin(a))
        if dest in self._kinds:
            idx = self.targets.of_kind(dest)
            for _ in range(400):
                i = int(rng.choice(idx))
                a, d = rng.uniform(0, 2 * np.pi), rng.uniform(5, 40)
                p = (self.targets.xy[i, 0] + d * np.cos(a), self.targets.xy[i, 1] + d * np.sin(a))
                if np.hypot(p[0] - cur[0], p[1] - cur[1]) > 600 and self.terr.land_ok(p[0], p[1], self.half, 0.2):
                    return p
        if dest in ("cave", "cave_multi"):
            self._site = self._pick_site(dest == "cave_multi")
            return self._site["S"]
        return self._far(cur)

    # ------------------------------------------------------------------ caves
    def _line_pts(self, a, b, step=10.0):
        n = max(2, int(np.hypot(b[0] - a[0], b[1] - a[1]) / step) + 1)
        return np.stack([np.linspace(a[0], b[0], n), np.linspace(a[1], b[1], n)], 1)

    def _best_line(self, X, rects, inward: bool, fl):
        """Direction from X to a point 70 px beyond all rects (or reverse); needs land outside, max opaque inside."""
        best = None
        for k in range(24):
            ang = k * np.pi / 12
            d = 0.0
            while any(f.contains(X[0] + d * np.cos(ang), X[1] + d * np.sin(ang), 70.0) for f in rects) and d < 900:
                d += 10.0
            E = (X[0] + d * np.cos(ang), X[1] + d * np.sin(ang))
            pts = self._line_pts(X, E)
            outside = [q for q in pts if not any(f.contains(q[0], q[1]) for f in rects)]
            if not outside or not all(self.terr.land_ok(q[0], q[1], self.half, 0.2) for q in outside[::2]):
                continue
            inside = [q for q in pts if any(f.contains(q[0], q[1]) for f in rects)]
            sc = np.mean([floor_opaque(fl, q[0], q[1]) for q in inside]) if inside else 0.0
            if best is None or sc > best[0]:
                best = (sc, E)
        return best

    def _pick_site(self, multi):
        rng = self.rng
        li = self.layers
        for _ in range(3000):
            if multi:
                g = [gf for gf in li.groups.values() if len(gf) >= 2]
                fs = g[int(rng.integers(0, len(g)))]
                A, B = (fs[int(i)] for i in rng.choice(len(fs), 2, replace=False))
            else:
                A = li.floors[int(rng.integers(0, len(li.floors)))]
                B = None
            if not (110 <= A.r - A.l <= 450 and 110 <= A.b - A.t <= 450):
                continue
            if B is not None and not (110 <= B.r - B.l <= 450 and 110 <= B.b - B.t <= 450):
                continue
            floor_opaque(A, A.l, A.t)
            ma = _OPAQUE[A.floor_id][1]
            if ma.mean() < 0.2:
                continue
            ys, xs = np.nonzero(cv2.erode(ma.astype(np.uint8), np.ones((9, 9), np.uint8)))
            if len(ys) < 50:
                continue
            if B is not None:
                floor_opaque(B, B.l, B.t)
                cand = []
                for j in rng.choice(len(ys), min(len(ys), 400), replace=False):
                    x, y = A.l + xs[j] + 0.5, A.t + ys[j] + 0.5
                    xb, yb = int(x - B.l), int(y - B.t)
                    mb = _OPAQUE[B.floor_id][1]
                    if 0 <= yb < mb.shape[0] and 0 <= xb < mb.shape[1] and mb[yb, xb] and floor_opaque(B, x, y, 0):
                        cand.append((x, y))
                if not cand:
                    continue
                X = cand[int(rng.integers(0, len(cand)))]
            else:
                j = int(rng.integers(0, len(ys)))
                X = (A.l + xs[j] + 0.5, A.t + ys[j] + 0.5)
            rects = [A] + ([B] if B else [])
            bl = self._best_line(X, rects, True, A)
            if bl is None or bl[0] < 0.7:
                continue
            return dict(A=A, B=B, X=X, S=bl[1])
        raise RuntimeError("no cave site")

    def _walk_in(self, fl, start, dur, v0=9.0, dt=0.1):
        rng = self.rng
        n = int(dur / dt) + 1
        x, y = start
        th = rng.uniform(0, 2 * np.pi)
        P, H = np.zeros((n, 2)), np.zeros(n)
        for i in range(n):
            P[i], H[i] = (x, y), th
            th += rng.normal(0, 0.3 * np.sqrt(dt))
            for _ in range(16):
                nx, ny = x + v0 * dt * np.cos(th), y + v0 * dt * np.sin(th)
                if floor_opaque(fl, nx, ny, 0) and _OPAQUE[fl.floor_id][1][max(0, int(ny - fl.t)), max(0, int(nx - fl.l))]:
                    x, y = nx, ny
                    break
                th += rng.uniform(0.7, 2.4)
        return P, np.degrees(H) + 90.0

    def _cave(self, t, area, dur, multi):
        site = self._site
        A, B, X, S = site["A"], site["B"], site["X"], site["S"]
        v = 12.0
        pts = self._line_pts(S, X, v * 0.1)
        P_in = pts
        P, L = [P_in], [[A.floor_id if floor_opaque(A, q[0], q[1]) else None for q in P_in]]
        cur_fl = A
        if multi:
            n4 = 40
            P.append(np.tile(np.array(X), (n4, 1))); L.append([A.floor_id] * n4)
            P.append(np.tile(np.array(X), (n4, 1))); L.append([B.floor_id] * n4)
            cur_fl = B
        Pw, _ = self._walk_in(cur_fl, X, dur)
        P.append(Pw); L.append([cur_fl.floor_id] * len(Pw))
        bl = self._best_line(tuple(Pw[-1]), [A] + ([B] if B else []), False, cur_fl)
        E = bl[1] if bl else (Pw[-1][0] + 120, Pw[-1][1])
        Pe = self._line_pts(tuple(Pw[-1]), E, v * 0.1)
        P.append(Pe)
        L.append([cur_fl.floor_id if floor_opaque(cur_fl, q[0], q[1]) else None for q in Pe])
        P = np.concatenate(P)
        Lr = np.array([x for l in L for x in l], dtype=object)
        d = np.diff(P, axis=0, prepend=P[:1] - 1e-6)
        H = np.degrees(np.arctan2(d[:, 0], -d[:, 1]))
        n = len(P)
        self.segs.append(Seg("cave", t, t + n * 0.1, "TRACKING", area, (P, H, 0.1), None,
                             {"layers": Lr, "floors": (A.floor_id, B.floor_id if B else None)}))
        return tuple(P[-1]), float(H[-1]), t + n * 0.1

    def _far(self, cur):
        for _ in range(200):
            p = sample_position(self.info, self.rng, self.s, self.cfg.r)
            if np.hypot(p[0] - cur[0], p[1] - cur[1]) > 1500:
                return p
        return p

    # ------------------------------------------------------------------ queries
    def seg_at(self, t):
        t = min(max(t, 0.0), self.duration - 1e-6)
        for i, sg in enumerate(self.segs):
            if sg.t0 <= t < sg.t1:
                return i, sg
        return len(self.segs) - 1, self.segs[-1]

    def pose_at(self, t):
        """(px0, py0, heading) at time t for world segments, or None."""
        i, sg = self.seg_at(t)
        if sg.path is not None:
            P, H, dt = sg.path
            k = min(len(P) - 1, int(round((t - sg.t0) / dt)))
            return float(P[k, 0]), float(P[k, 1]), float(H[k])
        if sg.pos is not None:
            return sg.pos[0], sg.pos[1], 0.0
        return None

    def truth(self, t):
        i, sg = self.seg_at(t)
        p = self.pose_at(t) if sg.kind in ("walk", "stand", "sea", "fadein", "bigmap", "menu", "cutscene", "cave") else None
        if sg.kind == "fadein" and sg.meta and sg.meta.get("domain"):
            p = None
        floor_id = None
        if sg.kind == "cave":
            k = min(len(sg.meta["layers"]) - 1, int(round((t - sg.t0) / 0.1)))
            floor_id = sg.meta["layers"][k]
        in_zone = bool(p is not None and self.layers.in_zone(p[0], p[1]))
        fl = self.layers.by_id.get(floor_id) if floor_id else None
        return dict(state=sg.state, kind=sg.kind, seg=i, t_seg=t - sg.t0,
                    px0=None if p is None else p[0], py0=None if p is None else p[1],
                    in_world=sg.kind in ("walk", "stand", "sea", "fadein", "cave") and p is not None,
                    floor_id=floor_id, group_id=fl.group_id if fl else None, in_zone=in_zone,
                    dest=(sg.meta or {}).get("dest") if sg.kind == "loading" else None)

    def frame_at(self, t):
        rng = np.random.default_rng([self.seed, int(round(t * 1000)) + 7])
        i, sg = self.seg_at(t)
        k = sg.kind
        sc = self._scene(sg.area)
        if k in ("walk", "stand", "sea"):
            px, py, hd = self.pose_at(t)
            return render_world(self.info, sc, px, py, hd, rng)
        if k == "cave":
            px, py, hd = self.pose_at(t)
            j = min(len(sg.meta["layers"]) - 1, int(round((t - sg.t0) / 0.1)))
            fid = sg.meta["layers"][j]
            return render_world(self.info, sc, px, py, hd, rng, floor=self.layers.by_id[fid] if fid else None)
        if k == "loading":
            return loading_frame(rng, sg.meta["kind"])
        if k == "fadein":
            wh = 1.0 - (0.2 + 0.8 * (t - sg.t0) / self.FADE)   # white fraction
            if sg.meta and sg.meta.get("domain"):
                f = render_world(self.info, sc, 0, 0, 0.0, rng, black=True, dark_bg=True)
            else:
                f = render_world(self.info, sc, sg.pos[0], sg.pos[1], 0.0, rng)
            return np.clip(f.astype(np.float32) * (1 - wh) + 245.0 * wh, 0, 255).astype(np.uint8)
        if k == "domain":
            hd = (t * 25.0) % 360
            return render_world(self.info, sc, 0, 0, hd, rng, black=True, dark_bg=True, noise=2.0)
        if k == "bigmap":
            return bigmap_frame(self.info, rng, *sg.pos)
        if k == "menu":
            return menu_frame(rng)
        if k == "cutscene":
            return cutscene_frame(sc, rng)
        raise ValueError(k)

    def at(self, t):
        return self.frame_at(t), self.truth(t)


def master_plan(rng):
    """~5.5 minutes: walk, stand, map, menu, teleports (waypoint / domain target / random), domain, sea."""
    lk = lambda: "white" if rng.random() < 0.5 else "black"
    j = lambda a, b: float(rng.uniform(a, b))
    return [("walk", j(35, 45), 10.0), ("stand", j(22, 28)), ("bigmap", j(6, 10)), ("walk", j(12, 18), 18.0),
            ("menu", j(4, 7)), ("walk", j(8, 12), 30.0),
            ("bigmap", 5.0), ("teleport", j(4, 7), lk(), "waypoint"), ("walk", j(18, 24), 10.0),
            ("bigmap", 4.0), ("teleport", j(4, 7), lk(), "domain"), ("walk", 8.0, 10.0),
            ("domain_in", j(4, 6), lk()), ("domain", j(25, 35)), ("domain_out", j(4, 6), lk()), ("walk", j(15, 20), 12.0),
            ("cutscene", 6.0), ("walk", 8.0, 10.0),
            ("teleport", j(4, 6), lk(), "random"), ("walk", 10.0, 10.0),
            ("teleport_coast", j(4, 6), lk()), ("sea", 0), ("walk", 20.0, 12.0)]


def plain_walk_plan(rng, dur=180.0):
    """Walking only (with fast stretches): proves 'zero full searches during normal play'."""
    return [("walk", dur * 0.4, 10.0), ("walk", dur * 0.2, 28.0), ("walk", dur * 0.4, 14.0)]


def teleport_plan(rng, n=16):
    """n teleports exercising all three relocation paths: waypoints/statues/domains, 'same' (respawn / pot exit), random."""
    dests = ["waypoint"] * 7 + ["statue"] * 2 + ["domain"] * 2 + ["same"] * 3 + ["random"] * 2
    dests = [dests[i] for i in rng.permutation(len(dests))][:n]
    plan = [("walk", 6.0, 10.0)]
    for d in dests:
        plan += [("teleport", float(rng.uniform(4, 6)), "white" if rng.random() < 0.5 else "black", d), ("walk", 6.0, 10.0)]
    return plan


def cave_plan(rng):
    lk = "white" if rng.random() < 0.5 else "black"
    return [("walk", 6.0, 10.0), ("teleport", 4.0, lk, "cave_multi"), ("cave", 14.0, "multi"), ("walk", 8.0, 10.0),
            ("teleport", 4.0, lk, "cave"), ("cave", 14.0, "single"), ("walk", 8.0, 10.0)]
