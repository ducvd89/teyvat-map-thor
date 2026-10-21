"""Cyan player arrow: segmentation + heading (real minimap).  HSV (OpenCV H 0-180): H 88-102, S 120-170, V >= 205
with a V >= 235 core (snow/ice minimap background has V <= ~230)."""
from __future__ import annotations

import cv2
import numpy as np

CX, CY = 210.0, 155.6       # pixel-index centre of the minimap in the frame (see geom.py)


def arrow_mask(frame, half=24, up=4, core_v=235):
    x0, y0 = int(round(CX)) - half, int(round(CY)) - half
    roi = frame[y0:y0 + 2 * half, x0:x0 + 2 * half]
    roi = cv2.resize(roi, None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    core = ((h >= 88) & (h <= 102) & (s >= 115) & (s <= 175) & (v >= core_v)).astype(np.uint8)
    loose = ((h >= 86) & (h <= 104) & (s >= 100) & (v >= 190)).astype(np.uint8)
    grow = cv2.dilate(core, np.ones((3, 3), np.uint8), iterations=3)
    m = (loose & grow) | core
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, st, cen = cv2.connectedComponentsWithStats(m)
    if n <= 1:
        return None, up, (x0, y0)
    # component nearest to the centre with decent area
    ctr = np.array([half * up, half * up], float)
    best, bd = None, 1e9
    for i in range(1, n):
        if st[i, cv2.CC_STAT_AREA] < 40 * up * up / 4:
            continue
        d = np.hypot(*(cen[i] - ctr))
        if d < bd:
            best, bd = i, d
    if best is None or bd > 10 * up:
        return None, up, (x0, y0)
    return (lab == best).astype(np.uint8), up, (x0, y0)


def heading(frame):
    """Returns (heading_deg clockwise from north/up, confidence, centroid_xy_in_frame, area_px) or (None, 0, None, 0)."""
    m, up, (x0, y0) = arrow_mask(frame)
    if m is None:
        return None, 0.0, None, 0
    ys, xs = np.nonzero(m)
    P = np.stack([xs, ys], 1).astype(np.float64)
    c = P.mean(0)
    Q = P - c
    cov = np.cov(Q.T)
    w, v = np.linalg.eigh(cov)
    # the arrow outline: hull vertex with the sharpest interior angle = tip
    cnt = max(cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)[0], key=cv2.contourArea)
    hull = cv2.convexHull(cnt)
    poly = cv2.approxPolyDP(hull, 0.03 * cv2.arcLength(hull, True), True)[:, 0, :].astype(float)
    best_ang, tip = 1e9, None
    k = len(poly)
    for i in range(k):
        a, b, d = poly[i - 1], poly[i], poly[(i + 1) % k]
        u, w_ = a - b, d - b
        cosang = np.dot(u, w_) / (np.linalg.norm(u) * np.linalg.norm(w_) + 1e-9)
        ang = np.arccos(np.clip(cosang, -1, 1))
        if ang < best_ang:
            best_ang, tip = ang, b
    vec = tip - c
    # the arrow is a dart: the tip is also where the mass is thinnest; vec from the centroid to the tip
    hd = (np.degrees(np.arctan2(vec[0], -vec[1]))) % 360.0
    conf = float(np.clip(1.0 - best_ang / np.radians(120), 0, 1))
    cen_frame = (x0 + c[0] / up, y0 + c[1] / up)
    return float(hd), conf, cen_frame, int(m.sum() / (up * up))


# ---------------------------------------------------------------------------------------------- rotation search
_REF = {}


def _centered_mask(m):
    ys, xs = np.nonzero(m)
    c = np.array([xs.mean(), ys.mean()])
    S = m.shape[0]
    M = np.float32([[1, 0, S / 2 - c[0]], [0, 1, S / 2 - c[1]]])
    return cv2.warpAffine(m.astype(np.float32), M, (S, S)), c


