"""Minimap crop + mask + icon suppression + contrast normalisation.

The *same* representation function is used for the reference map mosaics and for
the minimap template so that both live in the same feature space.

Representations (``Params.repr``):
  lcn   - local contrast normalisation: (g - local_mean) / (local_std + c)   [default]
  clahe - CLAHE on gray (tile ~32 px), then mean-centred
  grad  - normalised gradient magnitude (illumination/colour invariant)
Prepared images are stored as uint8: 128 + 42 * value  (value clipped to +-3).
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace

import cv2
import numpy as np

from .mapdata import CV_DIR, MapInfo, level_geom, load_mosaic

ENC = 42.0


@dataclass
class Calib:
    """Minimap circle in the full 1080p frame (native px) + scale s (minimap px per z0 px)."""
    cx: float = 138.0
    cy: float = 132.0
    r: float = 108.0
    s: float = 0.8
    ring_ref: list | None = None   # border-ring gray samples from calibration (screen.fit_ring_ref)


@dataclass
class Params:
    repr: str = "lcn"
    sigma: float = 1.2          # LCN window sigma in working-level px (small = fine texture; tuned on recall)
    lcn_c: float = 6.0          # std floor (gray levels) so flat areas stay flat
    r_in: float = 0.15          # exclude centre arrow (fraction of R)
    r_out: float = 0.88         # exclude faded rim
    icons: bool = True
    sat_thr: int = 175          # HSV S (0-255) above which (and V>val_thr) a blob is an icon
    val_thr: int = 150
    white_thr: int = 232        # near-white (V) with S<white_sat
    white_sat: int = 40
    max_blob: float = 0.035     # max icon blob area as fraction of pi R^2
    dilate: int = 3


def tag(p: Params) -> str:
    return f"{p.repr}{p.sigma:g}"


# ----------------------------------------------------------------------------- masks
def circle_mask(T: int, r_in_px: float, r_out_px: float) -> np.ndarray:
    yy, xx = np.mgrid[0:T, 0:T].astype(np.float32)
    rr = np.hypot(xx + 0.5 - T / 2, yy + 0.5 - T / 2)
    return ((rr >= r_in_px) & (rr <= r_out_px)).astype(np.uint8)


def _blob_filter(m: np.ndarray, max_area: float, max_side: float) -> np.ndarray:
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
    keep = np.zeros(n, np.uint8)
    for i in range(1, n):
        a, w, h = st[i, cv2.CC_STAT_AREA], st[i, cv2.CC_STAT_WIDTH], st[i, cv2.CC_STAT_HEIGHT]
        if a <= max_area and max(w, h) <= max_side:
            keep[i] = 1
    return keep[lab]


def icon_mask(bgr: np.ndarray, R: float, p: Params) -> np.ndarray:
    """uint8 mask, 1 where pixel belongs to an overlaid icon (saturated or near-white blob)."""
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV_FULL)
    s, v = hsv[..., 1], hsv[..., 2]
    sat = ((s > p.sat_thr) & (v > p.val_thr)).astype(np.uint8)
    white = ((v > p.white_thr) & (s < p.white_sat)).astype(np.uint8)
    sat = cv2.morphologyEx(sat, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    max_area, max_side = p.max_blob * np.pi * R * R, 0.3 * R
    m = _blob_filter(sat, max_area, max_side) | _blob_filter(white, max_area, max_side)
    if p.dilate > 0 and m.any():
        k = 2 * p.dilate + 1
        m = cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    return m


# ----------------------------------------------------------------------------- repr
def _nconv(img: np.ndarray, m: np.ndarray, sigma: float):
    """Normalised (masked) gaussian convolution; returns blurred, weight."""
    w = cv2.GaussianBlur(m, (0, 0), sigma, borderType=cv2.BORDER_REFLECT)
    b = cv2.GaussianBlur(img * m, (0, 0), sigma, borderType=cv2.BORDER_REFLECT)
    return b / np.maximum(w, 1e-3), w


def fill_masked(g: np.ndarray, valid: np.ndarray, sigma: float = 4.0) -> np.ndarray:
    """Replace invalid pixels with a smooth masked-average estimate."""
    if valid.all():
        return g
    out = g.copy()
    est, w = _nconv(g, valid, sigma)
    bad = valid == 0
    # second pass with larger sigma for pixels whose neighbourhood is also invalid
    est2, _ = _nconv(g, valid, sigma * 3)
    est = np.where(w > 0.05, est, est2)
    out[bad] = est[bad]
    return out


def represent(gray: np.ndarray, p: Params, valid: np.ndarray | None = None) -> np.ndarray:
    """gray: float32/uint8 image -> float32 representation, roughly zero-mean, unit-ish scale (+-3)."""
    g = gray.astype(np.float32)
    v = np.ones_like(g) if valid is None else valid.astype(np.float32)
    if valid is not None:
        g = fill_masked(g, v)
        v = np.ones_like(g)  # after fill, treat everything as valid for the statistics
    if p.repr == "lcn":
        mean = cv2.GaussianBlur(g, (0, 0), p.sigma, borderType=cv2.BORDER_REFLECT)
        var = cv2.GaussianBlur(g * g, (0, 0), p.sigma, borderType=cv2.BORDER_REFLECT) - mean * mean
        out = (g - mean) / (np.sqrt(np.maximum(var, 0)) + p.lcn_c)
        # soften the very-high-frequency component (JPEG / resampling noise)
        out = cv2.GaussianBlur(out, (0, 0), 0.7)
    elif p.repr == "clahe":
        cl = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(max(2, g.shape[1] // 32), max(2, g.shape[0] // 32)))
        c = cl.apply(np.clip(g, 0, 255).astype(np.uint8)).astype(np.float32)
        out = (c - cv2.GaussianBlur(c, (0, 0), 24)) / 48.0
    elif p.repr == "grad":
        b = cv2.GaussianBlur(g, (0, 0), 1.0)
        gx, gy = cv2.Sobel(b, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(b, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx * gx + gy * gy)
        loc = cv2.GaussianBlur(mag, (0, 0), max(8.0, 2 * p.sigma), borderType=cv2.BORDER_REFLECT)
        out = np.clip(mag / (loc + 6.0), 0, 4) - 1.0
    else:
        raise ValueError(p.repr)
    return out


def encode(x: np.ndarray) -> np.ndarray:
    return np.clip(128.0 + ENC * np.clip(x, -3, 3), 0, 255).astype(np.uint8)


def decode(u8: np.ndarray) -> np.ndarray:
    """uint8 -> float32 centred (value*ENC)."""
    return u8.astype(np.float32) - 128.0


# ----------------------------------------------------------------------------- minimap
def crop_minimap(frame: np.ndarray, c: Calib) -> np.ndarray:
    """Square crop of side D=2*ceil(r) centred (sub-pixel) on the minimap centre."""
    D = 2 * int(np.ceil(c.r))
    return cv2.getRectSubPix(frame, (D, D), (c.cx - 0.5, c.cy - 0.5))


@dataclass
class Template:
    img: np.ndarray        # float32 (T,T) centred values (value*ENC), zero where masked
    mask: np.ndarray       # float32 (T,T) 0/1
    T: int
    ft: float              # effective template scale (template px per native minimap px)
    crop: np.ndarray = field(repr=False, default=None)
    valid_native: np.ndarray = field(repr=False, default=None)


def make_template(frame_or_crop: np.ndarray, c: Calib, level_z: int, p: Params = Params(),
                  s: float | None = None, is_crop: bool = False) -> Template:
    """Template for matching against the level-`level_z` prepared mosaic at minimap scale s."""
    crop = frame_or_crop if is_crop else crop_minimap(frame_or_crop, c)
    D = crop.shape[0]
    R = D / 2.0 if is_crop else c.r
    s = c.s if s is None else s
    ft = (2.0 ** level_z) / s
    T = max(8, int(round(D * ft / 2.0)) * 2)
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY).astype(np.float32)
    valid = np.ones(gray.shape, np.uint8)
    if p.icons:
        valid = (1 - icon_mask(crop, R, p)).astype(np.uint8)
    interp = cv2.INTER_AREA if T < D else cv2.INTER_CUBIC
    g_s = cv2.resize(fill_masked(gray, valid), (T, T), interpolation=interp)
    v_s = cv2.resize(valid.astype(np.float32), (T, T), interpolation=cv2.INTER_AREA)
    ft_eff = T / D
    rep = represent(g_s, p)
    mask = circle_mask(T, p.r_in * R * ft_eff, p.r_out * R * ft_eff).astype(np.float32) * (v_s > 0.9)
    return Template(rep * ENC * mask, mask.astype(np.float32), T, ft_eff, crop, valid)


# ----------------------------------------------------------------------------- mosaics
def prep_path(info: MapInfo, z: int, p: Params):
    return CV_DIR / f"prep_{info.map_id}_{info.version[:8]}_z{z}_{tag(p)}.npy"


def build_prepped(info: MapInfo, z: int, p: Params = Params(), force: bool = False) -> np.ndarray:
    """Preprocess a whole gray mosaic (blockwise, with halo) and cache as uint8 .npy; returns memmap."""
    out = prep_path(info, z, p)
    if out.exists() and not force:
        return np.load(out, mmap_mode="r")
    src = load_mosaic(info, z, color=False)
    H, W = src.shape
    import os
    tmp = out.with_suffix(f".{os.getpid()}.tmp.npy")  # unique: parallel workers may race
    mm = np.lib.format.open_memmap(tmp, mode="w+", dtype=np.uint8, shape=(H, W))
    B, halo = 1536, int(max(32, 6 * max(p.sigma, 8)))
    for y in range(0, H, B):
        y0, y1 = max(0, y - halo), min(H, y + B + halo)
        rep = represent(np.asarray(src[y0:y1]), p)
        mm[y:min(H, y + B)] = encode(rep[y - y0:y - y0 + min(B, H - y)])
    mm.flush()
    del mm
    import os
    os.replace(tmp, out)
    return np.load(out, mmap_mode="r")


# ----------------------------------------------------------------------------- calibration helper
def find_minimap_circle(frame: np.ndarray, region=(0, 0, 420, 420)):
    """Detect the minimap circle in the top-left corner via Hough. Returns Calib-like (cx, cy, r) or None."""
    x0, y0, x1, y1 = region
    g = cv2.cvtColor(frame[y0:y1, x0:x1], cv2.COLOR_BGR2GRAY)
    g = cv2.GaussianBlur(g, (5, 5), 1.5)
    cs = cv2.HoughCircles(g, cv2.HOUGH_GRADIENT, dp=1, minDist=50, param1=100, param2=40,
                          minRadius=60, maxRadius=170)
    if cs is None:
        return None
    cx, cy, r = cs[0][0]
    return float(cx + x0), float(cy + y0), float(r)
