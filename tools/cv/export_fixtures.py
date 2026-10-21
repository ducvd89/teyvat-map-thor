"""Export parity fixtures for the Android instrumented tests (app/src/androidTest/assets/cv).

    tools/.venv/bin/python -m tools.cv.export_fixtures [--out app/src/androidTest/assets/cv] [--singles 40]

Produces, from the Python synthetic generator and the Python reference tracker:
  calib.json                       default calibration (circle, scale, ring reference) of the singles
  singles/index.json + NN.webp     ~40 single minimap frames: ROI crop, ground truth, true / calibrated scale and the
                                   Python global / local / heading results (the Kotlin matcher must be as good)
  scenarios/<name>/index.json      short state-machine sequences: for every instant the Python tracker asked for a
                                   frame (ROI PNG + 64x36 thumbnail PNG), the expected state, the truth and the Python
                                   tracker's own state / position (the Kotlin tracker must follow the same states)

The app never sees the full 1920x1080 frame, only the ROI around the minimap circle (same rectangle as
`FrameExtractor.roiRect`) and a 64x36 thumbnail, so that is all that is stored (keeps the assets small).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.cv import screen                                   # noqa: E402
from tools.cv.heading import estimate_heading                 # noqa: E402
from tools.cv.layers import FloorRefs                         # noqa: E402
from tools.cv.mapdata import load_info                        # noqa: E402
from tools.cv.match import Matcher                            # noqa: E402
from tools.cv.preprocess import Calib, Params, crop_minimap   # noqa: E402
from tools.cv.synth import Scenario, Seg, _OPAQUE, floor_opaque, make_sample   # noqa: E402
from tools.cv.track import TrackCfg, Tracker                  # noqa: E402

MARGIN = 8
GRACE = 3.0


def roi_rect(c: Calib, w=1920, h=1080):
    """Same rectangle as FrameExtractor.roiRect (x, y, w, h)."""
    x0 = max(0, math.floor(c.cx - c.r - MARGIN))
    y0 = max(0, math.floor(c.cy - c.r - MARGIN))
    x1 = min(w, math.ceil(c.cx + c.r + MARGIN) + 1)
    y1 = min(h, math.ceil(c.cy + c.r + MARGIN) + 1)
    return x0, y0, max(1, x1 - x0), max(1, y1 - y0)


def png(path: Path, img):
    """Lossless image file: .webp for the (noisy) ROI crops (30 % smaller than PNG), .png for thumbnails."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.suffix == ".webp":
        cv2.imwrite(str(path), img, [cv2.IMWRITE_WEBP_QUALITY, 101])
    else:
        cv2.imwrite(str(path), img, [cv2.IMWRITE_PNG_COMPRESSION, 9])


def calib_json(c: Calib):
    return dict(cx=c.cx, cy=c.cy, r=c.r, s=c.s, ringRef=c.ring_ref, frameW=1920, frameH=1080)


def export_singles(info, out: Path, n: int, seed: int):
    rng = np.random.default_rng(seed)
    levels = ["clean", "mixed", "hard"]
    base = Calib()
    m = Matcher(info, Params(), Calib(), coarse_z=-2, threads=8)
    rows = []
    for i in range(n):
        fr, gt = make_sample(info, rng, level=levels[i % 3], min_edge=0.12 if i % 10 == 9 else 0.30)
        s_cal = float(gt["s"] * (1 + rng.uniform(-0.02, 0.02)))
        m.calib = Calib(s=s_cal)
        g = m.global_match(fr, s=s_cal)
        off = [float(v) for v in rng.uniform(-1, 1, 2) * 20]
        lr = m.local_match(fr, (gt["px0"] + off[0], gt["py0"] + off[1]), 50, s=s_cal)
        hd, _ = estimate_heading(crop_minimap(fr, m.calib))
        x, y, w, h = roi_rect(base)
        png(out / "singles" / f"{i:02d}.webp", fr[y:y + h, x:x + w])
        rows.append(dict(
            id=i, file=f"singles/{i:02d}.webp", level=levels[i % 3], px0=gt["px0"], py0=gt["py0"], sTrue=gt["s"], sCalib=s_cal,
            heading=gt["heading"], localPrior=[gt["px0"] + off[0], gt["py0"] + off[1]],
            py=dict(global_=dict(ok=bool(g.ok), px0=g.px0, py0=g.py0, score=g.score, margin=g.margin),
                    local=dict(ok=bool(lr.ok), px0=lr.px0, py0=lr.py0, score=lr.score, margin=lr.margin),
                    heading=None if hd is None else float(hd)),
        ))
        print(f"single {i + 1}/{n}", end="\r", flush=True)
    c = Calib(); screen.fit_ring_ref(fr, c)
    (out / "calib.json").write_text(json.dumps(calib_json(c)))
    (out / "singles" / "index.json").write_text(json.dumps(dict(roi=list(roi_rect(base)), frames=rows)))
    print()


