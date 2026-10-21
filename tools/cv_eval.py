#!/usr/bin/env python
"""Evaluate the minimap matcher.

  tools/.venv/bin/python tools/cv_eval.py                 # synthetic (N=300) + tracking + real labels if present
  tools/.venv/bin/python tools/cv_eval.py --n 60 --quick  # fast sanity run
  tools/.venv/bin/python tools/cv_eval.py --real-only     # only tools/dataset/labels.json

Writes data/cv/eval_report.md.  NOTE: synthetic frames are cut from the very imagery used as the
reference, so they are optimistic; the real-set numbers are the ones that count.
"""
import argparse
import json
import platform
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import cv2
import numpy as np

from tools.cv.heading import angdiff, estimate_heading
from tools.cv.mapdata import CV_DIR, load_info
from tools.cv.match import Matcher
from tools.cv.preprocess import Calib, Params, crop_minimap
from tools.cv.track import TrackCfg, Tracker

FAIL_PX = 25.0
_W = {}


def _init(cfg):
    cv2.setNumThreads(1)
    info = load_info(2)
    _W["info"] = info
    _W["m"] = Matcher(info, Params(**cfg["params"]), Calib(), coarse_z=cfg["coarse_z"], threads=1)
    _W["cfg"] = cfg


def _one(job):
    """Run one synthetic sample: global (+ local) at slightly wrong calibrated scale."""
    from tools.cv.synth import make_sample
    idx, seed, level, s_fixed = job
    info, m, cfg = _W["info"], _W["m"], _W["cfg"]
    rng = np.random.default_rng(seed)
    fr, gt = make_sample(info, rng, s=s_fixed, level=level, min_edge=0.12 if idx % 10 == 9 else 0.30)
    s_cal = gt["s"] * (1 + rng.uniform(-cfg["s_err"], cfg["s_err"]))
    m.calib = Calib(s=s_cal)
    r = m.global_match(fr, s=s_cal)
    eg = float(np.hypot(r.px0 - gt["px0"], r.py0 - gt["py0"])) if r.ok else 1e9
    off = rng.uniform(-1, 1, 2) * 20
    lr = m.local_match(fr, (gt["px0"] + off[0], gt["py0"] + off[1]), 50, s=s_cal)
    el = float(np.hypot(lr.px0 - gt["px0"], lr.py0 - gt["py0"])) if lr.ok else 1e9
    hd, _ = estimate_heading(crop_minimap(fr, m.calib))
    he = abs(angdiff(hd, gt["heading"])) if hd is not None else None
    return dict(idx=idx, level=level, s=gt["s"], eg=eg, el=el, sg=r.score, mg=r.margin, sl=lr.score,
                msg=r.ms, msl=lr.ms, he=he, conf=r.confidence, z=lr.z)


def stats(e):
    e = np.asarray(e, float)
    return dict(n=len(e), med=float(np.median(e)), p90=float(np.percentile(e, 90)),
                fail=float((e > FAIL_PX).mean() * 100))


def fmt(st):
    return f"{st['med']:.2f} | {st['p90']:.2f} | {st['fail']:.1f}%"


def run_synthetic(args, lines):
    levels = ["clean", "mixed", "hard"]
    jobs = []
    for i in range(args.n):
        jobs.append((i, args.seed * 100003 + i, levels[i % 3], None))
    cfg = dict(params=dict(repr=args.repr, sigma=args.sigma), coarse_z=args.coarse_z, s_err=args.s_err)
    from tools.cv.preprocess import build_prepped
    info = load_info(2)
    for z in (-3, -2, -1, 0):
        build_prepped(info, z, Params(**cfg["params"]))
    t0 = time.time()
    with ProcessPoolExecutor(args.workers, initializer=_init, initargs=(cfg,)) as ex:
        rows = list(ex.map(_one, jobs, chunksize=2))
    print(f"synthetic accuracy pass: {time.time() - t0:.0f}s")
    lines += ["## Synthetic set", "",
              f"N={args.n} frames (1/3 each degradation level clean/mixed/hard), scale s log-uniform 0.4-1.3, "
              f"calibrated scale perturbed by +-{args.s_err * 100:.0f}% (calibration error). "
              f"Failure = error > {FAIL_PX:.0f} z0 px. Repr={args.repr}, sigma={args.sigma}, coarse z={args.coarse_z}.", "",
              "| Mode | subset | median px | p90 px | failure |", "|---|---|---|---|---|"]
    for name, key in (("global (whole map)", "eg"), ("local (prior +-20 px)", "el")):
        lines.append(f"| {name} | all | {fmt(stats([r[key] for r in rows]))} |")
        for lv in levels:
            lines.append(f"| {name} | {lv} | {fmt(stats([r[key] for r in rows if r['level'] == lv]))} |")
        for lo, hi in ((0, 0.6), (0.6, 0.9), (0.9, 2)):
            sel = [r[key] for r in rows if lo <= r["s"] < hi]
            if sel:
                lines.append(f"| {name} | s in [{lo},{hi}) | {fmt(stats(sel))} |")
    he = [r["he"] for r in rows if r["he"] is not None]
    lines += ["", f"Heading: detected in {len(he)}/{len(rows)}; median abs error {np.median(he):.1f} deg, p90 {np.percentile(he, 90):.1f} deg." if he else "Heading: none detected.", ""]
    # confidence separability
    good = [r for r in rows if r["eg"] <= FAIL_PX]
    bad = [r for r in rows if r["eg"] > FAIL_PX]
    lines.append(f"Global confidence: correct matches median score {np.median([r['sg'] for r in good]):.2f}, margin {np.median([r['mg'] for r in good]):.2f}" +
                 (f"; failures median score {np.median([r['sg'] for r in bad]):.2f}, margin {np.median([r['mg'] for r in bad]):.2f}." if bad else "; no failures."))
    if bad:
        lines.append("Failed global cases (s, level, score, margin): " + "; ".join(
            f"{r['s']:.2f}/{r['level']}/{r['sg']:.2f}/{r['mg']:.2f}" for r in bad[:15]))
    confident = lambda r: r["sg"] >= 0.5 and r["mg"] >= 0.08
    wrong_conf = [r for r in rows if r["eg"] > FAIL_PX and confident(r)]
    rej = [r for r in rows if r["eg"] > FAIL_PX and not confident(r)]
    acc = [r for r in rows if r["eg"] <= FAIL_PX and confident(r)]
    lines.append(f"Accept rule (score>=0.5 and margin>=0.08): accepted-and-correct {len(acc)}, "
                 f"accepted-but-wrong (dangerous) {len(wrong_conf)}, correct-but-rejected {len([r for r in rows if r['eg'] <= FAIL_PX and not confident(r)])}, "
                 f"wrong-and-rejected {len(rej)} (of {len(rows)}). Every 10th sample is drawn from nearly textureless sea/coast.")
    lines.append("")
    return rows


