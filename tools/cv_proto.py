#!/usr/bin/env python
"""Minimap -> map position prototype (PLAN.md section 6 / Phase 0.4).

Examples
  tools/.venv/bin/python tools/cv_proto.py tools/dataset/raw/20260101-120000.png
  tools/.venv/bin/python tools/cv_proto.py tools/dataset/raw/ --mode track --debug
  tools/.venv/bin/python tools/cv_proto.py --synthetic 5 --debug          # no real data needed
  tools/.venv/bin/python tools/cv_proto.py tools/dataset/raw/ --calibrate  # find circle + scale, save data/cv/calib.json

Calibration (cx, cy, r, s) is read from data/cv/calib.json unless --calib/--scale are given.
Debug images go to data/cv/debug/.
"""
import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import cv2
import numpy as np

from tools.cv.mapdata import CV_DIR, load_info
from tools.cv.match import Matcher
from tools.cv.preprocess import Calib, Params, crop_minimap, find_minimap_circle, make_template
from tools.cv.track import TrackCfg, Tracker
from tools.cv.screen import fit_ring_ref, ring_ncc, thumbnail, loading_stats
from tools.cv.layers import FloorRefs, LayerIndex
from tools.cv.targets import load_targets
from tools.cv.heading import estimate_heading
from tools.cv import viz

CALIB_JSON = CV_DIR / "calib.json"
DEBUG_DIR = CV_DIR / "debug"


def load_calib(args) -> Calib:
    c = Calib()
    if CALIB_JSON.exists():
        c = Calib(**json.loads(CALIB_JSON.read_text()))
    if args.calib:
        c.cx, c.cy, c.r = args.calib
    if args.scale:
        c.s = args.scale
    return c


def list_images(paths):
    out = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            out += sorted(q for q in p.iterdir() if q.suffix.lower() in (".png", ".jpg", ".jpeg"))
        else:
            out.append(p)
    return out