def boundaries(segs):
    out, prev = [], None
    for sg in segs:
        if prev is not None and (sg.state != prev.state or sg.kind != prev.kind):
            out.append((sg.t0, 8.0 if prev.state == "SEA" else GRACE))
        prev = sg
    return out


class ZoomScenario(Scenario):
    """Walk with the minimap zoom changing on the fly (the game zooms out while moving fast): the minimap scale of every
    frame is `s0 * mult(t)`; the calibrated base scale stays s0."""

    def __init__(self, info, seed, s, plan, schedule):
        super().__init__(info, seed, s=s, plan=plan)
        self.s0, self.schedule = s, schedule

    def mult(self, t):
        return float(np.interp(t, [p[0] for p in self.schedule], [p[1] for p in self.schedule]))

    def frame_at(self, t):
        self.cfg.s = self.s0 * self.mult(t)
        try:
            return super().frame_at(t)
        finally:
            self.cfg.s = self.s0


# (t, multiplier) knots, linear in between; a repeated t makes a jump
ZOOM_SCHEDULE = [(0, 1.0), (6, 1.0), (8, 0.70), (18, 0.70), (18.001, 0.85), (24, 0.85), (26, 1.0), (30, 1.0),
                 (30.001, 0.62), (33, 0.62), (33.001, 1.0), (40, 1.0)]


def export_scenario(info, out: Path, name: str, plan, seed: int, s_true: float, calib_err: float, dry: bool = False, sc=None,
                    grid: float | None = None):
    sc = sc or Scenario(info, seed, s=s_true, plan=plan)
    calib = Calib(s=s_true * (1 + calib_err))
    screen.fit_ring_ref(sc.frame_at(0.5), calib)
    m = Matcher(info, Params(), calib, coarse_z=-2, threads=1)
    trk = Tracker(m, calib, TrackCfg(), sc.layers, FloorRefs(info, m.p), sc.targets)
    trk.start()
    python_ok = grid is None     # a fixed-scale Python tracker cannot follow a zooming minimap
    x, y, w, h = roi_rect(calib)
    bounds = boundaries(sc.segs) + [(0.0, 2.5)]      # the initial lock after start() is not graded
    d = out / "scenarios" / name
    frames, t, k = [], 0.0, 0
    while t < sc.duration:
        fr, tr = sc.at(t)
        p = trk.update(fr, t) if python_ok else None
        if not dry:
            png(d / f"{k:03d}.webp", fr[y:y + h, x:x + w])
            png(d / f"{k:03d}_t.png", screen.thumbnail(fr))
        frames.append(dict(
            t=round(t, 3), file=f"scenarios/{name}/{k:03d}.webp", thumb=f"scenarios/{name}/{k:03d}_t.png",
            expected=tr["state"], kind=tr["kind"], inWorld=bool(tr["in_world"]), px0=tr["px0"], py0=tr["py0"],
            floorId=tr["floor_id"], inZone=bool(tr["in_zone"]), grace=any(b <= t < b + g for b, g in bounds),
            py=dict(state=p.state, mode=p.mode, px0=p.px0, py0=p.py0, accepted=bool(p.accepted), floorId=p.floor_id) if p else
            dict(state=tr["state"], mode="n/a", px0=None, py0=None, accepted=False, floorId=None),
            scale=getattr(sc, "s0", 0) * sc.mult(t) if hasattr(sc, "mult") else None,
        ))
        t += grid if grid else trk.next_check_delay()
        k += 1
    graded = [f for f in frames if not f["grace"]]
    acc = sum(f["py"]["state"] == f["expected"] for f in graded) / max(1, len(graded))
    cave = [f for f in graded if f["kind"] == "cave"]
    facc = sum(f["py"]["floorId"] == f["floorId"] for f in cave) / max(1, len(cave))
    if dry:
        return acc, facc
    (d / "index.json").write_text(json.dumps(dict(
        name=name, duration=sc.duration, calib=calib_json(calib), roi=[x, y, w, h], sTrue=s_true,
        segs=[dict(kind=s_.kind, t0=s_.t0, t1=s_.t1, state=s_.state) for s_ in sc.segs], frames=frames,
        stats=dict(full=trk.stats.full, target=trk.stats.target, local=trk.stats.local, layer=trk.stats.layer_checks),
    )))
    wrong = sum(1 for f in frames if f["py"]["accepted"] and f["px0"] is not None and f["inWorld"]
                and math.hypot(f["py"]["px0"] - f["px0"], f["py"]["py0"] - f["py0"]) > 25)
    print(f"scenario {name}: {len(frames)} frames, {sc.duration:.0f} s, python graded state accuracy {acc:.3f}, floor accuracy {facc:.3f}, wrong accepted {wrong}")
    return acc, facc