def run_timing(args, lines, n=24):
    from tools.cv.synth import make_sample
    info = load_info(2)
    rng = np.random.default_rng(args.seed + 7)
    samples = [make_sample(info, rng, s=float(s), level="mixed") for s in np.linspace(0.45, 1.2, n)]
    lines += ["## Timing", "", f"{n} synthetic frames, s from 0.45 to 1.2; host: {platform.processor() or platform.machine()}, "
              f"{cv2.getNumberOfCPUs()} logical CPUs. Mean over frames; local = masked NCC +-50 px window incl. template build.", "",
              "| Config | local ms (mean / p95) | global ms (mean / p95) |", "|---|---|---|"]
    for name, nth, mth in (("1 thread", 1, 1), (f"{args.threads} threads", args.threads, args.threads)):
        cv2.setNumThreads(nth)
        m = Matcher(info, Params(repr=args.repr, sigma=args.sigma), Calib(), coarse_z=args.coarse_z, threads=mth)
        tl, tg = [], []
        m.global_match(samples[0][0], s=samples[0][1]["s"])  # warm up (mmap, caches)
        for fr, gt in samples:
            s = gt["s"]
            t = time.perf_counter()
            m.local_match(fr, (gt["px0"] + 5, gt["py0"] - 4), 50, s=s)
            tl.append((time.perf_counter() - t) * 1e3)
            t = time.perf_counter()
            m.global_match(fr, s=s)
            tg.append((time.perf_counter() - t) * 1e3)
        lines.append(f"| {name} | {np.mean(tl):.1f} / {np.percentile(tl, 95):.1f} | {np.mean(tg):.0f} / {np.percentile(tg, 95):.0f} |")
    cv2.setNumThreads(args.threads)
    lines.append("")


def run_scale(args, lines, n=20):
    from tools.cv.synth import make_sample
    info = load_info(2)
    rng = np.random.default_rng(args.seed + 99)
    m = Matcher(info, Params(repr=args.repr, sigma=args.sigma), Calib(), coarse_z=args.coarse_z, threads=args.threads)
    res = {"hint": [], "nohint": []}
    t_h, t_n = [], []
    for i in range(n):
        fr, gt = make_sample(info, rng, level="mixed")
        hint = (gt["px0"] + rng.uniform(-60, 60), gt["py0"] + rng.uniform(-60, 60))
        t = time.perf_counter()
        s, r, _ = m.estimate_scale(fr, hint=hint, hint_radius_z0=120)
        t_h.append(time.perf_counter() - t)
        res["hint"].append((gt["s"], s, np.hypot(r.px0 - gt["px0"], r.py0 - gt["py0"]), r.score))
        if i < 16:
            t = time.perf_counter()
            s, r, _ = m.estimate_scale(fr)
            t_n.append(time.perf_counter() - t)
            res["nohint"].append((gt["s"], s, np.hypot(r.px0 - gt["px0"], r.py0 - gt["py0"]), r.score))
    lines += ["## Scale calibration", "",
              "Relative scale error |s_est/s_true - 1|; position error at the estimated scale.", "",
              "| Mode | n | median rel. err | p90 rel. err | within 3% | pos err median px | time s | accepted (score>=0.5) | wrong among accepted |", "|---|---|---|---|---|---|---|---|---|"]
    for k, tt in (("hint", t_h), ("nohint", t_n)):
        a = np.array(res[k])
        if len(a) == 0:
            continue
        rel = np.abs(a[:, 1] / a[:, 0] - 1)
        lines.append(f"| {'with ~60 px position hint' if k == 'hint' else 'no hint (global multi-scale)'} | {len(a)} | {np.median(rel) * 100:.2f}% | "
                     f"{np.percentile(rel, 90) * 100:.2f}% | {np.mean(rel < 0.03) * 100:.0f}% | {np.median(a[:, 2]):.1f} | {np.mean(tt):.1f} | "
                     f"{int((a[:, 3] >= 0.5).sum())}/{len(a)} | {int(((a[:, 3] >= 0.5) & (rel >= 0.03)).sum())} |")
    lines.append("")


def run_tracking(args, lines, ntraj=4):
    from tools.cv.synth import make_trajectory
    from tools.cv.track_legacy import RingDetector, TrackCfg, Tracker   # old 4 Hz tracker, kept for the legacy sequences
    info = load_info(2)
    rng = np.random.default_rng(args.seed + 5)
    errs, lost_frames, recov, total, times_local, times_glob = [], 0, [], 0, [], []
    specs = [dict(hidden_at=(), teleport_at=()), dict(hidden_at=(20, 21, 22, 23), teleport_at=()),
             dict(hidden_at=(), teleport_at=(25,)), dict(hidden_at=(15, 16, 17, 18, 19), teleport_at=(30,))]
    for k in range(ntraj):
        sp = specs[k % len(specs)]
        s = float(rng.uniform(0.5, 1.1))
        traj = make_trajectory(info, rng, n=45, s=s, level="mixed", **sp)
        m = Matcher(info, Params(repr=args.repr, sigma=args.sigma), Calib(s=s), coarse_z=args.coarse_z, threads=args.threads)
        from tools.cv import screen
        cal = Calib(s=s)
        screen.fit_ring_ref(traj[0][0], cal)

        class Det:                      # ring test of the new screen module (the legacy colour test does not know the new ring)
            def visible(self, frame, cal=cal):
                return screen.ring_present(frame, cal)
        trk = Tracker(m, cal, TrackCfg(), Det())
        t_since_event = None
        for i, (fr, gt) in enumerate(traj):
            p = trk.update(fr, t=i * 0.25)
            total += 1
            if gt.get("hidden"):
                if p.state not in ("HIDDEN", "OFF"):
                    lost_frames += 1
                continue
            if p.mode == "local":
                times_local.append(p.ms)
            elif p.mode == "global":
                times_glob.append(p.ms)
            e = np.hypot(p.px0 - gt["px0"], p.py0 - gt["py0"])
            if p.state == "TRACKING":
                errs.append(e)
                if e > FAIL_PX:
                    lost_frames += 1
            else:
                lost_frames += 1
    lines += ["## Tracking sequences", "",
              f"{ntraj} synthetic trajectories x 45 frames @4 Hz (speeds 1-8 z0 px/frame), with hidden-minimap gaps and teleports.", "",
              f"- TRACKING frames: {len(errs)}/{total}; median error {np.median(errs):.2f} px, p90 {np.percentile(errs, 90):.2f} px, "
              f"wrong or lost frames (visible minimap): {lost_frames}",
              f"- ms/frame: local mean {np.mean(times_local):.1f}" + (f", global mean {np.mean(times_glob):.0f}" if times_glob else ""), ""]


