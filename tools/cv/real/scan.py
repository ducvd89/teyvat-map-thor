"""Multi-scale template scanning of a real minimap crop against a reference region (experiments)."""
from __future__ import annotations

import time
from dataclasses import dataclass

import cv2
import numpy as np

from ..preprocess import Params, circle_mask, fill_masked, icon_mask, represent, ENC
from . import geom, ref


def rep(g: np.ndarray, name: str) -> np.ndarray:
    """Representation by name.  'lcn:sigma:c', 'dog:sigma', 'grad:sigma', 'raw', 'lcng:sigma:c' (global contrast stretch first)."""
    g = g.astype(np.float32)
    kind, *a = name.split(":")
    if kind == "lcn":
        sg, c = float(a[0]), float(a[1])
        return represent(g, Params(repr="lcn", sigma=sg, lcn_c=c))
    if kind == "dog":
        sg = float(a[0])
        return (g - cv2.GaussianBlur(g, (0, 0), sg, borderType=cv2.BORDER_REFLECT)) / 20.0
    if kind == "grad":
        sg = float(a[0])
        b = cv2.GaussianBlur(g, (0, 0), sg)
        gx, gy = cv2.Sobel(b, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(b, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx * gx + gy * gy)
        return (mag - cv2.GaussianBlur(mag, (0, 0), 10.0, borderType=cv2.BORDER_REFLECT)) / 20.0
    if kind == "raw":      # plain tonal structure: gray minus a wide local mean (so CCORR is zero-mean)
        return (g - cv2.GaussianBlur(g, (0, 0), 16.0, borderType=cv2.BORDER_REFLECT)) / 40.0
    if kind == "bl":       # blurred gray, wide local mean removed (coarse structure only)
        sg = float(a[0])
        b = cv2.GaussianBlur(g, (0, 0), sg)
        return (b - cv2.GaussianBlur(b, (0, 0), 16.0, borderType=cv2.BORDER_REFLECT)) / 40.0
    if kind == "lcnb":     # LCN of a blurred gray: lcnb:blur:sigma:c
        b = cv2.GaussianBlur(g, (0, 0), float(a[0]))
        return represent(b, Params(repr="lcn", sigma=float(a[1]), lcn_c=float(a[2])))
    if kind == "clahe":
        cl = cv2.createCLAHE(clipLimit=4.0, tileGridSize=(max(2, g.shape[1] // 24), max(2, g.shape[0] // 24)))
        c = cl.apply(np.clip(g, 0, 255).astype(np.uint8)).astype(np.float32)
        return (c - cv2.GaussianBlur(c, (0, 0), 16.0, borderType=cv2.BORDER_REFLECT)) / 48.0
    raise ValueError(name)


def crop_minimap(frame, cx=geom.CX, cy=geom.CY, r=geom.R_RING):
    D = 2 * int(np.ceil(r))
    return cv2.getRectSubPix(frame, (D, D), (cx - 0.5, cy - 0.5))


def tmpl(frame, z, s, repname, r_in=0.15, r_out=0.88, icons=True, crop=None, nmask=False):
    """Template (centred rep * mask) for level z at minimap scale s (minimap px per z0 px)."""
    crop = crop_minimap(frame) if crop is None else crop
    D = crop.shape[0]
    R = D / 2.0
    ft = (2.0 ** z) / s
    T = max(8, int(round(D * ft / 2.0)) * 2)
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY).astype(np.float32)
    valid = np.ones(gray.shape, np.uint8)
    if icons:
        valid = (1 - icon_mask(crop, R, Params())).astype(np.uint8)
    if nmask:   # "N" glyph: frame x 199..221, y 36..58 -> crop coords (crop origin = (CX-R, CY-R))
        ox, oy = geom.CX - D / 2.0, geom.CY - D / 2.0
        valid[int(36 - oy):int(58 - oy) + 1, int(199 - ox):int(221 - ox) + 1] = 0
    interp = cv2.INTER_AREA if T < D else cv2.INTER_CUBIC
    g_s = cv2.resize(fill_masked(gray, valid), (T, T), interpolation=interp)
    v_s = cv2.resize(valid.astype(np.float32), (T, T), interpolation=cv2.INTER_AREA)
    fe = T / D
    m = circle_mask(T, r_in * R * fe, r_out * R * fe).astype(np.float32) * (v_s > 0.9)
    r = rep(g_s, repname) * m
    return (r * ENC).astype(np.float32), m.astype(np.float32), T


def ref_rep(z, box, repname):
    img, off = ref.region(z, *box)
    r = rep(img, repname)
    return (r * ENC).astype(np.float32), off


ZERO_MEAN = ("raw", "dog", "bl", "grad", "lab")


def ncc(refimg, tm, mask, mode=cv2.TM_CCORR_NORMED):
    res = cv2.matchTemplate(refimg, tm, mode, mask=mask)
    np.nan_to_num(res, copy=False, nan=0.0, posinf=0.0, neginf=0.0)
    return res


def peak(res, nms):
    iy, ix = np.unravel_index(int(res.argmax()), res.shape)
    sc = float(res[iy, ix])
    y0, y1, x0, x1 = max(0, iy - nms), iy + nms + 1, max(0, ix - nms), ix + nms + 1
    sv = res[y0:y1, x0:x1].copy()
    res[y0:y1, x0:x1] = -1
    sec = float(res.max())
    res[y0:y1, x0:x1] = sv
    return sc, sec, ix, iy


def sweep(frame, box, scales, repname, z=-3, **kw):
    """Returns list of dict(s, score, second, x_pos, y_pos)."""
    R, off = ref_rep(z, box, repname)
    out = []
    for s in scales:
        tm, mask, T = tmpl(frame, z, s, repname, **kw)
        if T >= min(R.shape) - 2:
            out.append(dict(s=s, score=0, second=0, x=0, y=0, T=T)); continue
        res = ncc(R, tm, mask)
        sc, sec, ix, iy = peak(res, max(3, T // 6))
        x, y = ref.uv_to_pos(z, off[0] + ix + T / 2.0, off[1] + iy + T / 2.0)
        out.append(dict(s=float(s), score=sc, second=sec, x=x, y=y, T=T))
    return out


def local_scale_refine(frame, x0, y0, s0, repname="lcn:1.2:6", z=None, span=0.25, n=21, rad=30.0, **kw):
    """Fine (s, position) search around a hypothesis: for each scale a local masked NCC at level z (position +-rad z0 px).
    Returns best dict and the whole table."""
    if z is None:
        z = int(np.clip(np.floor(np.log2(max(s0, 1e-3)) + 1e-6), -3, 0))
    f = 2.0 ** z
    scales = s0 * np.exp(np.linspace(-span, span, n))
    # reference window big enough for the largest template + search radius
    Tmax = int(268 * f / scales.min()) + 8
    half_z0 = (Tmax / 2 + rad * f + 8) / f
    box = (x0 - half_z0, y0 - half_z0, x0 + half_z0, y0 + half_z0)
    R, off = ref_rep(z, box, repname)
    cu, cv_ = ref.pos_to_uv(z, x0, y0)
    table = []
    for s in scales:
        tm, mask, T = tmpl(frame, z, s, repname, **kw)
        ux, uy = int(round(cu - off[0] - T / 2 - rad * f)), int(round(cv_ - off[1] - T / 2 - rad * f))
        n_ = T + 2 * int(rad * f) + 1
        xa, ya = max(0, ux), max(0, uy)
        xb, yb = min(R.shape[1], ux + n_), min(R.shape[0], uy + n_)
        if xb - xa < T or yb - ya < T:
            table.append(dict(s=float(s), score=0, second=0, x=x0, y=y0)); continue
        res = ncc(np.ascontiguousarray(R[ya:yb, xa:xb]), tm, mask)
        sc, sec, ix, iy = peak(res, max(3, T // 6))
        # sub-pixel
        fx, fy = float(ix), float(iy)
        if 0 < ix < res.shape[1] - 1:
            a, b, c = res[iy, ix - 1], res[iy, ix], res[iy, ix + 1]
            d = a - 2 * b + c
            fx += 0.0 if abs(d) < 1e-9 else float(np.clip(0.5 * (a - c) / d, -1, 1))
        if 0 < iy < res.shape[0] - 1:
            a, b, c = res[iy - 1, ix], res[iy, ix], res[iy + 1, ix]
            d = a - 2 * b + c
            fy += 0.0 if abs(d) < 1e-9 else float(np.clip(0.5 * (a - c) / d, -1, 1))
        x, y = ref.uv_to_pos(z, off[0] + xa + fx + T / 2.0, off[1] + ya + fy + T / 2.0)
        table.append(dict(s=float(s), score=sc, second=sec, x=x, y=y))
    k = int(np.argmax([t["score"] for t in table]))
    best = dict(table[k])
    if 0 < k < len(table) - 1:
        a, b, c = table[k - 1]["score"], table[k]["score"], table[k + 1]["score"]
        d = a - 2 * b + c
        if abs(d) > 1e-9:
            ls = np.log([t["s"] for t in table])
            best["s"] = float(np.exp(ls[k] + np.clip(0.5 * (a - c) / d, -1, 1) * (ls[1] - ls[0])))
    return best, table


def sweep_auto(frame, box, scales, repname, **kw):
    """Like sweep() but picks the working level per scale (finest level that still downsamples, z in -3..0)."""
    out = []
    cache = {}
    for s in scales:
        z = int(np.clip(np.floor(np.log2(max(s, 1e-3)) + 1e-6), -3, 0))
        if z not in cache:
            cache[z] = ref_rep(z, box, repname)
        R, off = cache[z]
        tm, mask, T = tmpl(frame, z, s, repname, **kw)
        if T >= min(R.shape) - 2:
            out.append(dict(s=float(s), score=0, second=0, x=0, y=0, T=T, z=z)); continue
        res = ncc(R, tm, mask)
        sc, sec, ix, iy = peak(res, max(3, T // 6))
        x, y = ref.uv_to_pos(z, off[0] + ix + T / 2.0, off[1] + iy + T / 2.0)
        out.append(dict(s=float(s), score=sc, second=sec, x=x, y=y, T=T, z=z))
    return out