def write_debug(name, frame, calib, m: Matcher, res, info, s):
    DEBUG_DIR.mkdir(parents=True, exist_ok=True)
    crop = crop_minimap(frame, calib)
    z = res.z
    tm = res.tmpl or make_template(frame, calib, z, m.p, s=s)
    D = 256
    crop_v = cv2.resize(crop, (D, D))
    tiles = [viz.put_text(crop_v, "crop"),
             viz.put_text(cv2.resize(viz.mask_img(tm), (D, D), interpolation=cv2.INTER_NEAREST), "mask"),
             viz.put_text(cv2.resize(viz.tmpl_img(tm), (D, D)), f"template z{z}")]
    if res.heat is not None:
        tiles.append(viz.put_text(viz.heat_img(res.heat, D), "heatmap"))
    if res.ok:
        patch = viz.map_patch(info, res.px0, res.py0, s, crop.shape[0])
        tiles.append(viz.put_text(cv2.resize(viz.circle_view(patch), (D, D)),
                                  f"map @ match {res.score:.2f}"))
        blend = cv2.addWeighted(viz.circle_view(crop), 0.5, viz.circle_view(patch), 0.5, 0)
        tiles.append(viz.put_text(cv2.resize(blend, (D, D)), "overlay"))
    cv2.imwrite(str(DEBUG_DIR / f"{Path(name).stem}_debug.png"), np.hstack(tiles))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", help="image files or folders (1920x1080 frames)")
    ap.add_argument("--map", type=int, default=2)
    ap.add_argument("--calib", type=float, nargs=3, metavar=("CX", "CY", "R"))
    ap.add_argument("--scale", type=float, help="minimap px per z0 px")
    ap.add_argument("--mode", choices=["global", "local", "track", "both"], default="both",
                    help="global: whole-map search; local needs --prior; track: sequential tracker; both: global then local timing")
    ap.add_argument("--prior", type=float, nargs=2, metavar=("X_POS", "Y_POS"))
    ap.add_argument("--repr", default="lcn", choices=["lcn", "clahe", "grad"])
    ap.add_argument("--sigma", type=float, default=1.2)
    ap.add_argument("--synthetic", type=int, default=0, help="generate N synthetic frames instead of reading files")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--dt", type=float, default=1.0, help="track mode: seconds between the given frames")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--calibrate", action="store_true", help="detect circle + estimate scale (needs --prior or searches globally)")
    ap.add_argument("--features", action="store_true", help="also run ORB+RANSAC fallback on the top candidates")
    a = ap.parse_args()

    info = load_info(a.map)
    calib = load_calib(a)
    params = Params(repr=a.repr, sigma=a.sigma)
    m = Matcher(info, params, calib, threads=a.threads)

    frames = []   # (name, frame, truth|None)
    if a.synthetic:
        from tools.cv.synth import make_sample
        rng = np.random.default_rng(a.seed)
        for i in range(a.synthetic):
            fr, gt = make_sample(info, rng, cx=calib.cx, cy=calib.cy, r=calib.r)
            frames.append((f"synth{i:03d}", fr, gt))
    else:
        labels = {}
        lp = ROOT / "tools" / "dataset" / "labels.json"
        if lp.exists():
            labels = json.loads(lp.read_text())
        for p in list_images(a.paths):
            fr = cv2.imread(str(p))
            if fr is None:
                print("cannot read", p)
                continue
            gt = labels.get(p.name)
            if gt:
                gt = dict(gt, px0=gt["x_pos"] + info.origin[0], py0=gt["y_pos"] + info.origin[1])
            frames.append((p.name, fr, gt))
    if not frames:
        ap.error("no input frames")

    if a.calibrate:
        scs = []
        circ = []
        for name, fr, gt in frames:
            c = find_minimap_circle(fr) if not a.synthetic else (calib.cx, calib.cy, calib.r)
            if c:
                circ.append(c)
        if circ:
            cc = np.median(np.array(circ), axis=0)
            calib.cx, calib.cy, calib.r = map(float, cc)
            print("circle (cx, cy, r) =", np.round(cc, 1))
        m.calib = calib
        for name, fr, gt in frames:
            hint = (gt["px0"], gt["py0"]) if gt else (None if a.prior is None else info.to_px0(*a.prior))
            t = time.perf_counter()
            s, res, _ = m.estimate_scale(fr, hint=hint, calib=calib)
            print(f"{name}: s={s:.3f} score={res.score:.2f} pos=({res.px0 - info.origin[0]:.0f},{res.py0 - info.origin[1]:.0f}) "
                  f"{time.perf_counter() - t:.1f}s" + (f"  true s={gt['s']:.3f}" if gt and "s" in gt else ""))
            if res.score > 0.5:
                scs.append(s)
        if scs:
            calib.s = float(np.median(scs))
            fit_ring_ref(frames[0][1], calib)       # border-ring reference for the ring_present test
            CV_DIR.mkdir(parents=True, exist_ok=True)
            CALIB_JSON.write_text(json.dumps(calib.__dict__, indent=1))
            print(f"saved {CALIB_JSON}: {calib}")
        return

    if a.mode == "track":
        if calib.ring_ref is None:
            fit_ring_ref(frames[0][1], calib)       # no calibration ring: use the first frame (must show the minimap)
            print("note: no ring reference in calib.json, fitted on the first frame")
        m.calib = calib
        trk = Tracker(m, calib, TrackCfg(), LayerIndex(info), FloorRefs(info, m.p), load_targets(info))
        trk.start(info.to_px0(*a.prior) if a.prior else None)
        t = 0.0
        for i, (name, fr, gt) in enumerate(frames):
            p = trk.update(fr, t)
            th_med, th_frac = loading_stats(thumbnail(fr))
            err = f" err={np.hypot(p.px0 - gt['px0'], p.py0 - gt['py0']):.1f}px" if gt and p.state == "TRACKING" else ""
            lay = f" floor={p.floor_id}" if p.floor_id else ""
            print(f"{name}: {p.state:10s} {p.mode:8s} x={p.x_pos:9.1f} y={p.y_pos:9.1f} hdg={p.heading if p.heading is None else round(p.heading)} "
                  f"score={p.score:.2f}{lay} ring={ring_ncc(fr, calib):.2f} thumb={th_med:.0f}/{th_frac:.2f} {p.ms:.0f}ms next={p.delay:.1f}s{err}")
            t += a.dt
        st = trk.stats
        print(f"checks={st.checks} full={st.full} target={st.target} local={st.local} layer={st.layer_checks} cheap={st.cheap} cpu={st.cpu_ms:.0f}ms")
        return

    for name, fr, gt in frames:
        out = [name]
        res = None
        # synthetic frames carry their own scale (stands in for a calibrated s); --scale overrides
        s = gt["s"] if (a.synthetic and gt and not a.scale) else calib.s
        if a.mode in ("global", "both"):
            res = m.global_match(fr, s=s)
            out.append(f"global: x={res.px0 - info.origin[0]:.1f} y={res.py0 - info.origin[1]:.1f} "
                       f"score={res.score:.2f} 2nd={res.second:.2f} conf={res.confidence:.2f} {res.ms:.0f}ms")
            if a.features and res.candidates:
                f = m.feature_match(fr, res.candidates[:3], s=s)
                out.append(f"orb: ok={f.ok} inliers={f.extra.get('inliers', 0)} {f.ms:.0f}ms")
        if a.mode in ("local", "both"):
            prior = info.to_px0(*a.prior) if a.prior else ((res.px0, res.py0) if res else None)
            if prior is None:
                out.append("local: needs --prior")
            else:
                prior = (prior[0] + 6, prior[1] - 5) if a.mode == "both" else prior
                lr = m.local_match(fr, prior, 60, s=s, keep_heat=a.debug)
                out.append(f"local: x={lr.px0 - info.origin[0]:.1f} y={lr.py0 - info.origin[1]:.1f} score={lr.score:.2f} {lr.ms:.0f}ms")
                res = lr if a.debug else res
        hd, hc = estimate_heading(crop_minimap(fr, calib))
        out.append(f"heading={None if hd is None else round(hd)}")
        if gt and res is not None:
            out.append(f"ERR={np.hypot(res.px0 - gt['px0'], res.py0 - gt['py0']):.1f}px")
        print("  ".join(out), flush=True)
        if a.debug and res is not None:
            write_debug(name, fr, calib, m, res, info, s)


if __name__ == "__main__":
    main()