# ============================================================================== scenarios (PLAN.md 6.2)
SCEN_GRACE = 3.0
STATE_ALLOWED = {"SEA": ("TRACKING", "LOST")}
EXPECT_PATH = {"waypoint": "targets", "statue": "targets", "domain": "targets", "same": "last", "random": "full",
               "cave": "full", "cave_multi": "full", "teleport_coast": "full", None: "full"}


def _scen_init(cfg):
    cv2.setNumThreads(1)
    info = load_info(2)
    _W["info"] = info


def _make_scenario(info, kind, seed, s):
    from tools.cv.synth import Scenario, cave_plan, master_plan, plain_walk_plan, teleport_plan
    rng = np.random.default_rng(seed * 7919 + 13)
    plan = {"master": master_plan, "plain": plain_walk_plan, "teleports": teleport_plan, "caves": cave_plan}[kind](rng)
    return Scenario(info, seed, s=s, plan=plan)


def _run_scenario(job):
    """job = (kind, seed, variant) -> dict(rows, episodes, stats, segs).  variant: new | fullmap | nolayers | legacy"""
    from tools.cv.layers import FloorRefs
    from tools.cv.screen import fit_ring_ref
    from tools.cv.targets import load_targets
    kind, seed, variant = job
    info = _W["info"]
    rng = np.random.default_rng(seed + 555)
    s_true = float(rng.uniform(0.55, 1.05))
    sc = _make_scenario(info, kind, seed, s_true)
    calib = Calib(s=s_true * (1 + rng.uniform(-0.02, 0.02)))
    fit_ring_ref(sc.frame_at(0.5), calib)
    m = Matcher(info, Params(), calib, coarse_z=-2, threads=1)
    rows = []
    if variant == "legacy":
        from tools.cv.track_legacy import Tracker as LT, TrackCfg as LCfg
        from tools.cv import screen

        class Det:
            def visible(self, frame):
                return screen.ring_present(frame, calib)
        trk = LT(m, calib, LCfg(), Det())
        step = 0.25
        t = 0.0
        cpu = 0.0
        nglob = 0
        while t < sc.duration:
            fr, tr = sc.at(t)
            c0 = time.process_time()
            p = trk.update(fr, t)
            cpu += (time.process_time() - c0) * 1e3
            nglob += p.mode == "global"
            err = float(np.hypot(p.px0 - tr["px0"], p.py0 - tr["py0"])) if tr["px0"] is not None else None
            rows.append(dict(t=t, state=p.state, err=err, acc=p.measured is not None, kind=tr["kind"],
                             exp=tr["state"], in_world=tr["in_world"]))
            t += step
        return dict(kind=kind, seed=seed, variant=variant, rows=rows, duration=sc.duration, cpu_ms=cpu,
                    checks=len(rows), full=nglob)
    cfg = TrackCfg()
    if variant == "fullmap":
        cfg.reloc_order = "fullmap"
    if variant == "nolayers":
        cfg.layers_on = False
    trk = Tracker(m, calib, cfg, sc.layers, FloorRefs(info, m.p), sc.targets)
    trk.start()
    t = 0.0
    while t < sc.duration:
        fr, tr = sc.at(t)
        st = trk.stats
        before = (st.full, st.target, st.local, st.layer_checks, st.cheap, st.cpu_ms)
        p = trk.update(fr, t)
        err = float(np.hypot(p.px0 - tr["px0"], p.py0 - tr["py0"])) if tr["px0"] is not None else None
        rows.append(dict(t=t, state=p.state, mode=p.mode, acc=p.accepted, err=err, kind=tr["kind"], exp=tr["state"],
                         in_world=tr["in_world"], floor=p.floor_id, tfloor=tr["floor_id"], in_zone=tr["in_zone"],
                         d=(st.full - before[0], st.target - before[1], st.local - before[2], st.layer_checks - before[3],
                            st.cheap - before[4]), cpu=st.cpu_ms - before[5], ms=p.ms, path=p.path, seg=tr["seg"]))
        t += p.delay
    segs = []
    for sg in sc.segs:
        d = dict(kind=sg.kind, t0=sg.t0, t1=sg.t1, state=sg.state, meta={})
        if sg.kind == "loading":
            d["meta"] = dict(dest=sg.meta.get("dest"), dexit=sg.meta.get("domain_exit"))
        if sg.kind == "cave":
            ly = sg.meta["layers"]
            d["meta"] = dict(switches=[(sg.t0 + k * 0.1, ly[k - 1], ly[k]) for k in range(1, len(ly)) if ly[k] != ly[k - 1]])
        segs.append(d)
    stt = trk.stats
    return dict(kind=kind, seed=seed, variant=variant, rows=rows, episodes=trk.episodes, segs=segs, duration=sc.duration,
                stats=dict(checks=stt.checks, full=stt.full, target=stt.target, local=stt.local, layer=stt.layer_checks,
                           cheap=stt.cheap, cpu_ms=stt.cpu_ms, cheap_ms=stt.cheap_ms, by_state=stt.by_state))


def _boundaries(segs):
    """[(t, grace)] at every change of expected state."""
    out, prev = [], None
    for sg in segs:
        if prev is not None and (sg["state"] != prev["state"] or sg["kind"] != prev["kind"]):
            out.append((sg["t0"], 8.0 if prev["state"] == "SEA" else SCEN_GRACE))
        prev = sg
    return out


def _in_grace(t, bounds):
    return any(b <= t < b + g for b, g in bounds)


def _confusion(rows, bounds):
    order = ["TRACKING", "LOADING", "RELOCATING", "DOMAIN", "HIDDEN", "LOST"]
    cm = {e: {g: 0 for g in order} for e in order + ["SEA"]}
    ok = n = ok_g = n_g = 0
    for r in rows:
        e, g = r["exp"], r["state"]
        cm[e][g] = cm[e].get(g, 0) + 1
        good = g in STATE_ALLOWED.get(e, (e,))
        ok += good
        n += 1
        if not _in_grace(r["t"], bounds):
            ok_g += good
            n_g += 1
    return order, cm, ok, n, ok_g, n_g


