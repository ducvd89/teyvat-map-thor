#!/usr/bin/env python
"""Analysis of the first real Thor frames (tools/dataset/raw, 450 x 1920x1080, one every 2 s).

Run from the repo root:
  tools/.venv/bin/python tools/cv/real_analysis.py --all            # everything (about 5 min)
  tools/.venv/bin/python tools/cv/real_analysis.py --geometry --screen
  tools/.venv/bin/python tools/cv/real_analysis.py --match --labels --track --pins

Sections (helpers live in tools/cv/real/):
  --geometry  minimap ring circle fit (static HUD median + grid search), overlay image        -> data/cv/real/geometry.json
  --screen    loading screen / ring / black / flat statistics for every frame                  -> data/cv/real/screen_stats.json
  --match     blind scale sweeps, fine scale fits, SIFT cross-check, scale curves, side-by-side -> data/cv/real/match_results.json
  --labels    carried local NCC tracking at the per-region scale -> tools/dataset/labels.json
  --track     the unmodified prototype Tracker on the sequence (variants)                      -> data/cv/real/track_results.json
  --pins      big-map pin detection vs HoYoLAB points + minimap icon constellation (needs the lead's bigmap captures)
The narrative with all numbers is data/cv/real_report.md.  Nothing here modifies the other tools/cv/*.py files.
"""
from __future__ import annotations

import argparse
import json
import pickle
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import cv2
import numpy as np

from tools.cv import screen
from tools.cv.preprocess import Calib, crop_minimap
from tools.cv.real import geom, geometry as G, labels as L, scan, viz
from tools.cv.real import track_eval as TE
from tools.cv.real.data import Frames, OUT, ts

SOFT = {"S_SNOW": L.S_SNOW, "S_TOWN": L.S_TOWN}


def save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(json.dumps(obj, indent=1, default=float))


# ------------------------------------------------------------------------------------------------ geometry
def sec_geometry(F: Frames):
    def score_map(hp, cxs, cys, rs, nang=720, off=3.5):
        th = np.linspace(0, 2 * np.pi, nang, endpoint=False)
        ct, st = np.cos(th), np.sin(th)
        out = np.zeros((len(cys), len(cxs), len(rs)))
        for a, cy in enumerate(cys):
            for b, cx in enumerate(cxs):
                for c, r in enumerate(rs):
                    def samp(rr):
                        X = (cx + rr * ct).astype(np.float32).reshape(1, -1)
                        Y = (cy + rr * st).astype(np.float32).reshape(1, -1)
                        return cv2.remap(hp, X, Y, cv2.INTER_LINEAR)[0]
                    v = samp(r) - 0.5 * (samp(r - off) + samp(r + off))
                    out[a, b, c] = np.mean(np.clip(v, -6, 6))
        return out
    res = {}
    for k, ix in {"indoor": range(0, 35), "tundra_a": range(51, 200, 2), "tundra_b": range(200, 330, 2), "night": range(330, 450, 2)}.items():
        hp = G.hp_stack_median([F.tl[i] for i in ix], sigma=1.5)
        cxs, cys, rs = np.arange(208.5, 211.6, 0.25), np.arange(154, 157.1, 0.25), np.arange(132, 135.1, 0.25)
        sm = score_map(hp, cxs, cys, rs)
        a, b, c = np.unravel_index(sm.argmax(), sm.shape)
        res[k] = dict(cx=float(cxs[b]), cy=float(cys[a]), r=float(rs[c]), score=float(sm.max()))
    arr = np.array([[v["cx"], v["cy"], v["r"]] for v in res.values()])
    res["mean"] = dict(cx_idx=float(arr[:, 0].mean()), cy_idx=float(arr[:, 1].mean()), r=float(arr[:, 2].mean()),
                       std=arr.std(0).tolist(), note="pixel-INDEX convention; Calib (continuous) = +0.5")
    res["recommended"] = dict(Calib=dict(cx=geom.CX, cy=geom.CY, r=geom.R_RING), r_in=0.18, r_out=0.80,
                              N_rect_frame_xy=[199, 36, 221, 58], proto=dict(r_in=0.15, r_out=0.88))
    save("geometry.json", res)
    print("geometry:", res["mean"])


