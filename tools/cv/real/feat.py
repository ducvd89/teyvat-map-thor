"""Scale-invariant feature matching (SIFT / AKAZE / ORB + RANSAC similarity) of the real minimap crop against a map region."""
from __future__ import annotations

import time

import cv2
import numpy as np

from ..preprocess import Params, icon_mask
from . import ref
from .scan import crop_minimap


def _detector(kind, nfeat=1500):
    if kind == "sift":
        return cv2.SIFT_create(nfeatures=nfeat, contrastThreshold=0.01), cv2.NORM_L2
    if kind == "orb":
        return cv2.ORB_create(2500, 1.2, 8, 15, 0, 2, cv2.ORB_HARRIS_SCORE, 31, 10), cv2.NORM_HAMMING
    raise ValueError(kind)


def prep_gray(img_bgr, mode="clahe"):
    g = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY) if img_bgr.ndim == 3 else img_bgr
    if mode == "clahe":
        return cv2.createCLAHE(3.0, (4, 4)).apply(g)
    if mode == "norm":
        g = g.astype(np.float32)
        g = (g - g.mean()) / (g.std() + 1e-3)
        return np.clip(128 + 48 * g, 0, 255).astype(np.uint8)
    return g


def match(frame, box, z=-1, kind="sift", mode="clahe", up=2, ratio=0.8, r_in=0.15, r_out=0.9, min_inl=8, nfeat=1500):
    """Returns dict(ok, x, y, s, inliers, n_good, ms).  s = minimap px per z0 px."""
    t0 = time.perf_counter()
    crop = crop_minimap(frame)
    D = crop.shape[0]
    R = D / 2.0
    yy, xx = np.mgrid[0:D, 0:D]
    rr = np.hypot(xx + 0.5 - R, yy + 0.5 - R) / R
    valid = ((rr >= r_in) & (rr <= r_out)).astype(np.uint8)
    valid &= (1 - icon_mask(crop, R, Params())).astype(np.uint8)
    # arrow: remove the saturated cyan blob + dilate (icon_mask covers only some of it)
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    arrow = ((hsv[..., 0] >= 86) & (hsv[..., 0] <= 104) & (hsv[..., 1] >= 100) & (hsv[..., 2] >= 190)).astype(np.uint8)
    cn = np.zeros(arrow.shape, np.uint8)
    cv2.circle(cn, (D // 2, D // 2), int(0.2 * R), 1, -1)
    arrow = cv2.dilate(arrow & cn, np.ones((5, 5), np.uint8), iterations=2)
    valid &= (1 - arrow)
    g = prep_gray(crop, mode)
    if up != 1:
        g = cv2.resize(g, None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
        valid = cv2.resize(valid, None, fx=up, fy=up, interpolation=cv2.INTER_NEAREST)
    valid = cv2.erode(valid, np.ones((3, 3), np.uint8)) * 255
    det, norm = _detector(kind, nfeat)
    k1, d1 = det.detectAndCompute(g, valid)
    img = ref.region(z, *box, color=True)
    mp, off = img
    g2 = prep_gray(mp, mode)
    k2, d2 = det.detectAndCompute(g2, None)
    res = dict(ok=False, inliers=0, n_good=0, nk=(len(k1) if k1 else 0, len(k2) if k2 else 0))
    if d1 is None or d2 is None or len(k1) < 8:
        res["ms"] = (time.perf_counter() - t0) * 1e3
        return res
    bf = cv2.BFMatcher(norm)
    kn = bf.knnMatch(d1, d2, k=2)
    good = [a for a, b in (m for m in kn if len(m) == 2) if a.distance < ratio * b.distance]
    res["n_good"] = len(good)
    if len(good) >= 4:
        src = np.float32([k1[a.queryIdx].pt for a in good])
        dst = np.float32([k2[a.trainIdx].pt for a in good])
        A, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=3.0, maxIters=4000, confidence=0.995)
        if A is not None and np.hypot(A[0, 0], A[1, 0]) > 1e-3:
            scale = float(np.hypot(A[0, 0], A[1, 0]))      # template px -> level-z px
            rot = float(np.degrees(np.arctan2(A[1, 0], A[0, 0])))
            c = A @ np.array([R * up, R * up, 1.0])
            x, y = ref.uv_to_pos(z, off[0] + c[0], off[1] + c[1])
            # template px = minimap px * up -> level-z px per minimap px = scale*up; minimap px per z0 px = 2^z / (scale*up)
            s = (2.0 ** z) / (scale * up)
            res.update(ok=int(inl.sum()) >= min_inl and abs(rot) < 8, inliers=int(inl.sum()), x=float(x), y=float(y), s=float(s), rot=rot)
    res["ms"] = (time.perf_counter() - t0) * 1e3
    return res