def run_screen_tests(args, lines, n=120):
    """Classifier-level checks of the cheap tests on many synthetic frames (+ timing)."""
    from tools.cv import screen
    from tools.cv.layers import LayerIndex
    from tools.cv.synth import (Scenario, Scene, bigmap_frame, cutscene_frame, loading_frame, menu_frame, random_cfg,
                                render_world, sample_position, render_floor_crop)
    info = load_info(2)
    rng = np.random.default_rng(args.seed + 31)
    calib = Calib(s=0.8)
    sc = Scenario(info, 3, s=0.8, plan=[("walk", 5.0, 10.0)])
    screen.fit_ring_ref(sc.frame_at(0.5), calib)
    pos_l, neg_l, ring_p, ring_n, blk_p, blk_n, cave_n = [], [], [], [], [], [], []
    li = LayerIndex(info)
    for i in range(n):
        pos_l.append(loading_frame(rng, "white" if i % 2 else "black"))
    pos_t = [screen.is_loading_screen(screen.thumbnail(f)) for f in pos_l]
    pos_c = [screen.is_loading_screen(screen.thumbnail(f)) and not screen.ring_present(f, calib) for f in pos_l]
    neg_t, neg_c = [], []
    for i in range(n):
        cfgx = random_cfg(rng, s=0.8, level="mixed")
        scn = Scene(int(rng.integers(0, 10**6)), cfgx)
        px, py = sample_position(info, rng, 0.8)
        k = i % 8
        if k == 0:
            f = render_world(info, scn, px, py, 0.0, rng)
            ring_p.append(screen.ring_present(f, calib))
        elif k == 1:
            f = bigmap_frame(info, rng, px, py)
        elif k == 2:
            f = menu_frame(rng)
        elif k == 3:
            f = cutscene_frame(scn, rng)
        elif k == 4:   # HUD visible in a dark cave / night scene
            f = render_world(info, scn, px, py, 0.0, rng, dark_bg=True)
            ring_p.append(screen.ring_present(f, calib))
        elif k == 5:   # domain interior: HUD + black minimap
            f = render_world(info, scn, px, py, 0.0, rng, black=True, dark_bg=True)
            ring_p.append(screen.ring_present(f, calib))
            blk_p.append(screen.minimap_is_black(crop_minimap(f, calib)))
        elif k == 6:   # open world at night / far sea frames
            f = render_world(info, scn, px, py, 0.0, rng)
            ring_p.append(screen.ring_present(f, calib))
        else:          # underground floor minimap (dark background, must not look like a domain)
            fl = li.floors[int(rng.integers(0, len(li.floors)))]
            qx, qy = (fl.l + fl.r) / 2, (fl.t + fl.b) / 2
            f = render_world(info, scn, qx, qy, 0.0, rng, floor=fl)
            ring_p.append(screen.ring_present(f, calib))
            cave_n.append(screen.minimap_is_black(crop_minimap(f, calib)))
        if k not in (0, 4, 5, 6, 7):
            ring_n.append(screen.ring_present(f, calib))
        neg_t.append(screen.is_loading_screen(screen.thumbnail(f)))
        neg_c.append(neg_t[-1] and not screen.ring_present(f, calib))
        if k in (0, 6, 4):
            blk_n.append(screen.minimap_is_black(crop_minimap(f, calib)))
    # sea at night (dark, flat)
    sea_fp, sea_flat = [], []
    land_flat = []
    for i in range(30):
        scn = Scene(int(rng.integers(0, 10**6)), random_cfg(rng, s=0.8, level='mixed'))
        px, py = sample_position(info, rng, 0.8, min_edge=0.12)
        land_flat.append(screen.minimap_is_flat(crop_minimap(render_world(info, scn, px, py, 0.0, rng), calib)))
    for i in range(30):
        cfgx = random_cfg(rng, s=0.8, level="hard")
        cfgx.gain = 0.55
        scn = Scene(int(rng.integers(0, 10**6)), cfgx)
        for _ in range(500):
            px, py = sc.terr.lv.to_z0(int(rng.integers(40, sc.terr.g.shape[1] - 40)), int(rng.integers(40, sc.terr.g.shape[0] - 40)))
            if sc.terr.flat(px, py, 135.0):
                break
        f = render_world(info, scn, px, py, 0.0, rng)
        sea_fp.append(screen.minimap_is_black(crop_minimap(f, calib)))
        sea_flat.append(screen.minimap_is_flat(crop_minimap(f, calib)))
    # timing
    f = sc.frame_at(0.5)
    crop = crop_minimap(f, calib)
    prev = crop.copy()
    th = screen.thumbnail(f)

    def tm(fn, k=300):
        fn()
        t0 = time.perf_counter()
        for _ in range(k):
            fn()
        return (time.perf_counter() - t0) / k * 1e3
    rate = lambda a: f"{100 * float(np.mean(a)):.1f}% ({int(np.sum(a))}/{len(a)})"
    lines += ["## Cheap screen tests (synthetic classifier checks)", "",
              f"Generated frames: {len(pos_l)} loading screens (white/black, bottom icon + progress bar, tips text in half, vignette, noise, JPEG) "
              f"vs {len(neg_t)} non-loading frames (world with HUD, dark HUD scenes, domain interior, big map, menu, letterboxed cut-scene, "
              "cave floor minimap).", "",
              "| Test | positives detected | false positives |", "|---|---|---|",
              f"| is_loading_screen (thumbnail only) | {rate(pos_t)} | {rate(neg_t)} (dark HUD scenes / domain interior that are nearly uniform) |",
              f"| loading = thumbnail uniform **and ring absent** (what the tracker uses) | {rate(pos_c)} | {rate(neg_c)} |",
              f"| minimap_is_flat (skips searches on open sea) | {rate(sea_flat)} (night/day sea) | textured land incl. coasts {rate(land_flat)} |",
              f"| ring_present | {rate(ring_p)} (HUD frames) | {rate(ring_n)} (no-HUD frames) |",
              f"| minimap_is_black | {rate(blk_p)} (domain minimaps) | open world/night {rate(blk_n)}, cave floors {rate(cave_n)}, night sea {rate(sea_fp)} |",
              "", "Timing per call on this PC (1920x1080 BGR input, single thread):", "",
              "| Test | ms |", "|---|---|",
              f"| thumbnail (64x36) | {tm(lambda: screen.thumbnail(f)):.3f} |",
              f"| is_loading_screen | {tm(lambda: screen.is_loading_screen(th)):.3f} |",
              f"| ring_present | {tm(lambda: screen.ring_present(f, calib)):.3f} |",
              f"| crop_minimap | {tm(lambda: crop_minimap(f, calib)):.3f} |",
              f"| minimap_is_black | {tm(lambda: screen.minimap_is_black(crop)):.3f} |",
              f"| frame_changed | {tm(lambda: screen.frame_changed(prev, crop)):.3f} |", ""]
    return dict(load_recall=float(np.mean(pos_t)), load_fp=float(np.mean(neg_t)), blk_recall=float(np.mean(blk_p)))


