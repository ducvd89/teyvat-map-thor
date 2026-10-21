"""Icon-constellation localisation: waypoint pins detected in the minimap -> (position, scale) from >= 2 pins
against the HoYoLAB waypoint list.  No rotation (the minimap is north-up), so a pin pair gives a vector whose
direction AND length must match a waypoint pair: very selective."""
from __future__ import annotations

import json
from dataclasses import dataclass

import cv2
import numpy as np

from . import geom
from .pins import PACK


def load_waypoints(labels=(3,)):
    P = json.loads((PACK / "points.json").read_text())["point_list"]
    xy = np.array([(p["x_pos"], p["y_pos"]) for p in P if p["label_id"] in labels], float)
    return xy


class PinDetector:
    """Template match of the white waypoint pin in the minimap crop (template cut from a clean frame)."""

    def __init__(self, tmpl_bgr, centre=(14.0, 19.5)):
        self.t, self.c = tmpl_bgr, np.array(centre)

    def detect(self, frame, thr=0.72, r_max=0.97 * geom.R_RING):
        res = cv2.matchTemplate(frame, self.t, cv2.TM_CCOEFF_NORMED)
        out, r = [], res.copy()
        while True:
            iy, ix = np.unravel_index(int(r.argmax()), r.shape)
            sc = float(r[iy, ix])
            if sc < thr:
                break
            px, py = ix + self.c[0], iy + self.c[1]
            r[max(0, iy - 15):iy + 16, max(0, ix - 15):ix + 16] = -1
            if np.hypot(px - geom.CX_IDX, py - geom.CY_IDX) < r_max:
                out.append((float(px - geom.CX_IDX), float(py - geom.CY_IDX), sc))   # offsets from the minimap centre
        return out


def solve_pairs(pins, wps, s_range=(0.2, 4.0), dir_tol=2.5, len_tol=0.06, region=None, bias=(0.0, 0.0)):
    """pins: [(dx, dy, score)] minimap-px offsets (icon centres).  wps: (N,2) x_pos,y_pos.
    Returns hypotheses [(score, x, y, s, (i,j,a,b))] sorted best first.  `bias` = (dx, dy) in minimap px added to the
    detected icon centre to get the true anchor (HoYoLAB coordinate = icon centre + bias)."""
    if region is not None:
        x0, y0, x1, y1 = region
        m = (wps[:, 0] >= x0) & (wps[:, 0] <= x1) & (wps[:, 1] >= y0) & (wps[:, 1] <= y1)
        wps = wps[m]
    P = np.array([(p[0] + bias[0], p[1] + bias[1]) for p in pins])
    hyps = []
    for i in range(len(P)):
        for j in range(len(P)):
            if i == j:
                continue
            dm = P[j] - P[i]
            Lm = np.hypot(*dm)
            if Lm < 20:
                continue
            ang_m = np.degrees(np.arctan2(dm[1], dm[0]))
            D = wps[None, :, :] - wps[:, None, :]            # D[a,b] = wps[b]-wps[a]
            L = np.hypot(D[..., 0], D[..., 1])
            ang = np.degrees(np.arctan2(D[..., 1], D[..., 0]))
            s = Lm / np.maximum(L, 1e-6)
            dang = np.abs((ang - ang_m + 180) % 360 - 180)
            ok = (s >= s_range[0]) & (s <= s_range[1]) & (dang <= dir_tol) & (L > 1)
            aa, bb = np.nonzero(ok)
            for a, b in zip(aa, bb):
                ss = float(s[a, b])
                pos = wps[a] - P[i] / ss
                # verification: how many detected pins are explained by some waypoint at this (pos, s)?
                pred = (wps - pos) * ss
                expl = 0
                for q in P:
                    if np.min(np.hypot(pred[:, 0] - q[0], pred[:, 1] - q[1])) < 4.0 + 0.03 * np.hypot(*q):
                        expl += 1
                hyps.append((expl - 0.1 * abs(dang[a, b]) / dir_tol, float(pos[0]), float(pos[1]), ss, (i, j, int(a), int(b))))
    hyps.sort(key=lambda h: -h[0])
    return hyps


def solve_single(pin, wps, s, region=None, bias=(0.0, 0.0)):
    """One pin + known scale: one candidate position per waypoint (ambiguous: needs an NCC check at each)."""
    if region is not None:
        x0, y0, x1, y1 = region
        m = (wps[:, 0] >= x0) & (wps[:, 0] <= x1) & (wps[:, 1] >= y0) & (wps[:, 1] <= y1)
        wps = wps[m]
    off = np.array([pin[0] + bias[0], pin[1] + bias[1]]) / s
    return wps - off