# ------------------------------------------------------------------------------------------------ screen tests
def sec_screen(F: Frames):
    calib = Calib(cx=geom.CX, cy=geom.CY, r=geom.R_RING)
    screen.fit_ring_ref(F.tl[60], calib)
    rows = []
    for i in range(len(F)):
        med, frac = screen.loading_stats(F.thumb[i])
        crop = crop_minimap(F.tl[i], calib)
        bm, bp, be = screen.black_stats(crop)
        rows.append(dict(i=i, t=ts(F.names[i]), load_med=med, load_frac=frac, is_loading=bool(screen.is_loading_screen(F.thumb[i])),
                         ring_proto=float(screen.ring_ncc(F.tl[i], calib)), ring_contrast=TE.ring_contrast(F.tl[i]),
                         black=(bm, bp, be), is_black=bool(screen.minimap_is_black(crop)), is_flat=bool(screen.minimap_is_flat(crop))))
    save("screen_stats.json", rows)
    ld = [r for r in rows if r["is_loading"]]
    print("loading frames:", [(r["i"], r["t"]) for r in ld], "| snow max median/frac:",
          max(r["load_med"] for r in rows[51:]), max(r["load_frac"] for r in rows[51:]))
    rp = np.array([r["ring_proto"] for r in rows]); rc = np.array([r["ring_contrast"] for r in rows])
    print("proto ring ncc (thr .70) HUD frames below thr: %d of %d" % ((np.delete(rp, [47, 48, 49, 50]) < 0.7).sum(), 446))
    print("new ring contrast: HUD min %.1f | bigmap/loading max %.2f" % (np.delete(rc, [47, 48, 49, 50]).min(), rc[47:51].max()))


