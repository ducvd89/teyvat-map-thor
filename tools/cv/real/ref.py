"""Reference-map helpers (region crops of the gray / colour mosaics) + representations for the real minimap."""
from __future__ import annotations

import cv2
import numpy as np

from ..mapdata import load_info, load_mosaic, level_geom
from ..preprocess import Params, represent, fill_masked, circle_mask, icon_mask

INFO = load_info(2)
_MOS = {}


def mosaic(z, color=False):
    k = (z, color)
    if k not in _MOS:
        _MOS[k] = load_mosaic(INFO, z, color=color)
    return _MOS[k]


def pos_to_z0(x_pos, y_pos):
    return x_pos + INFO.origin[0], y_pos + INFO.origin[1]


def z0_to_pos(px, py):
    return px - INFO.origin[0], py - INFO.origin[1]


def region(z, x0, y0, x1, y1, color=False):
    """Crop of the level-z mosaic covering x_pos/y_pos box; returns (img, (u0, v0)) level-px offset of img[0,0]."""
    lv = level_geom(INFO, z)
    f = 2.0 ** z
    u0 = max(0, int(np.floor((x0 + INFO.origin[0]) * f - lv.ox)))
    v0 = max(0, int(np.floor((y0 + INFO.origin[1]) * f - lv.oy)))
    u1 = min(lv.w, int(np.ceil((x1 + INFO.origin[0]) * f - lv.ox)))
    v1 = min(lv.h, int(np.ceil((y1 + INFO.origin[1]) * f - lv.oy)))
    return np.ascontiguousarray(mosaic(z, color)[v0:v1, u0:u1]), (u0, v0)


def uv_to_pos(z, u, v):
    lv = level_geom(INFO, z)
    f = 2.0 ** z
    return (u + lv.ox) / f - INFO.origin[0], (v + lv.oy) / f - INFO.origin[1]


def pos_to_uv(z, x_pos, y_pos):
    lv = level_geom(INFO, z)
    f = 2.0 ** z
    return (x_pos + INFO.origin[0]) * f - lv.ox, (y_pos + INFO.origin[1]) * f - lv.oy
