"""Quick labelling of real screenshots.

1) Propose:   python -m tools.cv.label_helper [DIR] [--scale S] [--calib CX CY R]
      For each unlabelled frame in DIR (default tools/dataset/raw) run the global search and write
      tools/dataset/candidates/<name>.png  (minimap crop | top-3 map patches with scores)
      plus tools/dataset/candidates.json.
2) Accept:    python -m tools.cv.label_helper --accept NAME.png IDX      (IDX = 0,1,2 from the image)
3) Manual:    python -m tools.cv.label_helper --set NAME.png X_POS Y_POS (e.g. read off the big map)
      python -m tools.cv.label_helper --reject NAME.png                  (frame unusable: remove from candidates)
Labels are written to tools/dataset/labels.json: {"NAME.png": {"x_pos":..., "y_pos":..., "s":...}}
Run from the repository root (python -m needs the root on sys.path), or use tools/.venv/bin/python -m ...
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.cv import viz
from tools.cv.mapdata import CV_DIR, load_info
from tools.cv.match import Matcher
from tools.cv.preprocess import Calib, Params, crop_minimap

DS = ROOT / "tools" / "dataset"
LABELS = DS / "labels.json"
CANDS = DS / "candidates.json"
CAND_DIR = DS / "candidates"


def _load(p, default):
    return json.loads(p.read_text()) if p.exists() else default


def _save(p, d):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(d, indent=1, sort_keys=True))


def propose(args):
    info = load_info(args.map)
    calib = Calib()
    cj = CV_DIR / "calib.json"
    if cj.exists():
        calib = Calib(**json.loads(cj.read_text()))
    if args.calib:
        calib.cx, calib.cy, calib.r = args.calib
    if args.scale:
        calib.s = args.scale
    m = Matcher(info, Params(), calib, threads=8)
    labels, cands = _load(LABELS, {}), _load(CANDS, {})
    d = Path(args.dir or DS / "raw")
    CAND_DIR.mkdir(parents=True, exist_ok=True)
    for p in sorted(q for q in d.iterdir() if q.suffix.lower() in (".png", ".jpg")):
        if p.name in labels and not args.redo:
            continue
        fr = cv2.imread(str(p))
        if fr is None:
            continue
        res = m.global_match(fr, s=calib.s, topk=max(5, args.n))
        crop = crop_minimap(fr, calib)
        D = crop.shape[0]
        tiles = [viz.put_text(crop.copy(), p.name[:28])]
        entry = []
        for i, (px, py, sc) in enumerate(res.candidates[:args.n]):
            patch = viz.map_patch(info, px, py, calib.s, D)
            x, y = info.to_pos(px, py)
            entry.append(dict(idx=i, x_pos=round(x, 1), y_pos=round(y, 1), score=round(sc, 3)))
            tiles.append(viz.put_text(patch, f"#{i} {sc:.2f} ({x:.0f},{y:.0f})"))
        if not entry:
            print(p.name, "no candidates")
            continue
        cv2.imwrite(str(CAND_DIR / f"{p.stem}.png"), np.hstack(tiles))
        cands[p.name] = entry
        print(f"{p.name}: " + "  ".join(f"#{e['idx']} {e['score']:.2f}" for e in entry))
    _save(CANDS, cands)
    print(f"wrote {CANDS} and images in {CAND_DIR}; confirm with --accept NAME IDX")


def accept(name, idx, s=None):
    cands, labels = _load(CANDS, {}), _load(LABELS, {})
    e = cands[name][idx]
    labels[name] = dict(x_pos=e["x_pos"], y_pos=e["y_pos"])
    if s:
        labels[name]["s"] = s
    _save(LABELS, labels)
    print("label", name, labels[name])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir", nargs="?")
    ap.add_argument("--map", type=int, default=2)
    ap.add_argument("--scale", type=float)
    ap.add_argument("--calib", type=float, nargs=3)
    ap.add_argument("-n", type=int, default=3, help="candidates per frame")
    ap.add_argument("--redo", action="store_true", help="also re-propose already labelled frames")
    ap.add_argument("--accept", nargs=2, metavar=("NAME", "IDX"))
    ap.add_argument("--set", nargs=3, metavar=("NAME", "X_POS", "Y_POS"))
    ap.add_argument("--reject", metavar="NAME")
    a = ap.parse_args()
    if a.accept:
        accept(a.accept[0], int(a.accept[1]), a.scale)
    elif a.set:
        labels = _load(LABELS, {})
        labels[a.set[0]] = dict(x_pos=float(a.set[1]), y_pos=float(a.set[2]))
        _save(LABELS, labels)
        print("label", a.set[0], labels[a.set[0]])
    elif a.reject:
        c = _load(CANDS, {})
        c.pop(a.reject, None)
        _save(CANDS, c)
    else:
        propose(a)


if __name__ == "__main__":
    main()