class EntranceScenario(Scenario):
    """Scenario whose cave site is an *entrance point* (a real `entrance_ids` point of a floor) and that ends with a
    teleport straight to one of the underground Teleport Waypoints (a floor entrance of label 'Teleport Waypoint')."""

    def __init__(self, info, seed, s, plan):
        pg = json.loads((ROOT / "data/pack/maps" / str(info.map_id) / "point_groups.json").read_text())["list"]
        pts = {p["id"]: p for p in json.loads((ROOT / "data/pack/maps" / str(info.map_id) / "points.json").read_text())["point_list"]}
        self.ent = []        # (floor_id, point dict)
        for g in pg:
            for f in g["floors"]:
                for pid in f.get("entrance_ids") or []:
                    if pid in pts:
                        self.ent.append((f["id"], pts[pid]))
        super().__init__(info, seed, s=s, plan=plan)

    def _entrance_xy(self, p):
        return p["x_pos"] + self.info.origin[0], p["y_pos"] + self.info.origin[1]

    def _pick_site(self, multi):
        rng = self.rng
        for _ in range(4000):
            fid, p = self.ent[int(rng.integers(0, len(self.ent)))]
            A = self.layers.by_id.get(fid)
            if A is None or not (110 <= A.r - A.l <= 450 and 110 <= A.b - A.t <= 450):
                continue
            X = self._entrance_xy(p)
            if not A.contains(*X) or not floor_opaque(A, X[0], X[1], 0):
                continue
            if not _OPAQUE[A.floor_id][1][max(0, int(X[1] - A.t)), max(0, int(X[0] - A.l))]:
                continue
            bl = self._best_line(X, [A], True, A)
            if bl is None or bl[0] < 0.6:
                continue
            self.entrance_site = dict(floor=A.floor_id, point=p["id"], X=X)
            return dict(A=A, B=None, X=X, S=bl[1])
        raise RuntimeError("no entrance cave site")

    def add_underground_teleport(self, dur=8.0, kind="white"):
        """Append: loading screen, then standing / walking on a floor at an underground waypoint (no surface fade-in)."""
        rng = self.rng
        wp = [(fid, p) for fid, p in self.ent if p["label_id"] == self._wp_label()]
        for _ in range(500):
            fid, p = wp[int(rng.integers(0, len(wp)))]
            A = self.layers.by_id.get(fid)
            X = self._entrance_xy(p)
            if A is not None and A.contains(*X) and floor_opaque(A, X[0], X[1], 0) and \
                    _OPAQUE[A.floor_id][1][max(0, int(X[1] - A.t)), max(0, int(X[0] - A.l))]:
                break
        else:
            raise RuntimeError("no underground waypoint")
        t = self.segs[-1].t1
        area = self.segs[-1].area + 1
        self.segs.append(Seg("loading", t, t + 4.0, "LOADING", area - 1, None, None, {"kind": kind, "dest": "underground", "src": None, "domain_exit": False}))
        t += 4.0
        P, H = self._walk_in(A, X, dur, v0=6.0)
        n = len(P)
        self.segs.append(Seg("cave", t, t + n * 0.1, "TRACKING", area, (P, H, 0.1), None,
                             {"layers": np.array([A.floor_id] * n, dtype=object), "floors": (A.floor_id, None)}))
        self.duration = self.segs[-1].t1
        self.waypoint_site = dict(floor=A.floor_id, point=p["id"], X=X)

    def _wp_label(self):
        lab = json.loads((ROOT / "data/pack/maps" / str(self.info.map_id) / "labels.json").read_text())["tree"]
        out = []
        def rec(n):
            if n["name"] == "Teleport Waypoint":
                out.append(n["id"])
            for c in n.get("children") or []:
                rec(c)
        for n in lab:
            rec(n)
        return out[0]