# ------------------------------------------------------------------------------------------------ matching
def sec_match(F: Frames):
    box = (-10800, -8600, -5500, -4600)          # Snezhnaya anchors bounding box (prior)
    truth = {100: (L.S_SNOW, -8143.4, -6034.2), 440: (L.S_SNOW, -8223.4, -6098.1), 45: (L.S_TOWN, -8306.9, -5232.1), 0: (L.S_TOWN, -8418.2, -5344.0)}
    scales = np.exp(np.linspace(np.log(0.25), np.log(4.0), 57))
    res = {"blind_sweep": {}, "fine_scale": {}, "scale_curves": {}}
    for rn in ("lcn:1.2:6", "lcn:1.2:2", "dog:2", "raw"):
        res["blind_sweep"][rn] = {}
        for i, (s0, x0, y0) in truth.items():
            out = sorted(scan.sweep_auto(F.tl[i], box, scales, rn), key=lambda d: -d["score"])
            b = out[0]
            sec = max([o["score"] for o in out[1:] if np.hypot(o["x"] - b["x"], o["y"] - b["y"]) > 60 or abs(np.log(o["s"] / b["s"])) > 0.12] + [0])
            res["blind_sweep"][rn][i] = dict(s=b["s"], score=b["score"], second=sec,
                                             ok=bool(abs(np.log(b["s"] / s0)) < 0.07 and np.hypot(b["x"] - x0, b["y"] - y0) < 25))
        print("blind sweep", rn, res["blind_sweep"][rn])
    for k, (ix, pos, s0, z) in {"tundra_a_z-1": ([60, 100, 150, 200], (-8143.4, -6034.2), 0.75, -1), "tundra_b_z0": ([245, 300, 440], (-8223.4, -6098.1), 0.75, 0),
                                 "town_z0": ([0, 10, 20, 30], (-8418.2, -5344.0), 2.25, 0), "dock_z0": ([44, 45, 46], (-8306.9, -5232.1), 2.25, 0)}.items():
        v = [scan.local_scale_refine(F.tl[i], pos[0], pos[1], s0, z=z, span=0.08, n=33, rad=12)[0]["s"] for i in ix]
        res["fine_scale"][k] = dict(mean=float(np.mean(v)), std=float(np.std(v)), values=v)
    sc = np.exp(np.linspace(np.log(0.25), np.log(4.0), 90))
    curves = {}
    for nm, (i, pos) in {"tundra A f100": (100, (-8143.4, -6034.2)), "tundra B f440": (440, (-8223.4, -6098.1)), "town f0": (0, (-8418.2, -5344.0)), "dock f45": (45, (-8306.9, -5232.1))}.items():
        ys = []
        for s in sc:
            z = int(np.clip(np.floor(np.log2(s)), -3, 0))
            b, _ = scan.local_scale_refine(F.tl[i], pos[0], pos[1], s, span=1e-4, n=1, rad=10, z=z)
            ys.append(min(b["score"], 1.0))
        curves[nm] = (sc, np.array(ys))
    viz.plot_curves(curves, OUT / "scale_curves_known_pos.png", title="NCC vs minimap scale s at the TRUE position (lcn1.2/c6)", vlines=(0.752, 2.222))
    # sift cross-check
    from tools.cv.real import feat
    rows = pickle.load(open(OUT / "_label_rows.pkl", "rb")) if (OUT / "_label_rows.pkl").exists() else L.track_labels(F)
    sres = []
    for i in sorted(rows):
        if i % 6:
            continue
        r = rows[i]
        z = 0 if r["region"] == "town" else -1
        f = feat.match(F.tl[i], (r["x"] - 700, r["y"] - 700, r["x"] + 700, r["y"] + 700), z=z, kind="sift", nfeat=6000)
        sres.append(dict(i=i, region=r["region"], ok=bool(f["ok"]), inliers=f["inliers"], d=float(np.hypot(f["x"] - r["x"], f["y"] - r["y"])) if "x" in f else None, s=f.get("s")))
    res["sift_check"] = sres
    for i, t in ((100, "(a) tundra day"), (440, "(b) tundra night"), (45, "(c) dock 14:48:04"), (0, "(d) indoor train")):
        r = rows[i]
        cv2.imwrite(str(OUT / f"sbs_f{i}.png"), viz.side_by_side(F.tl[i], r["x"], r["y"], r["s"], t))
    save("match_results.json", res)
    print("fine scale:", {k: round(v["mean"], 4) for k, v in res["fine_scale"].items()})


# ------------------------------------------------------------------------------------------------ labels / tracking
def sec_labels(F: Frames):
    rows = L.track_labels(F)
    pickle.dump(rows, open(OUT / "_label_rows.pkl", "wb"))
    out = L.write_labels(rows, F, ROOT / "tools" / "dataset" / "labels.json")
    print("labels written:", len(out))


def sec_track(F: Frames):
    res = {}
    for name, var in {"proto_ring_region_scale": dict(ring="proto", scale="region"), "new_ring_region_scale": dict(ring="new", scale="region"),
                      "new_ring_fixed_s0.752": dict(ring="new", scale=0.752), "new_ring_fixed_s2.222": dict(ring="new", scale=2.222)}.items():
        t = time.time()
        rows, trk = TE.run(var)
        trk_rows = [r for r in rows if r["state"] == "TRACKING" and r["err"] is not None]
        err = np.array([r["err"] for r in trk_rows]) if trk_rows else np.zeros(1)
        res[name] = dict(by_state=trk.stats.by_state, n_tracking_labelled=len(trk_rows), err_median=float(np.median(err)), err_p90=float(np.percentile(err, 90)),
                         err_max=float(err.max()), wrong_accepted=sum(1 for r in rows if r["acc"] and r["err"] is not None and r["err"] > 25),
                         ms={m: float(np.mean([r["ms"] for r in rows if r["mode"] == m])) for m in ("local", "still", "full", "targets", "confirm") if any(r["mode"] == m for r in rows)},
                         episodes=trk.episodes, rows=rows)
        print(name, f"{time.time() - t:.0f}s", trk.stats.by_state)
    save("track_results.json", res)