def _pct(a, q):
    return float(np.percentile(a, q)) if len(a) else float("nan")


def run_scenarios(args, lines):
    seeds = list(range(1, args.seeds + 1))
    jobs = [("master", sd, "new") for sd in seeds] + [("plain", 1, "new"), ("plain", 2, "new")]
    jobs += [("teleports", sd, v) for sd in range(1, args.tele_seeds + 1) for v in ("new", "fullmap")]
    jobs += [("caves", sd, v) for sd in range(1, args.cave_seeds + 1) for v in ("new", "nolayers")]
    t0 = time.time()
    with ProcessPoolExecutor(min(args.workers, len(jobs)), initializer=_scen_init, initargs=({},)) as ex:
        outs = list(ex.map(_run_scenario, jobs, chunksize=1))
    print(f"scenario runs: {time.time() - t0:.0f}s")
    R = {(o["kind"], o["seed"], o["variant"]): o for o in outs}
    # CPU budget: re-run the budget scenarios one after the other so parallel workers do not inflate the CPU times
    B = {}
    if not args.no_budget:
        _scen_init({})
        for job in [("plain", 1, "new"), ("master", 1, "new")] + ([("plain", 1, "legacy"), ("master", 1, "legacy")] if args.legacy else []):
            B[job] = _run_scenario(job)
        print(f"serial budget runs done: {time.time() - t0:.0f}s")
    lines += ["# Tracking-state scenario report", "",
              f"Generated {time.strftime('%Y-%m-%d %H:%M')} by `tools/cv_eval.py --scenarios`. Simulated time: frames are rendered at the instants the tracker "
              "asks for (`next_check_delay()`), so check rates are real; compute latency (a 0.05-0.5 s search) is not added to the simulated clock. "
              "Single thread (cv2 + matcher), CPU = process CPU time inside `Tracker.update`. **Synthetic only**: loading/domain/ring thresholds, "
              "speeds and layer behaviour must be confirmed on real Thor captures.", ""]
    if not args.no_screen:
        run_screen_tests(args, lines)

    # ---------------- master scenarios
    masters = [R[("master", sd, "new")] for sd in seeds]
    allrows, tot_dur, tot_cpu, tot_checks = [], 0.0, 0.0, 0
    lines += ["## State machine on the master scenario", "",
              "Each run (~5.5 min): walk (10/18/30 z0 px/s), stand 25 s (camera turning), big map, menu, teleports (to a waypoint, to a domain, to a random "
              "place), domain (loading, black minimap 30 s, loading, back near the entrance), cut-scene, sea round trip (textureless), walk.", "",
              "State accuracy: raw = every sampled check; graded = excluding the 3 s after each expected-state change (8 s after the sea trip), which is "
              "the unavoidable detection latency (2 uniform checks at 1 Hz, etc.).", ""]
    order = ["TRACKING", "LOADING", "RELOCATING", "DOMAIN", "HIDDEN", "LOST"]
    cm_tot = {e: {g: 0 for g in order} for e in order + ["SEA"]}
    ok = n = ok_g = n_g = 0
    det = {"LOADING": [0, 0], "DOMAIN": [0, 0]}
    det_seg = {"LOADING": [0, 0], "DOMAIN": [0, 0]}
    errs, wrong_acc, wrong_disp, coast_n = [], 0, 0, 0
    relock, relock_ep = [], []
    plain_full = plain_min = 0.0
    walk_rows_full = walk_checks = 0
    for o in masters:
        rows, segs = o["rows"], o["segs"]
        bounds = _boundaries(segs)
        _, cm, a, b, ag, bg = _confusion(rows, bounds)
        ok, n, ok_g, n_g = ok + a, n + b, ok_g + ag, n_g + bg
        for e in cm:
            for g in cm[e]:
                cm_tot[e][g] += cm[e][g]
        # detection recall after latency
        for r in rows:
            sg = segs[r["seg"]]
            for st_ in ("LOADING", "DOMAIN"):
                # a loading screen that follows the black domain minimap is noticed with the 5 s DOMAIN delay + 1 s confirmation
                lat = 6.0 if (st_ == "LOADING" and r["seg"] > 0 and segs[r["seg"] - 1]["state"] == "DOMAIN") else 3.0
                if sg["state"] == st_ and r["t"] - sg["t0"] >= lat:
                    det[st_][1] += 1
                    det[st_][0] += r["state"] == st_
        for si, sg in enumerate(segs):
            for st_ in ("LOADING", "DOMAIN"):
                if sg["state"] == st_ and sg["kind"] in ("loading", "domain"):
                    hit = any(r["state"] == st_ for r in rows if r["seg"] == si)
                    det_seg[st_][1] += 1
                    det_seg[st_][0] += hit
        for r in rows:
            if r["err"] is not None and r["in_world"]:
                if r["acc"] and r["err"] > FAIL_PX:
                    wrong_acc += 1
                if r["state"] == "TRACKING" and r["exp"] == "TRACKING" and not _in_grace(r["t"], bounds):
                    errs.append(r["err"])
                    wrong_disp += r["err"] > FAIL_PX
        # relock after loading
        for si, sg in enumerate(segs):
            if sg["kind"] == "loading" and not (si + 1 < len(segs) and segs[si + 1]["kind"] == "fadein" and
                                                   segs[si + 2]["kind"] == "domain"):
                t_end = sg["t1"]
                t_ok = next((r["t"] for r in rows if r["t"] >= t_end and r["state"] == "TRACKING" and r["err"] is not None
                             and r["err"] <= FAIL_PX and r["kind"] in ("walk", "fadein", "stand", "sea")), None)
                relock.append(None if t_ok is None else t_ok - t_end)
        # full searches while plainly walking/standing (after the first lock, >=8 s after any non-walk segment)
        last_other = -1e9
        for r in rows:
            if r["kind"] not in ("walk", "stand") or r["exp"] != "TRACKING":
                last_other = r["t"]
                continue
            if r["t"] < 4.0 or r["t"] - last_other < 8.0:
                continue
            walk_checks += 1
            walk_rows_full += r["d"][0] + r["d"][1]
        tot_dur += o["duration"]
        tot_cpu += o["stats"]["cpu_ms"]
        tot_checks += o["stats"]["checks"]
    lines += ["Confusion matrix, summed over all runs (rows = expected, columns = tracker state):", "",
              "| expected \\ got | " + " | ".join(order) + " |", "|---" * (len(order) + 1) + "|"]
    for e in order + ["SEA"]:
        lines.append(f"| {e} | " + " | ".join(str(cm_tot[e][g]) for g in order) + " |")
    lines += ["", f"- State accuracy: raw {100 * ok / n:.1f}% ({ok}/{n}), graded {100 * ok_g / n_g:.1f}% ({ok_g}/{n_g}). `SEA` accepts TRACKING or LOST.",
              f"- LOADING detection: {100 * det['LOADING'][0] / max(1, det['LOADING'][1]):.1f}% of checks >=3 s into a loading screen (6 s when it follows a domain: 5 s DOMAIN delay + 1 s) "
              f"({det['LOADING'][0]}/{det['LOADING'][1]}); segments detected {det_seg['LOADING'][0]}/{det_seg['LOADING'][1]}.",
              f"- DOMAIN detection: {100 * det['DOMAIN'][0] / max(1, det['DOMAIN'][1]):.1f}% of checks >=3 s into the black-minimap stretch "
              f"({det['DOMAIN'][0]}/{det['DOMAIN'][1]}); segments detected {det_seg['DOMAIN'][0]}/{det_seg['DOMAIN'][1]}.",
              f"- Position error while TRACKING (graded, open world incl. the sea stretch coasting excluded): median {_pct(errs, 50):.2f} px, p90 {_pct(errs, 90):.2f} px, "
              f"max {max(errs) if errs else float('nan'):.1f} px; frames shown >{FAIL_PX:.0f} px off: {wrong_disp}/{len(errs)}.",
              f"- **Wrong positions accepted** (any accepted measurement/lock/still-check with error > {FAIL_PX:.0f} z0 px, all scenarios below too): "
              f"master runs {wrong_acc}.", ""]
    rl = [x for x in relock if x is not None]
    lines += [f"- Time to a correct TRACKING position after a loading screen ends (simulated): median {_pct(rl, 50):.1f} s, p90 {_pct(rl, 90):.1f} s, "
              f"max {max(rl) if rl else float('nan'):.1f} s, within 2 s {sum(x <= 2.0 for x in rl)}/{len(relock)} "
              f"(never relocked: {sum(x is None for x in relock)}). The loading screen is noticed up to 1 s late (1 Hz) and the 2-frame agreement costs 0.5 s.", ""]

    # ---------------- plain walking
    pw = [R[("plain", sd, "new")] for sd in (1, 2)]
    lines += ["## Plain walking (3 min, 10/28/14 z0 px/s)", ""]
    for o in pw:
        rows = [r for r in o["rows"] if r["t"] >= 4.0]
        e = [r["err"] for r in rows if r["state"] == "TRACKING"]
        wa = sum(1 for r in o["rows"] if r["acc"] and r["err"] is not None and r["err"] > FAIL_PX)
        mins = (o["duration"] - 4.0) / 60
        sums = [sum(r["d"][i] for r in rows) for i in range(5)]
        cpu = sum(r["cpu"] for r in rows)
        lines.append(f"- seed {o['seed']} (initial lock excluded): full searches {sums[0]}, target searches {sums[1]}, local matches {sums[2]}, "
                     f"layer checks {sums[3]}, checks {len(rows)} ({len(rows) / mins:.0f}/min), cheap-only {sums[4]}, "
                     f"CPU {cpu / mins:.0f} ms/min, TRACKING error median {_pct(e, 50):.2f} px, p90 {_pct(e, 90):.2f} px, wrong accepted {wa}")
    lines += ["", f"Master runs, walking/standing stretches only (>= 8 s after any non-walk event): {walk_checks} checks, "
              f"**{walk_rows_full} full or target searches**.", ""]

    # ---------------- budget vs legacy
    lines += ["## Check and CPU budget vs the old 4 Hz design", "",
              "Old design = `tools/cv/track_legacy.py` (4 Hz, local search every frame, whole-map search on every miss), run on the same scenarios with the "
              "same ring test. New design numbers come from the runs above.", "",
              "| Scenario | design | checks/min | CPU ms/min | full searches/min | wrong accepted |", "|---|---|---|---|---|---|"]

    def row_for(o, name, design):
        wa = sum(1 for r in o["rows"] if r["acc"] and r["err"] is not None and r["err"] > FAIL_PX)
        if o["variant"] == "legacy":
            mins = o["duration"] / 60
            return f"| {name} | {design} | {o['checks'] / mins:.0f} | {o['cpu_ms'] / mins:.0f} | {o['full'] / mins:.1f} | {wa} |"
        rows = [r for r in o["rows"] if r["t"] >= 4.0]      # initial lock excluded
        mins = (o["duration"] - 4.0) / 60
        full = sum(r["d"][0] for r in rows)
        tgt = sum(r["d"][1] for r in rows)
        return (f"| {name} | {design} | {len(rows) / mins:.0f} | {sum(r['cpu'] for r in rows) / mins:.0f} | "
                f"{full / mins:.1f} (+{tgt / mins:.1f} target batches) | {wa} |")
    BR = B if B else R
    lines.append(row_for(BR[("plain", 1, "new")], "plain walk", "new"))
    if ("plain", 1, "legacy") in BR:
        lines.append(row_for(BR[("plain", 1, "legacy")], "plain walk", "old 4 Hz"))
    lines.append(row_for(BR[("master", 1, "new")], "master", "new"))
    if ("master", 1, "legacy") in BR:
        lines.append(row_for(BR[("master", 1, "legacy")], "master", "old 4 Hz"))
    lines.append("")
    lines += ["Master run, new design, share of checks per tracker state (seed 1): " +
              ", ".join(f"{k} {v}" for k, v in sorted(R[("master", 1, "new")]["stats"]["by_state"].items())) +
              f"; cheap-test time {R[('master', 1, 'new')]['stats']['cheap_ms'] / max(1, R[('master', 1, 'new')]['stats']['checks']):.2f} ms/check average.", ""]
    if ("master", 1, "legacy") in BR:
        lo = BR[("master", 1, "legacy")]
        bad = [r for r in lo["rows"] if r["acc"] and r["err"] is not None and r["err"] > FAIL_PX]
        lines += [f"Old design on the master run: wrong positions accepted {len(bad)}; frames with state TRACKING but > {FAIL_PX:.0f} px off: "
                  f"{sum(1 for r in lo['rows'] if r['state'] == 'TRACKING' and r['err'] is not None and r['in_world'] and r['err'] > FAIL_PX)}.", ""]

    # ---------------- relocation paths
    lines += ["## RELOCATING search order (teleport scenarios)", "",
              "16 teleports per run: 7 to a waypoint, 2 statue, 2 domain entrance (all landing 5-40 px from the real point), 3 'same' (respawn/pot exit, <=30 px "
              "from the old position), 2 random places (portable waypoint). `fullmap` = old whole-map-first order on the same runs.", ""]
    ep_stats = {}
    for variant in ("new", "fullmap"):
        for sd in range(1, args.tele_seeds + 1):
            o = R[("teleports", sd, variant)]
            segs, eps = o["segs"], o["episodes"]
            lo = [sg for sg in segs if sg["kind"] == "loading"]
            for i, sg in enumerate(lo):
                nxt = lo[i + 1]["t0"] if i + 1 < len(lo) else 1e9
                ep = next((e for e in eps if sg["t1"] - 1.5 <= e["t0"] < nxt and e["src"] != "START"), None)
                dest = sg["meta"]["dest"]
                key = (variant, EXPECT_PATH.get(dest, "full"))
                d = ep_stats.setdefault(key, dict(n=0, hit=0, locked=0, cpu=[], t=[], wrong=0, dests=[]))
                d["n"] += 1
                if ep and ep["t_lock"] is not None:
                    d["locked"] += 1
                    d["hit"] += ep["path"] == EXPECT_PATH.get(dest, "full")
                    d["cpu"].append(ep["cpu_ms"])
                    d["t"].append(ep["t_lock"] - sg["t1"])
                    truth_ok = next((r["err"] for r in o["rows"] if abs(r["t"] - ep["t_lock"]) < 1e-6), None)
                    d["wrong"] += truth_ok is not None and truth_ok > FAIL_PX
                    d["dests"].append(ep["path"])
    lines += ["| order | destination type (expected path) | relocations | locked | lock via expected path | wrong lock | time from screen end to lock, median / max (s) | CPU per relocation, mean / max (ms) |",
              "|---|---|---|---|---|---|---|---|"]
    for variant in ("new", "fullmap"):
        for path in ("last", "targets", "full"):
            d = ep_stats.get((variant, path))
            if not d:
                continue
            lines.append(f"| {variant} | {path} | {d['n']} | {d['locked']} | {d['hit']} | {d['wrong']} | "
                         f"{_pct(d['t'], 50):.1f} / {max(d['t']) if d['t'] else float('nan'):.1f} | "
                         f"{np.mean(d['cpu']) if d['cpu'] else float('nan'):.0f} / {max(d['cpu']) if d['cpu'] else float('nan'):.0f} |")
    allnew = [x for (v, p), d in ep_stats.items() if v == "new" for x in d["cpu"]]
    allold = [x for (v, p), d in ep_stats.items() if v == "fullmap" for x in d["cpu"]]
    tn = [x for (v, p), d in ep_stats.items() if v == "new" for x in d["t"]]
    to = [x for (v, p), d in ep_stats.items() if v == "fullmap" for x in d["t"]]
    lines += ["", f"All relocations: new order mean CPU {np.mean(allnew):.0f} ms, median time {_pct(tn, 50):.1f} s (p90 {_pct(tn, 90):.1f}); "
              f"whole-map-first mean CPU {np.mean(allold):.0f} ms, median time {_pct(to, 50):.1f} s (p90 {_pct(to, 90):.1f}). "
              "(In the new order a 'full' destination first pays steps 1 and 2, then the whole-map scan.)", ""]
    wl = sum(d["wrong"] for d in ep_stats.values())
    for sd in range(1, args.tele_seeds + 1):
        for variant in ("new", "fullmap"):
            wrong_acc += sum(1 for r in R[("teleports", sd, variant)]["rows"] if r["acc"] and r["err"] is not None and r["err"] > FAIL_PX)

    # ---------------- layers
    lines += ["## Map layers (caves)", "",
              "Cave scenarios: teleport next to a cave entrance (surface minimap), walk in **without a loading screen** (the minimap switches to the floor "
              "overlay composited over a dark background), in `multi` runs stand at a spot shared by two floors of one group and switch floor in place, "
              "walk on the second floor, then walk out. Truth floor = overlay content (dilated 12 px) at the player position. `nolayers` = layer logic off.", ""]
    lay = dict(rows=0, ok=0, lat=[], err=[], full_in_cave=0, lost_in_cave=0, out_checks=0, out_rows=0, in_checks=0, in_min=0.0, wrong=0,
               err_nl=[], lost_nl=0, rows_nl=0, surf_ok=0, surf_n=0)
    for sd in range(1, args.cave_seeds + 1):
        o = R[("caves", sd, "new")]
        onl = R[("caves", sd, "nolayers")]
        rows = o["rows"]
        switches = [sw for sg in o["segs"] if sg["kind"] == "cave" for sw in sg["meta"]["switches"]]
        for r in rows:
            if r["err"] is not None and r["acc"] and r["err"] > FAIL_PX:
                lay["wrong"] += 1
            if r["kind"] == "cave":
                near_sw = any(0 <= r["t"] - sw[0] < 3.0 for sw in switches)
                if not near_sw:
                    lay["rows"] += 1
                    lay["ok"] += r["floor"] == r["tfloor"]
                    if r["tfloor"] is not None:
                        lay["err"].append(r["err"]) if r["state"] == "TRACKING" else None
                if r["state"] in ("LOST",):
                    lay["lost_in_cave"] += 1
                if r["t"] - o["segs"][r["seg"]]["t0"] >= 3.0:      # the teleport relocation happens in the first seconds
                    lay["full_in_cave"] += r["d"][0] + r["d"][1]
            if r["tfloor"] is None and r["kind"] in ("walk", "cave") :
                pass
            if not r["in_zone"]:
                lay["out_rows"] += 1
                lay["out_checks"] += r["d"][3]
            else:
                lay["in_checks"] += r["d"][3]
        lay["in_min"] += sum(1 for r in rows if r["in_zone"]) and 0
        for sw in switches:
            t_sw, a, b = sw
            hit = next((r["t"] - t_sw for r in rows if r["t"] >= t_sw and r["floor"] == b and r["state"] == "TRACKING"), None)
            lay["lat"].append((a, b, hit))
        for r in onl["rows"]:
            if r["kind"] == "cave":
                lay["rows_nl"] += 1
                lay["lost_nl"] += r["state"] == "LOST"
                if r["state"] == "TRACKING" and r["err"] is not None:
                    lay["err_nl"].append(r["err"])
        # time inside zones (by truth) in minutes
        dt_in = 0.0
        for a_, b_ in zip(rows[:-1], rows[1:]):
            if a_["in_zone"]:
                dt_in += b_["t"] - a_["t"]
        lay["in_min"] += dt_in / 60
    lat = [x[2] for x in lay["lat"]]
    sw_in = [x for x in lay["lat"] if x[0] is None and x[1] is not None]
    sw_fl = [x for x in lay["lat"] if x[0] is not None and x[1] is not None]
    sw_out = [x for x in lay["lat"] if x[1] is None]
    f = lambda L: (f"{sum(x[2] is not None for x in L)}/{len(L)} switched, latency median {_pct([x[2] for x in L if x[2] is not None], 50):.1f} s, "
                   f"max {max([x[2] for x in L if x[2] is not None] or [float('nan')]):.1f} s")
    lines += [f"- Layer accuracy (cave segments, excluding 3 s after each truth switch): {100 * lay['ok'] / max(1, lay['rows']):.1f}% ({lay['ok']}/{lay['rows']}).",
              f"- Switch surface -> floor (walking in, no loading): {f(sw_in)}.",
              f"- Switch floor -> floor (in place): {f(sw_fl)}.",
              f"- Switch floor -> surface (walking out): {f(sw_out)}.",
              f"- Position error on floors (state TRACKING): median {_pct(lay['err'], 50):.2f} px, p90 {_pct(lay['err'], 90):.2f} px; "
              f"checks in LOST while in a cave: {lay['lost_in_cave']}; **full/target searches triggered anywhere in cave runs: {lay['full_in_cave']}** "
              "(checks in cave segments; the initial teleport relocations happen before the cave segment).",
              f"- Layer checks outside a zone (truth, rect margin 0): {lay['out_checks']} over {lay['out_rows']} checks; inside zones: {lay['in_checks']} "
              f"({lay['in_checks'] / max(lay['in_min'], 1e-6):.1f} per minute in zones).",
              f"- Without layer logic (`nolayers`): cave checks in LOST {lay['lost_nl']}/{lay['rows_nl']}, position error while TRACKING median "
              f"{_pct(lay['err_nl'], 50):.1f} px (p90 {_pct(lay['err_nl'], 90):.1f}).",
              f"- Wrong positions accepted in cave runs: {lay['wrong']}.", ""]
    lines += ["## Totals", "", f"- Wrong positions accepted over every run in this report (master, plain, teleports, caves): "
              f"{wrong_acc + sum(1 for sd in (1, 2) for r in R[('plain', sd, 'new')]['rows'] if r['acc'] and r['err'] is not None and r['err'] > FAIL_PX) + lay['wrong']}.", ""]
    return R