def export_entrance(out: Path, info, seed: int):
    plan = [("walk", 4.0, 10.0), ("teleport", 4.0, "black", "cave"), ("cave", 4.0, "single"), ("walk", 2.0, 10.0)]
    sc = EntranceScenario(info, seed, 0.8, plan)
    sc.add_underground_teleport(dur=4.0)
    return sc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "app/src/androidTest/assets/cv"))
    ap.add_argument("--singles", type=int, default=40)
    ap.add_argument("--seed", type=int, default=11)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    info = load_info(2)
    export_singles(info, out, a.singles, a.seed)
    rng = np.random.default_rng(a.seed + 1)
    # 1) open world: walk, big map, teleport to a waypoint, walk, domain in / black minimap / out, walk
    world = [("walk", 8.0, 10.0), ("bigmap", 4.0), ("teleport", 4.0, "white", "waypoint"), ("walk", 8.0, 10.0),
             ("domain_in", 4.0, "black"), ("domain", 10.0), ("domain_out", 4.0, "white"), ("walk", 8.0, 10.0)]
    export_scenario(info, out, "world", world, seed=3, s_true=0.8, calib_err=0.01)
    # 2) caves: teleport next to a cave, walk in without a loading screen (floor switch), walk out
    cave = [("walk", 4.0, 10.0), ("teleport", 4.0, "black", "cave_multi"), ("cave", 4.0, "multi"), ("walk", 3.0, 10.0)]
    # the cave site is random: take the first seed on which the Python tracker itself follows the floors well
    for seed in range(5, 40):
        acc, facc = export_scenario(info, out, "cave", cave, seed=seed, s_true=0.7, calib_err=-0.01, dry=True)
        print(f"cave seed {seed}: state {acc:.3f} floor {facc:.3f}")
        if acc >= 0.97 and facc >= 0.9:
            break
    export_scenario(info, out, "cave", cave, seed=seed, s_true=0.7, calib_err=-0.01)
    # 3) zoom: the minimap zoom changes while walking (0.70x, 0.85x, 0.62x of the base scale, ramps and jumps)
    zsc = ZoomScenario(info, 9, 0.8, [("walk", 40.0, 10.0)], ZOOM_SCHEDULE)
    export_scenario(info, out, "zoom", None, 9, 0.8, 0.0, sc=zsc, grid=1.0)
    # 4) entrance: walk into a cave through an entrance point, then teleport to an underground waypoint
    esc = export_entrance(out, info, seed=7)
    print("entrance site", esc.entrance_site, "waypoint", esc.waypoint_site)
    export_scenario(info, out, "entrance", None, 7, 0.8, 0.005, sc=esc)
    ent = json.loads((out / "scenarios/entrance/index.json").read_text())
    ent["entranceSite"] = esc.entrance_site
    ent["waypointSite"] = esc.waypoint_site
    (out / "scenarios/entrance/index.json").write_text(json.dumps(ent))
    total = sum(f.stat().st_size for f in out.rglob("*") if f.is_file())
    print(f"total {total / 1e6:.1f} MB in {out}")


if __name__ == "__main__":
    main()
