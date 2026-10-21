"""Side-by-side visualisations: real minimap crop vs. matched map patch."""
from __future__ import annotations

import cv2
import numpy as np

from ..mapdata import level_geom
from . import geom, ref
from .scan import crop_minimap


def map_patch(x_pos, y_pos, s, D=268, color=True):
    """D x D patch of the map centred on (x_pos, y_pos) showing the area a minimap of scale s would show
    (D/s z0 px wide), taken from the finest sufficient level and resampled to D px."""
    from ..mapdata import level_geom
    side0 = D / s                                # z0 px covered
    z = int(np.clip(np.floor(np.log2(max(s, 1e-3)) + 1e-6), -3, 0))   # finest level that still downsamples
    f = 2.0 ** z
    lv = level_geom(ref.INFO, z)
    mos = ref.mosaic(z, color)
    cu, cv_ = ref.pos_to_uv(z, x_pos, y_pos)
    side = side0 * f
    M = np.array([[D / side, 0, D / 2 - cu * D / side], [0, D / side, D / 2 - cv_ * D / side]], np.float32)
    # take a window of the mosaic around the centre to avoid warping the whole mosaic
    half = int(side * 0.75) + 4
    u0, v0 = int(cu) - half, int(cv_) - half
    ua, va = max(0, u0), max(0, v0)
    ub, vb = min(lv.w, int(cu) + half), min(lv.h, int(cv_) + half)
    win = np.asarray(mos[va:vb, ua:ub])
    if win.size == 0:
        return np.zeros((D, D, 3 if color else 1), np.uint8)
    M2 = M.copy()
    M2[0, 2] += ua * D / side
    M2[1, 2] += va * D / side
    interp = cv2.INTER_AREA if D / side < 1 else cv2.INTER_CUBIC
    out = cv2.warpAffine(win, M2, (D, D), flags=interp, borderValue=(16, 17, 24))
    return out


def circ(img, r_frac=1.0):
    D = img.shape[0]
    m = np.zeros((D, D), np.uint8)
    cv2.circle(m, (D // 2, D // 2), int(D / 2 * r_frac), 255, -1)
    out = img.copy()
    out[m == 0] = 0
    return out


def put(img, text, y=18, color=(0, 255, 255)):
    img = img.copy()
    cv2.putText(img, text, (5, y), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 3)
    cv2.putText(img, text, (5, y), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)
    return img


def side_by_side(frame, x_pos, y_pos, s, label="", D=268):
    crop = circ(crop_minimap(frame), 1.0)
    patch = circ(map_patch(x_pos, y_pos, s, D), 1.0)
    # enhanced versions to compare structure
    def enh(im):
        g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
        c = cv2.createCLAHE(3.0, (4, 4)).apply(g)
        return cv2.cvtColor(c, cv2.COLOR_GRAY2BGR)
    return np.hstack([put(crop, "minimap " + label), put(patch, f"map s={s:.3f} ({x_pos:.0f},{y_pos:.0f})"),
                      put(circ(enh(crop)), "minimap CLAHE"), put(circ(enh(patch)), "map CLAHE")])


def plot_curves(curves, path, size=(900, 420), xlog=True, title="", xlabel="scale", vlines=()):
    """curves: dict name -> (xs, ys).  Minimal cv2 line plot (no matplotlib in the venv)."""
    W, H = size
    img = np.full((H, W, 3), 255, np.uint8)
    ml, mr, mt, mb = 60, 20, 30, 40
    allx = np.concatenate([np.asarray(c[0], float) for c in curves.values()])
    ally = np.concatenate([np.asarray(c[1], float) for c in curves.values()])
    x0, x1 = float(allx.min()), float(allx.max())
    y0, y1 = min(0.0, float(ally.min())), max(1.0, float(ally.max()))
    fx = (lambda x: np.log(x)) if xlog else (lambda x: x)
    def X(x): return int(ml + (fx(x) - fx(x0)) / (fx(x1) - fx(x0)) * (W - ml - mr))
    def Y(y): return int(H - mb - (y - y0) / (y1 - y0) * (H - mt - mb))
    cv2.rectangle(img, (ml, mt), (W - mr, H - mb), (0, 0, 0), 1)
    for yy in np.linspace(y0, y1, 6):
        cv2.putText(img, f"{yy:.2f}", (5, Y(yy) + 4), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 0, 0), 1)
        cv2.line(img, (ml, Y(yy)), (W - mr, Y(yy)), (230, 230, 230), 1)
    for xv in np.geomspace(x0, x1, 8) if xlog else np.linspace(x0, x1, 8):
        cv2.putText(img, f"{xv:.2f}", (X(xv) - 14, H - mb + 16), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 0, 0), 1)
    for v in vlines:
        cv2.line(img, (X(v), mt), (X(v), H - mb), (150, 150, 255), 1)
    cols = [(200, 0, 0), (0, 140, 0), (0, 0, 200), (200, 120, 0), (140, 0, 140), (0, 140, 140), (80, 80, 80)]
    for k, (name, (xs, ys)) in enumerate(curves.items()):
        pts = np.array([[X(x), Y(y)] for x, y in zip(xs, ys)], np.int32)
        cv2.polylines(img, [pts], False, cols[k % len(cols)], 2)
        cv2.putText(img, name, (ml + 8, mt + 16 + 14 * k), cv2.FONT_HERSHEY_SIMPLEX, 0.45, cols[k % len(cols)], 1)
    cv2.putText(img, title, (ml, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1)
    cv2.putText(img, xlabel, (W // 2, H - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 1)
    cv2.imwrite(str(path), img)
