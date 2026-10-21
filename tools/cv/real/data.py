"""Loading / caching of the real Thor frames.  Only the top-left HUD corner is cached (never full frames,
the bottom-right corner carries the player UID)."""
from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[3]
RAW = ROOT / "tools" / "dataset" / "raw"
OUT = ROOT / "data" / "cv" / "real"
CACHE = OUT / "cache_topleft.npz"
CW, CH = 440, 340            # cached top-left region (x 0..440, y 0..340)


def frame_names():
    return sorted(p.name for p in RAW.glob("*.png"))


def ts(name: str) -> str:
    return f"{name[9:11]}:{name[11:13]}:{name[13:15]}"


def secs(name: str) -> int:
    return int(name[9:11]) * 3600 + int(name[11:13]) * 60 + int(name[13:15])


def _load(name):
    f = cv2.imread(str(RAW / name), cv2.IMREAD_COLOR)
    from ..screen import thumbnail
    th = thumbnail(f)
    # coarse 160x90 colour thumbnail (no UID legible) + gray stats for loading-screen analysis
    small = cv2.resize(f, (192, 108), interpolation=cv2.INTER_AREA)
    return f[:CH, :CW].copy(), th, small, f.shape


def build_cache(force=False):
    if CACHE.exists() and not force:
        return
    names = frame_names()
    with ThreadPoolExecutor(12) as ex:
        res = list(ex.map(_load, names))
    OUT.mkdir(parents=True, exist_ok=True)
    np.savez(CACHE, names=np.array(names), tl=np.stack([r[0] for r in res]),
             thumb=np.stack([r[1] for r in res]), small=np.stack([r[2] for r in res]))


class Frames:
    def __init__(self):
        build_cache()
        z = np.load(CACHE)
        self.names = [str(n) for n in z["names"]]
        self.tl = z["tl"]
        self.thumb = z["thumb"]
        self.small = z["small"]
        self.idx = {n: i for i, n in enumerate(self.names)}

    def __len__(self):
        return len(self.names)

    def full(self, name):
        return cv2.imread(str(RAW / name), cv2.IMREAD_COLOR)

    def get(self, i):
        if isinstance(i, str):
            i = self.idx[i]
        return self.tl[i]
