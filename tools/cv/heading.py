"""Player arrow heading from the gold arrow in the centre of the minimap."""
from __future__ import annotations

import cv2
import numpy as np


def estimate_heading(crop_bgr: np.ndarray, rad_frac: float = 0.22):
    """Return (heading_deg, confidence) with 0 = north/up, clockwise; (None, 0) if no arrow found.

    HSV threshold for the gold/yellow arrow, largest contour near the centre, then the angle from
    the contour centroid to its farthest point (the tip).  Better than the centroid->farthest-point
    on a symmetric triangle: use the farthest point from the centroid among hull points, weighted
    toward the point with the sharpest corner.
    """
    D = crop_bgr.shape[0]
    R = D / 2.0
    c = int(R)
    h = int(rad_frac * R)
    roi = crop_bgr[c - h:c + h, c - h:c + h]
    up = 4                                               # work at 4x for sub-pixel tip accuracy
    roi = cv2.resize(roi, None, fx=up, fy=up, interpolation=cv2.INTER_CUBIC)
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    # gold/yellow-orange: H 10-38 (OpenCV 0-180), strong saturation and brightness
    strict = cv2.inRange(hsv, (12, 135, 165), (36, 255, 255))
    loose = cv2.inRange(hsv, (8, 115, 130), (38, 255, 255))
    # grow slightly from the strict seed into the loose mask (picks up the shaded part of the arrow)
    # drop gold regions that touch the ROI border: that is terrain (the arrow is enclosed by its outline)
    n, lab = cv2.connectedComponents(strict)
    border = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
    strict = np.where(np.isin(lab, border[border > 0]), 0, strict).astype(np.uint8)
    grow = cv2.dilate(strict, np.ones((3, 3), np.uint8), iterations=2)
    m = cv2.bitwise_and(loose, grow) | strict
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cnts:
        return None, 0.0
    # candidates must be reasonably big and near the centre; take the biggest of those
    ctr = h * up
    def near(cn):
        mm = cv2.moments(cn)
        return mm["m00"] > 0 and np.hypot(mm["m10"] / mm["m00"] - ctr, mm["m01"] / mm["m00"] - ctr) < 0.55 * ctr
    cnts = [cn for cn in cnts if near(cn)]
    if not cnts:
        return None, 0.0
    amax = max(cv2.contourArea(cn) for cn in cnts)
    cnts = [cn for cn in cnts if cv2.contourArea(cn) >= 0.3 * amax]

    def dist(cn):
        mm = cv2.moments(cn)
        return np.hypot(mm["m10"] / mm["m00"] - ctr, mm["m01"] / mm["m00"] - ctr)
    cnt = min(cnts, key=dist)                        # the arrow sits at the exact centre
    area = cv2.contourArea(cnt) / (up * up)
    if area < 10:
        return None, 0.0
    fill = cv2.drawContours(np.zeros_like(m), [cnt], -1, 255, -1)
    ys, xs = np.nonzero(fill)
    P = np.stack([xs, ys], 1).astype(np.float64)
    c0 = P.mean(0)
    Q = P - c0
    w_, v_ = np.linalg.eigh(np.cov(Q.T))
    u = v_[:, 1]                                   # principal axis (tip <-> tail)
    if w_[1] < 1.08 * w_[0]:
        return None, 0.0                           # nearly round blob: no reliable direction
    t = Q @ u
    wid = np.abs(Q @ np.array([-u[1], u[0]]))
    # direction sign: a dart-shaped arrow has its mass near the base and a thin tip, so the
    # projection onto the axis is skewed towards the tip (positive third moment)
    if float((t ** 3).mean()) < 0:
        u = -u
    ang = np.degrees(np.arctan2(u[0], -u[1])) % 360.0
    conf = float(np.clip(1.0 - np.sqrt(w_[0] / w_[1]), 0, 1))
    return float(ang), conf


def angdiff(a: float, b: float) -> float:
    return (a - b + 180.0) % 360.0 - 180.0


class HeadingSmoother:
    """Circular exponential smoothing."""

    def __init__(self, alpha: float = 0.5):
        self.alpha, self.v = alpha, None

    def update(self, ang):
        if ang is None:
            return self.v
        if self.v is None:
            self.v = ang
        else:
            self.v = (self.v + self.alpha * angdiff(ang, self.v)) % 360.0
        return self.v
