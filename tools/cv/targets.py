"""Teleport targets for RELOCATING (PLAN.md 6.2 "RELOCATING search order").

Every point whose label is a descendant of the top-level label "Waypoints" (id 1) in labels.json:
Teleport Waypoint, Domain, Statue of The Seven, meeting points, ...  All points are used (underground ones simply fail to match the surface).
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .mapdata import CV_DIR, PACK, MapInfo

KINDS = ("waypoint", "statue", "domain", "other")


def _collect(node, out, name_by_id):
    name_by_id[node["id"]] = node["name"]
    out.append(node["id"])
    for c in node.get("children") or []:
        _collect(c, out, name_by_id)


@dataclass
class Targets:
    xy: np.ndarray          # (N,2) z0 px
    kind: np.ndarray        # (N,) int index into KINDS

    def __len__(self):
        return len(self.xy)

    def of_kind(self, k: str):
        return np.nonzero(self.kind == KINDS.index(k))[0]

    def order(self, last_pos, first: str | None = None, near: float = 3000.0):
        """(priority indices, remaining indices).  first='domain': domains within `near` z0 px of last_pos (nearest
        first); first='statue': all statues, nearest first."""
        if first is None or last_pos is None:
            return np.zeros(0, int), np.arange(len(self))
        idx = self.of_kind(first)
        d = np.hypot(self.xy[idx, 0] - last_pos[0], self.xy[idx, 1] - last_pos[1])
        o = np.argsort(d)
        idx, d = idx[o], d[o]
        if first == "domain":
            idx = idx[d <= near]
        rest = np.setdiff1d(np.arange(len(self)), idx)
        return idx, rest


def load_targets(info: MapInfo, pack: Path = PACK, root_label: int = 1) -> Targets:
    cache = CV_DIR / f"targets_{info.map_id}_{info.version[:8]}.npz"
    if cache.exists():
        z = np.load(cache)
        return Targets(z["xy"], z["kind"])
    d = Path(pack) / "maps" / str(info.map_id)
    tree = json.loads((d / "labels.json").read_text())["tree"]
    ids, names = [], {}
    for n in tree:
        if n["id"] == root_label:
            _collect(n, ids, names)
    kind_of = {}
    for i in ids:
        nm = names[i].lower()
        kind_of[i] = KINDS.index("statue" if "statue" in nm else "domain" if nm.startswith("domain") else
                                 "waypoint" if "teleport" in nm else "other")
    pts = json.loads((d / "points.json").read_text())["point_list"]
    xy, kd = [], []
    ox, oy = info.origin
    for p in pts:
        if p["label_id"] in kind_of:
            xy.append((p["x_pos"] + ox, p["y_pos"] + oy))
            kd.append(kind_of[p["label_id"]])
    t = Targets(np.array(xy, np.float64), np.array(kd, np.int8))
    CV_DIR.mkdir(parents=True, exist_ok=True)
    np.savez(cache, xy=t.xy, kind=t.kind)
    return t
