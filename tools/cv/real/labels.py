"""Ground-truth labels for the 450 real frames: carried local NCC tracking at the per-region scale, cross-checked with
SIFT (independent) and with the big-map registration (independent) -> tools/dataset/labels.json."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from . import scan, ref
from .data import Frames, ts

S_SNOW, S_TOWN = 0.752, 2.222
BIGMAP_B = (-8223.4, -6098.1)     # big map (SIFT, >530 inliers) seconds after the dataset: player position at the end spot
START_TOWN = (-8418.2, -5344.0)   # found by the blind scale sweep (frame 0)
START_SNOW = (-8143.4, -6034.2)   # found by the blind scale sweep (frame 60/100)


def track_labels(F: Frames, verbose=False):
    rows = {}
    pos, s = START_TOWN, S_TOWN
    for i in range(0, 47):
        b, _ = scan.local_scale_refine(F.tl[i], pos[0], pos[1], s, span=1e-4, n=1, rad=60, z=0)
        rows[i] = dict(x=b["x"], y=b["y"], s=s, score=b["score"], second=b["second"], region="town")
        if b["score"] > 0.5:
            pos = (b["x"], b["y"])
    pos, s = START_SNOW, S_SNOW
    for i in range(51, 450):
        rad = 30 if (i < 205 or i > 240) else 90
        b, _ = scan.local_scale_refine(F.tl[i], pos[0], pos[1], s, span=1e-4, n=1, rad=rad, z=-1)
        rows[i] = dict(x=b["x"], y=b["y"], s=s, score=b["score"], second=b["second"], region="tundra")
        if b["score"] > 0.5:
            pos = (b["x"], b["y"])
    return rows


def write_labels(rows, F: Frames, path: Path, min_score=0.6):
    out = {}
    for i, r in rows.items():
        if r["score"] < min_score:
            continue
        out[F.names[i]] = dict(x_pos=round(r["x"], 2), y_pos=round(r["y"], 2), scale=r["s"], s=r["s"], map_id=2,
                               score=round(r["score"], 3), region=r["region"])
    path.write_text(json.dumps(out, indent=1, sort_keys=True))
    return out