def run_real(args, lines):
    lp = ROOT / "tools" / "dataset" / "labels.json"
    if not lp.exists():
        lines += ["## Real labelled set", "", "`tools/dataset/labels.json` not present - no real data yet.", ""]
        return
    labels = json.loads(lp.read_text())
    info = load_info(2)
    calib = Calib()
    cj = CV_DIR / "calib.json"
    if cj.exists():
        calib = Calib(**json.loads(cj.read_text()))
    m = Matcher(info, Params(repr=args.repr, sigma=args.sigma), calib, coarse_z=args.coarse_z, threads=args.threads)
    rows = []
    for name, lab in labels.items():
        p = lp.parent / "raw" / name
        fr = cv2.imread(str(p))
        if fr is None:
            continue
        px, py = lab["x_pos"] + info.origin[0], lab["y_pos"] + info.origin[1]
        s = lab.get("s", calib.s)
        g = m.global_match(fr, s=s)
        rng = np.random.default_rng(len(rows))
        off = rng.uniform(-1, 1, 2) * 20
        l = m.local_match(fr, (px + off[0], py + off[1]), 50, s=s)
        rows.append((name, np.hypot(g.px0 - px, g.py0 - py), g.score, g.margin, np.hypot(l.px0 - px, l.py0 - py), l.score, g.ms, l.ms))
    if not rows:
        lines += ["## Real labelled set", "", "labels.json present but no readable images in tools/dataset/raw/.", ""]
        return
    eg, el = [r[1] for r in rows], [r[4] for r in rows]
    lines += ["## Real labelled set", "", f"{len(rows)} labelled frames, calibration {calib}.", "",
              "| Mode | median px | p90 px | failure |", "|---|---|---|---|",
              f"| global | {fmt(stats(eg))} |", f"| local | {fmt(stats(el))} |", "",
              f"Timing: global {np.mean([r[6] for r in rows]):.0f} ms, local {np.mean([r[7] for r in rows]):.1f} ms.", "",
              "| frame | global err | score | margin | local err | score |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r[0]} | {r[1]:.1f} | {r[2]:.2f} | {r[3]:.2f} | {r[4]:.1f} | {r[5]:.2f} |")
    lines.append("")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, default=300)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--repr", default="lcn")
    ap.add_argument("--sigma", type=float, default=1.2)
    ap.add_argument("--coarse-z", type=int, default=-2)
    ap.add_argument("--s-err", type=float, default=0.02)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--quick", action="store_true", help="skip timing/scale/tracking sections")
    ap.add_argument("--real-only", action="store_true")
    ap.add_argument("--scenarios", action="store_true", help="state-machine scenario evaluation -> data/cv/scenario_report.md")
    ap.add_argument("--seeds", type=int, default=3, help="master scenario seeds")
    ap.add_argument("--tele-seeds", type=int, default=2)
    ap.add_argument("--cave-seeds", type=int, default=3)
    ap.add_argument("--legacy", action="store_true", help="also run the old 4 Hz tracker for comparison")
    ap.add_argument("--no-budget", action="store_true", help="skip the serial CPU-budget re-runs")
    ap.add_argument("--no-screen", action="store_true", help="skip the cheap-test classifier section")
    ap.add_argument("--out", default=str(CV_DIR / "eval_report.md"))
    a = ap.parse_args()
    if a.scenarios:
        ls = []
        run_scenarios(a, ls)
        out = CV_DIR / "scenario_report.md"
        out.write_text("\n".join(ls))
        print("\n".join(ls))
        return
    lines = ["# CV evaluation report", "", f"Generated {time.strftime('%Y-%m-%d %H:%M')} by tools/cv_eval.py. "
             "Synthetic frames reuse the reference imagery, so synthetic numbers are optimistic (real minimap rendering, "
             "lighting and overlays will differ).", ""]
    if not a.real_only:
        run_synthetic(a, lines)
        if not a.quick:
            run_timing(a, lines)
            run_scale(a, lines)
            run_tracking(a, lines)
    run_real(a, lines)
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text("\n".join(lines))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