# ------------------------------------------------------------------------------------------------ big map pins
def sec_pins(F: Frames):
    from tools.cv.real import bigmap, pins, constellation as C
    SC = Path("/tmp/claude-1000/-home-cid-Genshin-Interactive-Map/4ec8e6e2-35b0-4778-9467-ae361367505e/scratchpad/thor/bigmap")
    if not (SC / "bm1.png").exists():
        print("lead's bigmap captures not found; skipping")
        return
    rects = [(0, 0, 520, 420), (0, 420, 140, 700), (600, 0, 1920, 110), (0, 960, 1920, 1080), (1250, 860, 1920, 1080), (0, 960, 200, 1080)]
    P = pins.load_points()
    lab = np.array([p["label_id"] for p in P]); X = np.array([p["x_pos"] for p in P], float); Y = np.array([p["y_pos"] for p in P], float)
    m3 = lab == 3
    tm = cv2.imread(str(SC / "bm1.png"))[572:610, 1000:1032].copy()
    c0 = np.array([15.5, 24.0])
    out = {}
    for name in ("bm1", "bm4"):
        fr = cv2.imread(str(SC / (name + ".png")))
        r = bigmap.register(fr, (-10800, -8600, -5500, -4600), z=0, ui_mask_rects=rects)
        sx, sy = pins.project(r["A"], r["off"], X[m3], Y[m3])
        res = cv2.matchTemplate(fr, tm, cv2.TM_CCOEFF_NORMED)
        dets, rr = [], res.copy()
        while True:
            iy, ix = np.unravel_index(int(rr.argmax()), rr.shape)
            if rr[iy, ix] < 0.6:
                break
            dets.append((ix + c0[0], iy + c0[1])); rr[max(0, iy - 18):iy + 19, max(0, ix - 18):ix + 19] = -1
        dets = [d for d in dets if not (d[0] < 520 and d[1] < 420) and d[1] < 960 and not (d[0] > 1250 and d[1] > 860) and 20 < d[0] < 1900]
        resid = []
        for x, y in dets:
            d = np.hypot(sx - x, sy - y); j = int(np.argmin(d))
            if d[j] < 8:
                resid.append((sx[j] - x, sy[j] - y))
        out[name] = dict(inliers=r["inliers"], scale=r["scale"], centre_pos=list(map(float, r["centre_pos"])), pins=len(dets), matched=len(resid),
                         resid_mean=np.mean(resid, 0).tolist(), resid_std=np.std(resid, 0).tolist())
        print(name, out[name])
    tmm = F.tl[440][44:84, 135:163].copy()
    det = C.PinDetector(tmm)
    wps = C.load_waypoints((3,))
    pn = det.detect(F.tl[440])
    h = C.solve_pairs(pn, wps, s_range=(0.7, 0.8))
    out["constellation_B"] = dict(pins=[list(map(float, p)) for p in pn], hypotheses=len(h), top=[list(map(float, hh[:4])) for hh in h[:4]])
    save("pins_results.json", out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for k in ("all", "geometry", "screen", "match", "labels", "track", "pins"):
        ap.add_argument("--" + k, action="store_true")
    a = ap.parse_args()
    if not any(vars(a).values()):
        ap.print_help()
        return
    F = Frames()
    if a.all or a.geometry:
        sec_geometry(F)
    if a.all or a.screen:
        sec_screen(F)
    if a.all or a.labels:
        sec_labels(F)
    if a.all or a.match:
        sec_match(F)
    if a.all or a.track:
        sec_track(F)
    if a.all or a.pins:
        sec_pins(F)


if __name__ == "__main__":
    main()
