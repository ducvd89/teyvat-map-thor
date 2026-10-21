"""Map pack access and cached grayscale / colour mosaics.

Coordinate conventions (all "z0 px" values are continuous, pixel i covers [i, i+1)):
    px0 = x_pos + origin[0],  py0 = y_pos + origin[1]
    level-z pixel = z0 pixel * 2**z   (z in -3..0)
A mosaic at level z covers the land bounding box and stores its integer offset
(ox, oy) in level-z pixels: level_px = z0_px * 2**z - (ox, oy).
"""
from __future__ import annotations

import json
import os
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "data" / "pack"
CV_DIR = ROOT / "data" / "cv"
TILE = 256
SEA_BGR = (31, 23, 16)
SEA_GRAY = 22


@dataclass(frozen=True)
class MapInfo:
    map_id: int
    version: str
    total_size: tuple
    padding: tuple
    origin: tuple
    min_zoom: int
    max_zoom: int
    original_size: tuple

    def land_bbox0(self):
        """Land bounding box (x0, y0, x1, y1) in zoom-0 pixels."""
        x0, y0 = self.padding
        return x0, y0, x0 + self.original_size[0], y0 + self.original_size[1]

    def to_pos(self, px0, py0):
        return px0 - self.origin[0], py0 - self.origin[1]

    def to_px0(self, x_pos, y_pos):
        return x_pos + self.origin[0], y_pos + self.origin[1]


def load_info(map_id: int = 2, pack: Path = PACK) -> MapInfo:
    d = json.loads((Path(pack) / "maps" / str(map_id) / "info.json").read_text())
    d = d.get("info", d)
    d = d.get("detail_v2", d)
    if isinstance(d, str):
        d = json.loads(d)
    return MapInfo(
        map_id, d["map_version"], tuple(d["total_size"]), tuple(d["padding"]),
        tuple(d["origin"]), d["min_zoom"], d["max_zoom"],
        tuple(d.get("original_map_size") or d["total_size"]),
    )


@dataclass(frozen=True)
class Level:
    z: int
    ox: int
    oy: int
    w: int
    h: int

    @property
    def f(self):
        return 2.0 ** self.z

    def from_z0(self, px0, py0):
        return px0 * self.f - self.ox, py0 * self.f - self.oy

    def to_z0(self, u, v):
        return (u + self.ox) / self.f, (v + self.oy) / self.f


def level_geom(info: MapInfo, z: int) -> Level:
    f = 2.0 ** z
    x0, y0, x1, y1 = info.land_bbox0()
    ox, oy = int(np.floor(x0 * f)), int(np.floor(y0 * f))
    ex, ey = int(np.ceil(x1 * f)), int(np.ceil(y1 * f))
    return Level(z, ox, oy, ex - ox, ey - oy)


def _tile_path(info, z, tx, ty, pack=PACK):
    return Path(pack) / "tiles" / str(info.map_id) / info.version / str(z) / f"{tx}_{ty}.webp"


def tiles_present(info: MapInfo, z: int, pack=PACK) -> int:
    d = Path(pack) / "tiles" / str(info.map_id) / info.version / str(z)
    return sum(1 for _ in d.iterdir()) if d.exists() else 0


def tiles_expected(info: MapInfo, z: int) -> int:
    f = 2.0 ** z
    return int(np.ceil(info.total_size[0] * f / TILE) * np.ceil(info.total_size[1] * f / TILE))


def _mosaic_path(info, z, kind):
    return CV_DIR / f"mosaic_{info.map_id}_{info.version[:8]}_z{z}_{kind}.npy"


def build_mosaic(info: MapInfo, z: int, color: bool = False, force: bool = False,
                 workers: int = 16, pack=PACK) -> Path:
    """Stitch the land bbox at level z into a .npy (uint8 gray HxW or BGR HxWx3)."""
    kind = "bgr" if color else "gray"
    out = _mosaic_path(info, z, kind)
    CV_DIR.mkdir(parents=True, exist_ok=True)
    if out.exists() and not force:
        return out
    lv = level_geom(info, z)
    shape = (lv.h, lv.w, 3) if color else (lv.h, lv.w)
    tmp = out.with_suffix(f".{os.getpid()}.tmp.npy")
    mm = np.lib.format.open_memmap(tmp, mode="w+", dtype=np.uint8, shape=shape)
    mm[...] = SEA_BGR if color else SEA_GRAY  # blank/missing tiles = sea colour
    tx0, ty0 = lv.ox // TILE, lv.oy // TILE
    tx1, ty1 = (lv.ox + lv.w - 1) // TILE, (lv.oy + lv.h - 1) // TILE
    flag = cv2.IMREAD_COLOR if color else cv2.IMREAD_GRAYSCALE

    def work(ty):
        for tx in range(tx0, tx1 + 1):
            p = _tile_path(info, z, tx, ty, pack)
            if not p.exists() or p.stat().st_size < 100:
                continue  # blank sea tile or missing -> sea colour
            img = cv2.imread(str(p), flag)
            if img is None:
                continue
            # destination window in mosaic coordinates
            gx, gy = tx * TILE - lv.ox, ty * TILE - lv.oy
            sx0, sy0 = max(0, -gx), max(0, -gy)
            dx0, dy0 = max(0, gx), max(0, gy)
            wv = min(TILE - sx0, lv.w - dx0)
            hv = min(TILE - sy0, lv.h - dy0)
            if wv > 0 and hv > 0:
                mm[dy0:dy0 + hv, dx0:dx0 + wv] = img[sy0:sy0 + hv, sx0:sx0 + wv]

    with ThreadPoolExecutor(workers) as ex:
        list(ex.map(work, range(ty0, ty1 + 1)))
    mm.flush()
    del mm
    os.replace(tmp, out)
    return out


def load_mosaic(info: MapInfo, z: int, color: bool = False, mmap: bool = True,
                build: bool = True) -> np.ndarray:
    p = _mosaic_path(info, z, "bgr" if color else "gray")
    if not p.exists():
        if not build:
            raise FileNotFoundError(p)
        build_mosaic(info, z, color)
    return np.load(p, mmap_mode="r" if mmap else None)


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description="Build map mosaics into data/cv/")
    ap.add_argument("--map", type=int, default=2)
    ap.add_argument("--z", type=int, nargs="+", default=[-3, -2, -1, 0])
    ap.add_argument("--color", action="store_true")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    inf = load_info(a.map)
    for z in a.z:
        have, exp = tiles_present(inf, z), tiles_expected(inf, z)
        print(f"z={z}: tiles {have}/{exp}", flush=True)
        if have < exp:
            print("  (incomplete - mosaic will have holes)")
        print(" ->", build_mosaic(inf, z, a.color, a.force))