def build_reference(frame, ref_heading_deg, up=4):
    """Canonical arrow shape from one clean frame whose tip direction (clockwise from north) is ref_heading_deg."""
    m, up_, _ = arrow_mask(frame, up=up)
    cm, _ = _centered_mask(m)
    S = cm.shape[0]
    # rotate so that the tip points north (heading 0)
    R = cv2.getRotationMatrix2D((S / 2, S / 2), ref_heading_deg, 1.0)   # +angle = counter-clockwise in image coords
    # rotating by +ref_heading (CCW) brings a clockwise-heading=ref arrow to north
    north = cv2.warpAffine(cm, R, (S, S))
    return north


def heading_rot(frame, ref_north, step=3):
    m, up, (x0, y0) = arrow_mask(frame)
    if m is None:
        return None, 0.0
    cm, _ = _centered_mask(m)
    S = cm.shape[0]
    best = (-1, 0)
    scores = []
    for a in range(0, 360, step):
        R = cv2.getRotationMatrix2D((S / 2, S / 2), -a, 1.0)   # rotate the north-up template clockwise by a
        t = cv2.warpAffine(ref_north, R, (S, S))
        inter = float((t * cm).sum())
        uni = float(np.maximum(t, cm).sum()) + 1e-6
        iou = inter / uni
        scores.append(iou)
        if iou > best[0]:
            best = (iou, a)
    scores = np.array(scores)
    # sub-step refinement by parabola
    k = int(np.argmax(scores))
    a0 = k * step
    if 0 < k < len(scores) - 1:
        a, b, c = scores[k - 1], scores[k], scores[k + 1]
        d = a - 2 * b + c
        if abs(d) > 1e-9:
            a0 += 0.5 * (a - c) / d * step
    return float(a0 % 360.0), float(best[0])


def _arrow_gray(frame, up=4, half=24):
    """Arrow-centred crop (centroid at the centre), V channel as float, plus the arrow mask (centred)."""
    m, up_, (x0, y0) = arrow_mask(frame, half=half, up=up)
    if m is None:
        return None, None
    x0r, y0r = int(round(CX)) - half, int(round(CY)) - half
    roi = cv2.resize(frame[y0r:y0r + 2 * half, x0r:x0r + 2 * half], None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
    v = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)[..., 2].astype(np.float32)
    ys, xs = np.nonzero(m)
    c = np.array([xs.mean(), ys.mean()])
    S = m.shape[0]
    M = np.float32([[1, 0, S / 2 - c[0]], [0, 1, S / 2 - c[1]]])
    vc = cv2.warpAffine(v, M, (S, S), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    mc = cv2.warpAffine(m.astype(np.float32), M, (S, S))
    return vc, mc


def build_reference_v(frame, ref_heading_deg):
    vc, mc = _arrow_gray(frame)
    S = vc.shape[0]
    R = cv2.getRotationMatrix2D((S / 2, S / 2), ref_heading_deg, 1.0)
    return cv2.warpAffine(vc, R, (S, S)), cv2.warpAffine(mc, R, (S, S))


def heading_v(frame, ref_v, ref_m, step=3):
    vc, mc = _arrow_gray(frame)
    if vc is None:
        return None, 0.0
    S = vc.shape[0]
    scores = []
    for a in range(0, 360, step):
        R = cv2.getRotationMatrix2D((S / 2, S / 2), -a, 1.0)
        tv = cv2.warpAffine(ref_v, R, (S, S))
        tm = cv2.warpAffine(ref_m, R, (S, S))
        w = np.maximum(tm, mc) > 0.5
        x, y = tv[w], vc[w]
        x = x - x.mean(); y = y - y.mean()
        scores.append(float((x * y).sum() / (np.sqrt((x * x).sum() * (y * y).sum()) + 1e-6)))
    scores = np.array(scores)
    k = int(np.argmax(scores))
    a0 = k * step
    if 0 < k < len(scores) - 1:
        a, b, c = scores[k - 1], scores[k], scores[k + 1]
        d = a - 2 * b + c
        if abs(d) > 1e-9:
            a0 += 0.5 * (a - c) / d * step
    srt = np.sort(scores)
    return float(a0 % 360.0), float(scores[k]), float(scores[k] - srt[-6])   # (angle, ncc, margin vs the 6th best)
