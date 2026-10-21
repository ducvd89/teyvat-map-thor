"""Cheap per-frame screen tests (PLAN.md section 6.2). Each must stay well under 1 ms.

  thumbnail(frame)            -> 64x36 gray (3x3 samples per 30x30 block, mean)
  is_loading_screen(thumb)    -> near-uniform and very bright / very dark
  ring_present(frame, calib)  -> minimap border ring vs the calibrated reference (Pearson correlation)
  minimap_is_black(crop)      -> domain case: dark, flat, (almost) no edges in the outer annulus
  frame_changed(prev, crop)   -> minimap content moved / changed (standing-still detector)

All thresholds live in ``ScreenCfg`` (``CFG``); the Kotlin port copies them verbatim.  They were chosen on
synthetic frames and MUST be confirmed on real Thor screenshots (real loading screens, real domain minimaps).
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from .preprocess import Calib

THUMB_W, THUMB_H = 64, 36


@dataclass
class ScreenCfg:
    # --- loading screen: >= load_frac of thumbnail pixels within +-load_band of the median, and the median
    #     is very bright (white screen) or very dark (black screen / fade).  Small non-uniform areas
    #     (progress bar, element icon, tips text, vignette) are tolerated by the 8 % allowance.
    load_frac: float = 0.92
    load_band: float = 12.0
    load_bright: float = 228.0
    load_dark: float = 26.0
    # --- minimap ring: gray samples on ring_angles x radii (R+ring_r0 .. R+ring_r1, step ring_dr)
    ring_angles: int = 180
    ring_r0: float = -1.5
    ring_r1: float = 5.5
    ring_dr: float = 1.0
    ring_thr: float = 0.70      # Pearson correlation with the reference; >= thr -> ring present
    # --- black minimap (domain): outer annulus of the minimap crop only (the centre arrow / view cone is excluded)
    black_r_in: float = 0.62
    black_r_out: float = 0.88
    black_median: float = 8.0   # gray median <= this (open-sea tiles are ~22, underground background ~20: NOT black)
    black_p90: float = 24.0     # 90th percentile <= this (tolerates a few icons / noise)
    black_edge_thr: int = 96    # |sobel_x|+|sobel_y| (ksize 3, 16-bit) above this = an edge pixel
    black_edge_frac: float = 0.03
    # --- frame_changed: 32x32 gray signature, annulus 0.55R..0.88R, mean abs difference
    chg_size: int = 32
    chg_r_in: float = 0.55
    chg_r_out: float = 0.88
    chg_thr: float = 1.5
    # --- flat (open sea): outer annulus, 90th-10th percentile spread and edge fraction; searching a flat minimap is pointless
    flat_spread: float = 6.0
    flat_edge_frac: float = 0.10  # loose: icons over the sea add a few % edge pixels; the spread test does the work


CFG = ScreenCfg()


# ----------------------------------------------------------------------------- loading screen
def thumbnail(frame: np.ndarray) -> np.ndarray:
    """64x36 gray thumbnail.  For 1920x1080: take every 10th pixel (108x192 samples, offset 5) and average
    3x3 samples per thumbnail pixel (INTER_AREA).  Equivalent to a sparse 30x30 block mean."""
    h, w = frame.shape[:2]
    sy, sx = max(1, h // 108), max(1, w // 192)
    sub = frame[sy // 2::sy, sx // 2::sx]
    small = cv2.resize(sub, (THUMB_W, THUMB_H), interpolation=cv2.INTER_AREA)
    return small if small.ndim == 2 else cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)


def loading_stats(thumb: np.ndarray, cfg: ScreenCfg = CFG):
    med = float(np.median(thumb))
    frac = float(np.mean(np.abs(thumb.astype(np.int16) - med) <= cfg.load_band))
    return med, frac


def is_loading_screen(thumb: np.ndarray, cfg: ScreenCfg = CFG) -> bool:
    med, frac = loading_stats(thumb, cfg)
    return (med >= cfg.load_bright or med <= cfg.load_dark) and frac >= cfg.load_frac


# ----------------------------------------------------------------------------- minimap ring
_RING_IDX: dict = {}
_RING_REF: dict = {}


def _ring_idx(calib: Calib, cfg: ScreenCfg):
    key = (round(calib.cx, 2), round(calib.cy, 2), round(calib.r, 2), cfg.ring_angles, cfg.ring_r0, cfg.ring_r1, cfg.ring_dr)
    v = _RING_IDX.get(key)
    if v is None:
        th = np.linspace(0, 2 * np.pi, cfg.ring_angles, endpoint=False)
        rad = calib.r + np.arange(cfg.ring_r0, cfg.ring_r1 + 1e-9, cfg.ring_dr)
        xs = np.round(calib.cx + rad[:, None] * np.cos(th)[None]).astype(np.intp).ravel()
        ys = np.round(calib.cy + rad[:, None] * np.sin(th)[None]).astype(np.intp).ravel()
        v = _RING_IDX[key] = (ys, xs)
    return v


def ring_signature(frame: np.ndarray, calib: Calib, cfg: ScreenCfg = CFG) -> np.ndarray:
    """Gray samples (float32) of the ring annulus at fixed polar positions."""
    ys, xs = _ring_idx(calib, cfg)
    px = frame[ys, xs].astype(np.float32)
    if px.ndim == 1:
        return px
    return px[:, 0] * 0.114 + px[:, 1] * 0.587 + px[:, 2] * 0.299


def fit_ring_ref(frame: np.ndarray, calib: Calib, cfg: ScreenCfg = CFG) -> Calib:
    """Store the ring reference in ``calib.ring_ref`` (call once during calibration on a frame with the minimap visible)."""
    calib.ring_ref = [round(float(v), 1) for v in ring_signature(frame, calib, cfg)]
    return calib


def ring_ncc(frame: np.ndarray, calib: Calib, cfg: ScreenCfg = CFG) -> float:
    ref = calib.ring_ref
    if ref is None:
        return 1.0
    r = _RING_REF.get(id(ref))
    if r is None or r[0] is not ref:
        a = np.asarray(ref, np.float32)
        a = a - a.mean()
        r = _RING_REF[id(ref)] = (ref, a, float(np.sqrt((a * a).sum())))
    a, na = r[1], r[2]
    v = ring_signature(frame, calib, cfg)
    v = v - v.mean()
    nv = float(np.sqrt((v * v).sum()))
    if nv < 1e-3 or na < 1e-3:
        return 0.0
    return float((v @ a) / (nv * na))


def ring_present(frame: np.ndarray, calib: Calib, cfg: ScreenCfg = CFG) -> bool:
    return ring_ncc(frame, calib, cfg) >= cfg.ring_thr


# ----------------------------------------------------------------------------- black minimap (domain)
_MASKS: dict = {}


def annulus_mask(D: int, r_in: float, r_out: float) -> np.ndarray:
    """bool (D,D): r_in*R <= radius <= r_out*R, R = D/2."""
    key = (D, r_in, r_out)
    m = _MASKS.get(key)
    if m is None:
        yy, xx = np.mgrid[0:D, 0:D].astype(np.float32)
        rr = np.hypot(xx + 0.5 - D / 2, yy + 0.5 - D / 2) / (D / 2)
        m = _MASKS[key] = (rr >= r_in) & (rr <= r_out)
    return m


def black_stats(crop: np.ndarray, mask: np.ndarray | None = None, cfg: ScreenCfg = CFG):
    g = crop if crop.ndim == 2 else cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    m = annulus_mask(g.shape[0], cfg.black_r_in, cfg.black_r_out) if mask is None else (mask > 0)
    v = g[m]
    k = int(0.9 * len(v))
    p90 = float(np.partition(v, k)[k])
    med = float(np.median(v))
    gx = cv2.Sobel(g, cv2.CV_16S, 1, 0, ksize=3)
    gy = cv2.Sobel(g, cv2.CV_16S, 0, 1, ksize=3)
    edge = float(((np.abs(gx) + np.abs(gy)) > cfg.black_edge_thr)[m].mean())
    return med, p90, edge


def minimap_is_black(crop: np.ndarray, mask: np.ndarray | None = None, cfg: ScreenCfg = CFG) -> bool:
    """Domain minimap: dark, flat, no edges.  Call only when the ring is present."""
    med, p90, edge = black_stats(crop, mask, cfg)
    return med <= cfg.black_median and p90 <= cfg.black_p90 and edge <= cfg.black_edge_frac


def minimap_is_flat(crop: np.ndarray, mask: np.ndarray | None = None, cfg: ScreenCfg = CFG) -> bool:
    """Textureless minimap (open sea): tiny gray spread and no edges in the outer annulus.  Used only to skip
    whole-map / target searches, never to decide a state."""
    g = crop if crop.ndim == 2 else cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    m = annulus_mask(g.shape[0], cfg.black_r_in, cfg.black_r_out) if mask is None else (mask > 0)
    v = g[m]
    k0, k1 = int(0.1 * len(v)), int(0.9 * len(v))
    pv = np.partition(v, (k0, k1))
    if float(pv[k1]) - float(pv[k0]) > cfg.flat_spread:
        return False
    gx = cv2.Sobel(g, cv2.CV_16S, 1, 0, ksize=3)
    gy = cv2.Sobel(g, cv2.CV_16S, 0, 1, ksize=3)
    return float(((np.abs(gx) + np.abs(gy)) > cfg.black_edge_thr)[m].mean()) <= cfg.flat_edge_frac


# ----------------------------------------------------------------------------- frame change
def minimap_sig(crop: np.ndarray, cfg: ScreenCfg = CFG) -> np.ndarray:
    g = crop if crop.ndim == 2 else cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    return cv2.resize(g, (cfg.chg_size, cfg.chg_size), interpolation=cv2.INTER_AREA).astype(np.float32)


def sig_diff(a: np.ndarray, b: np.ndarray, cfg: ScreenCfg = CFG) -> float:
    m = annulus_mask(cfg.chg_size, cfg.chg_r_in, cfg.chg_r_out)
    return float(np.abs(a - b)[m].mean())


def frame_diff(prev_crop: np.ndarray, crop: np.ndarray, cfg: ScreenCfg = CFG) -> float:
    return sig_diff(minimap_sig(prev_crop, cfg), minimap_sig(crop, cfg), cfg)


def frame_changed(prev_crop: np.ndarray, crop: np.ndarray, cfg: ScreenCfg = CFG) -> bool:
    return frame_diff(prev_crop, crop, cfg) > cfg.chg_thr
