"""Map layers (caves / underground floors): zone index + floor reference images (PLAN.md 6.2 "Map layers").

Data: ``data/pack/maps/{id}/point_groups.json`` -> groups -> floors; every floor has an overlay image
(``data/pack/overlays/{floor_id}.png``, RGBA) and bounds ``l_x,l_y,r_x,r_y`` in x_pos/y_pos space.
The overlay is stretched onto its bounds (image px = bounds / (width, height), which is ~1 z0 px per image px
for most floors and 2 for some hi-res ones; verified visually, see data/cv/overlay_check.png).

Reference images for matching are built like the surface reference: a gray patch (surface mosaic as filler, the
floor composited over the dark underground background inside its bounds) -> same ``represent`` + ``encode``.
Cached lazily as ``data/cv/layers/floor_{id}_z{z}_{tag}.npy``.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np

from .mapdata import CV_DIR, PACK, Level, MapInfo, level_geom, load_mosaic
from .preprocess import Params, encode, represent, tag

DARK_GRAY = 20            # underground background (outside / transparent part of the overlay), gray level
DARK_BGR = (24, 20, 16)
MIN_SIDE = 40.0           # floors smaller than this (z0 px) are ignored (cannot hold a minimap)


@dataclass
class Floor:
    group_id: int
    floor_id: int
    name: str
    l: float                # bounds in z0 px
    t: float
    r: float
    b: float
    path: Path
    _rgba: np.ndarray | None = field(default=None, repr=False)

    def contains(self, px0, py0, margin: float = 0.0) -> bool:
        return self.l - margin <= px0 <= self.r + margin and self.t - margin <= py0 <= self.b + margin

    def rgba(self) -> np.ndarray:
        """Overlay (h,w,4) uint8 BGRA at native resolution."""
        if self._rgba is None:
            from PIL import Image
            im = Image.open(self.path).convert("RGBA")
            a = np.asarray(im)
            self._rgba = cv2.cvtColor(a, cv2.COLOR_RGBA2BGRA)
        return self._rgba

    def composite_z0(self) -> tuple[np.ndarray, np.ndarray]:
        """(BGR over the dark background, alpha 0..1) both resized to the bounds at z0 resolution."""
        w, h = max(2, int(round(self.r - self.l))), max(2, int(round(self.b - self.t)))
        a = self.rgba()
        interp = cv2.INTER_AREA if a.shape[1] > w else cv2.INTER_LINEAR
        a = cv2.resize(a, (w, h), interpolation=interp)
        al = a[..., 3:4].astype(np.float32) / 255.0
        bgr = a[..., :3].astype(np.float32) * al + np.array(DARK_BGR, np.float32) * (1 - al)
        return bgr.astype(np.uint8), al[..., 0]


class LayerIndex:
    """All floors of a map + rectangle lookup (uniform grid)."""
    CELL = 256.0

    def __init__(self, info: MapInfo, pack: Path = PACK):
        self.info = info
        gj = json.loads((Path(pack) / "maps" / str(info.map_id) / "point_groups.json").read_text())["list"]
        ox, oy = info.origin
        self.floors: list[Floor] = []
        self.by_id: dict[int, Floor] = {}
        self.groups: dict[int, list[Floor]] = {}
        for g in gj:
            for f in g.get("floors", []):
                o = f.get("overlay") or {}
                fid = f.get("id", 0)
                p = Path(pack) / "overlays" / f"{fid}.png"
                if not fid or not p.exists() or "l_x" not in o:
                    continue
                fl = Floor(g["id"], fid, f.get("floor_name", ""), o["l_x"] + ox, o["l_y"] + oy, o["r_x"] + ox,
                           o["r_y"] + oy, p)
                if min(fl.r - fl.l, fl.b - fl.t) < MIN_SIDE:
                    continue
                self.floors.append(fl)
                self.by_id[fid] = fl
                self.groups.setdefault(g["id"], []).append(fl)
        self._grid: dict = {}
        for fl in self.floors:
            for i in range(int(fl.l // self.CELL), int(fl.r // self.CELL) + 1):
                for j in range(int(fl.t // self.CELL), int(fl.b // self.CELL) + 1):
                    self._grid.setdefault((i, j), []).append(fl)
        # zones = union rectangle per group (what "inside a layer zone" means)
        self.zone_rect = {gid: (min(f.l for f in fs), min(f.t for f in fs), max(f.r for f in fs), max(f.b for f in fs))
                          for gid, fs in self.groups.items()}

    def floors_at(self, px0, py0, margin: float = 0.0) -> list[Floor]:
        """Floors whose bounds contain the point (the layer-check candidates)."""
        c = self._grid.get((int(px0 // self.CELL), int(py0 // self.CELL)), ())
        return [f for f in c if f.contains(px0, py0, margin)]

    def in_zone(self, px0, py0) -> bool:
        return any(True for f in self.floors_at(px0, py0))


class FloorRefs:
    """Lazily built prepared reference patches per (floor, level)."""

    def __init__(self, info: MapInfo, params: Params = Params(), pad_z0: float = 220.0, cache: bool = True):
        self.info, self.p, self.pad, self.cache = info, params, pad_z0, cache
        self._mem: dict = {}

    def _path(self, fl: Floor, z: int) -> Path:
        return CV_DIR / "layers" / f"floor_{fl.floor_id}_z{z}_{tag(self.p)}.npy"

    def geom(self, fl: Floor, z: int) -> Level:
        f = 2.0 ** z
        ox, oy = int(np.floor((fl.l - self.pad) * f)), int(np.floor((fl.t - self.pad) * f))
        ex, ey = int(np.ceil((fl.r + self.pad) * f)), int(np.ceil((fl.b + self.pad) * f))
        return Level(z, ox, oy, ex - ox, ey - oy)

    def gray_patch(self, fl: Floor, z: int, lv: Level) -> np.ndarray:
        """Surface gray as filler, floor composite (over the dark background) inside its bounds."""
        src = load_mosaic(self.info, z, color=False)
        sl = level_geom(self.info, z)          # mosaic offset
        out = np.full((lv.h, lv.w), 22, np.float32)
        x0, y0 = lv.ox - sl.ox, lv.oy - sl.oy
        xa, ya, xb, yb = max(0, x0), max(0, y0), min(src.shape[1], x0 + lv.w), min(src.shape[0], y0 + lv.h)
        if xb > xa and yb > ya:
            out[ya - y0:yb - y0, xa - x0:xb - x0] = np.asarray(src[ya:yb, xa:xb], np.float32)
        f = 2.0 ** z
        bgr, _ = fl.composite_z0()
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        w, h = max(2, int(round((fl.r - fl.l) * f))), max(2, int(round((fl.b - fl.t) * f)))
        gz = cv2.resize(gray, (w, h), interpolation=cv2.INTER_AREA if f < 1 else cv2.INTER_LINEAR)
        u0, v0 = int(round(fl.l * f)) - lv.ox, int(round(fl.t * f)) - lv.oy
        ua, va, ub, vb = max(0, u0), max(0, v0), min(lv.w, u0 + w), min(lv.h, v0 + h)
        out[va:vb, ua:ub] = gz[va - v0:vb - v0, ua - u0:ub - u0]
        return out

    def get(self, fl: Floor, z: int):
        """(prepared uint8 patch, Level) for local_match(ref=...)."""
        key = (fl.floor_id, z)
        v = self._mem.get(key)
        if v is None:
            lv = self.geom(fl, z)
            p = self._path(fl, z)
            if self.cache and p.exists():
                P = np.load(p)
            else:
                P = encode(represent(self.gray_patch(fl, z, lv), self.p))
                if self.cache:
                    p.parent.mkdir(parents=True, exist_ok=True)
                    np.save(p, P)
            v = self._mem[key] = (P, lv)
        return v
