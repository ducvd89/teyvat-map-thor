"""Big-map frame -> map registration with SIFT (similarity transform).  Used as independent ground truth."""
from __future__ import annotations

import cv2
import numpy as np

from . import ref


def register(frame_bgr, box, z=-1, ui_mask_rects=None, ratio=0.78, nfeat=6000):
    """Returns dict(A (2x3 screen->level-z uv), inliers, scale, centre_pos (x,y of screen centre), z)."""
    img, off = ref.region(z, *box)
    g_ref = cv2.cvtColor(ref.region(z, *box, color=True)[0], cv2.COLOR_BGR2GRAY)
    g_scr = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
    m = np.full(g_scr.shape, 255, np.uint8)
    for (x0, y0, x1, y1) in (ui_mask_rects or []):
        m[y0:y1, x0:x1] = 0
    cl = cv2.createCLAHE(2.0, (8, 8))
    sift = cv2.SIFT_create(nfeatures=nfeat)
    k1, d1 = sift.detectAndCompute(cl.apply(g_scr), m)
    k2, d2 = sift.detectAndCompute(cl.apply(g_ref), None)
    bf = cv2.BFMatcher()
    mm = bf.knnMatch(d1, d2, k=2)
    good = [a for a, b in (x for x in mm if len(x) == 2) if a.distance < ratio * b.distance]
    if len(good) < 6:
        return None
    src = np.float32([k1[a.queryIdx].pt for a in good])
    dst = np.float32([k2[a.trainIdx].pt for a in good])
    A, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=4.0, maxIters=5000, confidence=0.999)
    if A is None:
        return None
    sc = float(np.hypot(A[0, 0], A[1, 0]))
    rot = float(np.degrees(np.arctan2(A[1, 0], A[0, 0])))
    H, W = g_scr.shape
    c = A @ np.array([W / 2, H / 2, 1.0])
    x, y = ref.uv_to_pos(z, off[0] + c[0], off[1] + c[1])
    return dict(A=A, off=off, z=z, inliers=int(inl.sum()), matches=len(good), scale=sc, rot=rot, centre_pos=(x, y), nk=(len(k1), len(k2)))
