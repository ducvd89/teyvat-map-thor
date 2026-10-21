#!/usr/bin/env python3
"""Verify the coordinate transform: stitch a zoom level of a map and plot points.

Needs Pillow:  python3 -m venv tools/.venv && tools/.venv/bin/pip install pillow
Usage:  python3 tools/render_check.py [--pack data/pack] [--map 2] [--zoom -3]
                                      [--labels 3,2] [--out data/render_check_map2.png]
"""
import argparse
import json
import math
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("render_check.py needs Pillow, which is not installed for this interpreter.\n"
             "Install it in a local venv (no sudo needed):\n"
             "  python3 -m venv tools/.venv && tools/.venv/bin/pip install pillow\n"
             "  tools/.venv/bin/python tools/render_check.py")

COLORS = [(255, 0, 0), (0, 255, 255), (255, 255, 0), (255, 0, 255), (0, 255, 0)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", default="data/pack")
    ap.add_argument("--map", type=int, default=2)
    ap.add_argument("--zoom", type=int, default=-3)
    ap.add_argument("--labels", default="3,2", help="label ids to plot (3=Teleport Waypoint, 2=Statue of The Seven)")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    pack = Path(a.pack)
    out = Path(a.out or f"data/render_check_map{a.map}.png")

    info = json.load(open(pack / "maps" / str(a.map) / "info.json"))["info"]["detail_v2"]
    pts = json.load(open(pack / "maps" / str(a.map) / "points.json"))["point_list"]
    w, h = info["total_size"]
    ox, oy = info["origin"]
    ver = info["map_version"]
    z = a.zoom
    s = 2.0 ** z
    nx, ny = math.ceil(w * s / 256), math.ceil(h * s / 256)
    img = Image.new("RGB", (nx * 256, ny * 256), (20, 20, 30))
    missing = 0
    for x in range(nx):
        for y in range(ny):
            p = pack / "tiles" / str(a.map) / ver / str(z) / f"{x}_{y}.webp"
            try:
                with Image.open(p) as t:
                    img.paste(t.convert("RGB"), (x * 256, y * 256))
            except (OSError, FileNotFoundError):
                missing += 1
    print(f"stitched {nx}x{ny} tiles ({missing} missing/undecodable) -> {img.size}")

    d = ImageDraw.Draw(img)
    labels = [int(v) for v in a.labels.split(",")]
    for i, lid in enumerate(labels):
        col = COLORS[i % len(COLORS)]
        sel = [p for p in pts if p["label_id"] == lid]
        r = 4
        for p in sel:
            px = (p["x_pos"] + ox) * s
            py = (p["y_pos"] + oy) * s
            d.ellipse([px - r, py - r, px + r, py + r], outline=col, width=2)
        print(f"label {lid}: {len(sel)} points plotted ({col})")
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print("saved", out)


if __name__ == "__main__":
    main()
