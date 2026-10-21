"""Big-map pin detection + comparison with HoYoLAB point coordinates (alignment check)."""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np

from . import ref, bigmap

ROOT = Path(__file__).resolve().parents[3]
PACK = ROOT / "data" / "pack" / "maps" / "2"


def load_points():
    return json.loads((PACK / "points.json").read_text())["point_list"]


def project(A, off, xp, yp):
    Ai = cv2.invertAffineTransform(A)
    u, v = ref.pos_to_uv(0, xp, yp)
    pts = np.stack([u - off[0], v - off[1], np.ones_like(u)], 0)
    return (Ai @ pts)   # (2, N) screen px


def detect(frame, tmpl, thr=0.62, nms=20):
    res = cv2.matchTemplate(frame, tmpl, cv2.TM_CCOEFF_NORMED)
    out = []
    r = res.copy()
    h, w = tmpl.shape[:2]
    while True:
        iy, ix = np.unravel_index(int(r.argmax()), r.shape)
        sc = float(r[iy, ix])
        if sc < thr:
            break
        out.append((ix + w / 2.0, iy + h / 2.0, sc))
        r[max(0, iy - nms):iy + nms + 1, max(0, ix - nms):ix + nms + 1] = -1
    return out
