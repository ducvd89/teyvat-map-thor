"""Visualisation helpers shared by cv_proto and label_helper."""
from __future__ import annotations

import cv2
import numpy as np

from .mapdata import MapInfo, level_geom, load_mosaic
from .preprocess import Calib, Template, crop_minimap


def map_patch(info: MapInfo, px0: float, py0: float, s: float, D: int) -> np.ndarray:
    """Colour map patch (D x D) at the minimap's scale s centred on z0 (px0, py0)."""
    from .synth import render_crop
    return render_crop(info, px0, py0, s, D)


def circle_view(img: np.ndarray, r_frac: float = 1.0) -> np.ndarray:
    out = img.copy()
    D = img.shape[0]
    yy, xx = np.mgrid[0:D, 0:D]
    out[np.hypot(xx + 0.5 - D / 2, yy + 0.5 - D / 2) > r_frac * D / 2] = 0
    return out


def put_text(img, text, org=(6, 16), scale=0.5):
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 0, 0), 3, cv2.LINE_AA)
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, (255, 255, 255), 1, cv2.LINE_AA)
    return img


def heat_img(res: np.ndarray, size: int = 256) -> np.ndarray:
    h = np.clip(res, 0, 1)
    h = (h * 255).astype(np.uint8)
    h = cv2.applyColorMap(h, cv2.COLORMAP_INFERNO)
    return cv2.resize(h, (size, size), interpolation=cv2.INTER_NEAREST)


def tmpl_img(tm: Template) -> np.ndarray:
    g = np.clip(tm.img + 128, 0, 255).astype(np.uint8)
    g[tm.mask == 0] = 0
    return cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)


def mask_img(tm: Template) -> np.ndarray:
    return cv2.cvtColor((tm.mask * 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
