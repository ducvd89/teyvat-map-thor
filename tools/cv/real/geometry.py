"""Minimap geometry from the static HUD pattern of many frames (ring circle fit etc.)."""
from __future__ import annotations

import cv2
import numpy as np


def hp_stack_median(tl_frames, sigma=2.0):
    st = []
    for f in tl_frames:
        g = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY).astype(np.float32)
        st.append(g - cv2.GaussianBlur(g, (0, 0), sigma))
    return np.median(np.stack(st), axis=0)


def ring_points(hp, c0=(211.0, 155.0), r0=133.0, rw=9.0, nang=360, sign=None):
    """Per angle, sub-pixel radius of the strongest ring response (|hp| max) around r0.  Returns (angles, radii, strength)."""
    th = np.linspace(0, 2 * np.pi, nang, endpoint=False)
    rr = np.arange(r0 - rw, r0 + rw + 1e-6, 0.25)
    H, W = hp.shape
    X = c0[0] + rr[None, :] * np.cos(th)[:, None]
    Y = c0[1] + rr[None, :] * np.sin(th)[:, None]
    prof = cv2.remap(hp, X.astype(np.float32), Y.astype(np.float32), cv2.INTER_LINEAR)
    pr = prof if sign is None else prof * sign
    k = np.argmax(np.abs(pr) if sign is None else pr, axis=1)
    return th, rr[k], np.abs(prof[np.arange(nang), k])


def fit_circle(pts, iters=5, thr=1.5):
    """Least-squares circle with iterative outlier rejection."""
    pts = np.asarray(pts, np.float64)
    keep = np.ones(len(pts), bool)
    for _ in range(iters):
        P = pts[keep]
        A = np.c_[2 * P[:, 0], 2 * P[:, 1], np.ones(len(P))]
        b = (P ** 2).sum(1)
        sol = np.linalg.lstsq(A, b, rcond=None)[0]
        cx, cy = sol[0], sol[1]
        r = np.sqrt(sol[2] + cx * cx + cy * cy)
        d = np.abs(np.hypot(pts[:, 0] - cx, pts[:, 1] - cy) - r)
        keep = d < thr
    return cx, cy, r, keep, d
