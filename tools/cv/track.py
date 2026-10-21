"""State-machine tracker, the reference for the Kotlin port (PLAN.md section 6.2).

States: TRACKING / LOADING / RELOCATING / DOMAIN / HIDDEN / LOST / OFF.
Drive it in simulated or real time:

    trk = Tracker(matcher, calib, cfg, layers=LayerIndex, refs=FloorRefs, targets=Targets)
    trk.start(prior=None)
    loop:  pose = trk.update(frame, t);  sleep(trk.next_check_delay())

Every check runs the cheap tests first (thumbnail, ring, black interior); matching only runs as far as the state
needs.  Counters in ``trk.stats`` prove the budget.  All thresholds are in ``TrackCfg``.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

import numpy as np

from . import screen
from .heading import HeadingSmoother, estimate_heading
from .match import Matcher
from .preprocess import Calib, crop_minimap

STATES = ("TRACKING", "LOADING", "RELOCATING", "DOMAIN", "HIDDEN", "LOST", "OFF")


@dataclass
class TrackCfg:
    # ---- check delays (seconds)
    d_tracking: float = 1.0       # 1 Hz
    d_fast: float = 0.5           # 2 Hz when the estimated speed is high
    d_still: float = 2.0          # 0.5 Hz after still_after seconds with an unchanged minimap
    d_loading: float = 1.0
    d_relocating: float = 0.5     # 2 Hz
    d_domain: float = 5.0         # 0.2 Hz
    d_hidden: float = 2.0         # 0.5 Hz
    d_lost: float = 3.0           # one full search per 3 s
    d_pending: float = 1.0        # confirming a first uniform / black observation
    d_layer_pending: float = 0.5  # confirming a layer switch
    fast_speed_frac: float = 0.12  # "fast" = speed > frac * (minimap radius in z0 px) per second
    still_after: float = 10.0
    still_verify_every: int = 5   # an unchanged minimap still gets a real local search every Nth check
    # ---- state transitions
    uniform_checks: int = 2       # uniform thumbnails in a row -> LOADING
    black_checks: int = 2         # ring + black interior in a row -> DOMAIN
    max_miss: int = 3             # consecutive local misses -> LOST
    reloc_max: float = 10.0       # RELOCATING timeout -> LOST
    reloc_agree: float = 30.0     # z0 px: two relocation results closer than this agree
    # ---- local tracking
    vmax: float = 45.0            # z0 px / s, plausible max speed (gate)
    local_min_radius: float = 24.0
    local_max_radius: float = 90.0
    tau_local: float = 0.40
    tau_margin: float = 0.02
    alpha: float = 0.85
    beta: float = 0.25
    # ---- relocation search order
    reloc_order: str = "targets"  # "targets" (1 last pos, 2 targets, 3 whole map) | "fullmap" (old behaviour)
    reloc_last_radius: float = 150.0
    tau_reloc_local: float = 0.55
    tau_reloc_local_margin: float = 0.10
    tau_global: float = 0.50
    tau_global_margin: float = 0.08
    target_win: float = 60.0
    target_topk: int = 5
    domain_near: float = 3000.0
    full_after_fails: int = 1     # whole-map scan only after this many RELOCATING frames in a row found nothing in steps 1-2
                                  # (skips the washed-out first frames of a fade-in); LOST scans immediately
    skip_flat: bool = True        # no relocation search on a flat (open-sea) minimap
    confirm_radius: float = 40.0
    # ---- layers
    layers_on: bool = True
    tau_layer_drop: float = 0.10  # score this far below the running average triggers a layer check
    layer_period: float = 5.0     # safety-net check interval inside a zone (s)
    layer_bias: float = 0.03      # current layer gets this bonus (hysteresis on top of the 2-in-a-row)
    layer_agree: int = 2
    layer_override: float = 0.15  # while a switch is pending, reject the current layer's result if it is this much worse
    avg_alpha: float = 0.3


@dataclass
class Pose:
    state: str = "OFF"
    px0: float = 0.0
    py0: float = 0.0
    x_pos: float = 0.0
    y_pos: float = 0.0
    heading: float | None = None
    confidence: float = 0.0
    score: float = 0.0
    mode: str = ""              # local / still / coast / full / targets / last / confirm / none
    ms: float = 0.0
    accepted: bool = False      # a position measurement was accepted on this check
    group_id: int | None = None
    floor_id: int | None = None
    path: str = ""              # relocation path that produced a lock: last / targets / full
    delay: float = 1.0


@dataclass
class Stats:
    checks: int = 0
    full: int = 0               # whole-map searches
    target: int = 0             # teleport-target batch searches
    local: int = 0              # local matches (incl. relocation step 1, confirmations, layer candidates)
    layer_checks: int = 0
    cheap: int = 0              # checks that ran no matching at all
    cpu_ms: float = 0.0         # process CPU time inside update()
    wall_ms: float = 0.0
    cheap_ms: float = 0.0       # time spent in the cheap tests alone
    by_state: dict = field(default_factory=dict)


class Tracker:
    def __init__(self, matcher: Matcher, calib: Calib | None = None, cfg: TrackCfg = TrackCfg(),
                 layers=None, refs=None, targets=None):
        self.m, self.calib, self.cfg = matcher, calib or matcher.calib, cfg
        self.layers, self.refs, self.targets = layers, refs, targets
        self.stats = Stats()
        self.episodes: list[dict] = []
        self.reset()

    def reset(self):
        self.state = "OFF"
        self.pos = None
        self.vel = np.zeros(2)
        self.t_pos = None
        self.miss = 0
        self.n_uni = 0
        self.n_black = 0
        self.pending = None            # relocation candidate (x, y, path)
        self.pending_t = 0.0
        self.n_fail = 0                # consecutive RELOCATING frames with no candidate from steps 1-2
        self.t_reloc = None
        self.t_last_full = -1e9
        self.hidden_ret = "track"
        self.pre_loading = "TRACKING"
        self.reloc_from = "LOADING"
        self.ref_sig = None
        self.t_changed = 0.0
        self.still_n = 0
        self.hs = HeadingSmoother(0.5)
        self.heading = None
        self.layer = None              # Floor or None (surface)
        self.layer_pending = None      # (layer or None, count)
        self.was_zone = False
        self.t_layer = 0.0
        self.avg = None
        self.layer_after_lock = False
        self._ep = None
        self._next = 1.0

    def start(self, prior=None):
        if prior is not None:
            self._set_pos(np.array(prior, float), 0.0)
            self.state = "TRACKING"
        else:
            self.state = "RELOCATING"
        self.t_reloc = None

    # ------------------------------------------------------------------ helpers
    def _set_pos(self, p, t):
        self.pos, self.t_pos, self.vel = p.copy(), t, np.zeros(2)
        self.miss = 0

    def _lock(self, p, t, crop, path):
        self._set_pos(np.array(p, float), t)
        self.state = "TRACKING"
        self.pending = None
        self.ref_sig = screen.minimap_sig(crop)
        self.t_changed = t
        self.still_n = 0
        self.layer, self.layer_pending, self.avg = None, None, None
        self.layer_after_lock = bool(self.layers is not None and self.cfg.layers_on and self.layers.floors_at(*p))
        self.was_zone = False
        if self._ep is not None:
            self._ep.update(t_lock=t, path=path, px0=float(p[0]), py0=float(p[1]))
            self.episodes.append(self._ep)
            self._ep = None

    def _enter_reloc(self, t, src):
        self.state = "RELOCATING"
        self.t_reloc = t
        self.pending = None
        self.n_fail = 0
        self.reloc_from = src
        self._ep = dict(t0=t, src=src, cpu_ms=0.0, checks=0, t_lock=None, path=None)

    def _end_episode(self):
        if self._ep is not None:
            self.episodes.append(self._ep)
            self._ep = None

    def speed(self):
        return float(np.hypot(*self.vel))

    def next_check_delay(self) -> float:
        c = self.cfg
        if self.n_uni == 1 or self.n_black == 1:
            return c.d_pending
        s = self.state
        if s == "TRACKING":
            if self.layer_pending is not None or self.layer_after_lock:
                return c.d_layer_pending
            if self._still_now:
                return c.d_still
            if self.speed() > c.fast_speed_frac * self.calib.r / self.calib.s:
                return c.d_fast
            return c.d_tracking
        return {"LOADING": c.d_loading, "RELOCATING": c.d_relocating, "DOMAIN": c.d_domain, "HIDDEN": c.d_hidden,
                "LOST": c.d_lost, "OFF": 1.0}[s]

    _still_now = False

    # ------------------------------------------------------------------ main entry
    def update(self, frame, t: float) -> Pose:
        w0, c0 = time.perf_counter(), time.process_time()
        st = self.stats
        n_local0, n_full0, n_tgt0, n_lay0 = st.local, st.full, st.target, st.layer_checks
        state0 = self.state
        pose = self._update(frame, t)
        st.checks += 1
        st.by_state[state0] = st.by_state.get(state0, 0) + 1
        if st.local == n_local0 and st.full == n_full0 and st.target == n_tgt0:
            st.cheap += 1
        cpu = (time.process_time() - c0) * 1e3
        st.cpu_ms += cpu
        st.wall_ms += (time.perf_counter() - w0) * 1e3
        if self._ep is not None:
            self._ep["cpu_ms"] += cpu
            self._ep["checks"] += 1
        elif self.episodes and self.episodes[-1].get("t_lock") == t:
            self.episodes[-1]["cpu_ms"] += cpu
            self.episodes[-1]["checks"] += 1
        pose.ms = (time.perf_counter() - w0) * 1e3
        pose.delay = self.next_check_delay()
        return pose

    def _update(self, frame, t) -> Pose:
        cfg, st = self.cfg, self.stats
        th0 = time.perf_counter()
        if self.state == "OFF" or (self.state == "RELOCATING" and self.t_reloc is None):
            self._enter_reloc(t, "START")
        # ---- 1. thumbnail
        thumb = screen.thumbnail(frame)
        # a uniform thumbnail with the minimap ring still on screen is a dark/bright HUD scene, not a loading screen
        if screen.is_loading_screen(thumb) and not screen.ring_present(frame, self.calib):
            self.n_uni += 1
            self.n_black = 0
            st.cheap_ms += (time.perf_counter() - th0) * 1e3
            if self.n_uni >= cfg.uniform_checks and self.state != "LOADING":
                if self.state != "RELOCATING":
                    self.pre_loading = self.state
                else:
                    self.pre_loading = self.reloc_from
                self._end_episode()
                self.state = "LOADING"
                self.pending = None
            return self._pose("none", 0.0, False)
        self.n_uni = 0
        if self.state == "LOADING":
            src = {"DOMAIN": "DOMAIN", "TRACKING": "TRACKING"}.get(self.pre_loading, "LOADING")
            self._enter_reloc(t, src)
        # ---- 2. ring
        ring = screen.ring_present(frame, self.calib)
        if not ring:
            self.n_black = 0
            st.cheap_ms += (time.perf_counter() - th0) * 1e3
            if self.state in ("TRACKING", "DOMAIN", "LOST"):
                self.hidden_ret = {"TRACKING": "track", "DOMAIN": "relocate", "LOST": "lost"}[self.state]
                self.state = "HIDDEN"
            return self._pose("none", 0.0, False)
        # ---- 3. black interior (domain)
        crop = crop_minimap(frame, self.calib)
        if screen.minimap_is_black(crop):
            self.n_black += 1
            st.cheap_ms += (time.perf_counter() - th0) * 1e3
            if self.n_black >= cfg.black_checks and self.state != "DOMAIN":
                self._end_episode()
                self.state = "DOMAIN"
                self.pending = None
            return self._pose("none", 0.0, False)
        self.n_black = 0
        st.cheap_ms += (time.perf_counter() - th0) * 1e3
        # ---- 4. matching, as much as the state needs
        s = self.state
        if s == "DOMAIN":                       # interior no longer black without a loading screen seen: re-locate
            self._enter_reloc(t, "DOMAIN")
            s = "RELOCATING"
        elif s == "HIDDEN":
            ret = self.hidden_ret
            if ret == "track" and self.pos is not None:
                return self._hidden_return(frame, crop, t)
            if ret == "lost":
                self.state, s = "LOST", "LOST"
            else:
                self._enter_reloc(t, "HIDDEN")
                s = "RELOCATING"
        if s == "TRACKING":
            return self._track(frame, crop, t)
        if s == "RELOCATING":
            if self.t_reloc is not None and t - self.t_reloc >= cfg.reloc_max and self.pending is None:
                self._end_episode()
                self.state = "LOST"
                self.reloc_from = "LOST"
                return self._pose("none", 0.0, False)
            return self._relocate(frame, crop, t)
        if s == "LOST":
            if t - self.t_last_full < cfg.d_lost - 1e-6:
                return self._pose("none", 0.0, False)
            return self._relocate(frame, crop, t)
        return self._pose("none", 0.0, False)

    # ------------------------------------------------------------------ pose
    def _pose(self, mode, score, accepted, conf=0.0, path="") -> Pose:
        p = self.pos if self.pos is not None else np.zeros(2)
        x, y = self.m.info.to_pos(p[0], p[1])
        L = self.layer
        return Pose(self.state, float(p[0]), float(p[1]), float(x), float(y), self.heading, float(conf), float(score),
                    mode, 0.0, accepted, L.group_id if L else None, L.floor_id if L else None, path)

    def _update_heading(self, crop):
        hd, _ = estimate_heading(crop)
        self.heading = self.hs.update(hd)

    # ------------------------------------------------------------------ HIDDEN -> minimap back
    def _hidden_return(self, frame, crop, t):
        """Local search around the last position (wide); on a miss -> RELOCATING (search order incl. whole map)."""
        cfg = self.cfg
        s = self.calib.s
        z = self.m.local_level(s)
        ref = self._ref(self.layer, z)
        self.stats.local += 1
        r = self.m.local_match(frame, tuple(self.pos), cfg.local_max_radius * 1.5, s=s, ref=ref)
        if r.ok and r.score >= cfg.tau_local and r.margin >= cfg.tau_margin:
            self.state = "TRACKING"
            self._set_pos(np.array([r.px0, r.py0]), t)
            self.ref_sig = screen.minimap_sig(crop)
            self.t_changed = t
            self._update_heading(crop)
            return self._pose("local", r.score, True, r.confidence)
        self._enter_reloc(t, "HIDDEN")
        return self._relocate(frame, crop, t)

    # ------------------------------------------------------------------ layers
    def _ref(self, layer, z):
        if layer is None or self.refs is None:
            return None
        return self.refs.get(layer, z)

    def _layer_check(self, frame, center, rad, zone_floors, cur_res, s, z):
        """Local match at `center` against the surface and every floor containing it -> (best_layer, best_res, cur_score)."""
        cfg = self.cfg
        tm = self.m.template(frame, z, s)
        results = {}
        cur_id = self.layer.floor_id if self.layer else None
        cands = [None] + list(zone_floors)
        if self.layer is not None and all(f.floor_id != cur_id for f in zone_floors):
            cands.append(self.layer)
        for L in cands:
            key = L.floor_id if L else None
            if key == cur_id and cur_res is not None:
                results[key] = (L, cur_res)
                continue
            self.stats.local += 1
            r = self.m.local_match(None, tuple(center), rad, s=s, tmpl=tm, z=z, ref=self._ref(L, z))
            results[key] = (L, r)
        best_key, best = None, None
        for key, (L, r) in results.items():
            sc = (r.score if r.ok else 0.0) + (cfg.layer_bias if key == cur_id else 0.0)
            if best is None or sc > best[0]:
                best_key, best = key, (sc, L, r)
        return best_key, best[1], best[2], results

    # ------------------------------------------------------------------ TRACKING
    def _track(self, frame, crop, t):
        cfg, st = self.cfg, self.stats
        s = self.calib.s
        dt = max(1e-3, t - (self.t_pos if self.t_pos is not None else t - 1.0))
        pred = self.pos + self.vel * dt
        # ---- unchanged minimap: skip matching (cheap-only), verify every Nth
        sig = screen.minimap_sig(crop)
        changed = self.ref_sig is None or screen.sig_diff(self.ref_sig, sig) > screen.CFG.chg_thr
        if changed:
            self.t_changed = t
            self.still_n = 0
        else:
            self.still_n += 1
        self._still_now = (t - self.t_changed) >= cfg.still_after
        if not changed and self.miss == 0 and self.still_n % cfg.still_verify_every != 0 and not self.layer_after_lock:
            self.t_pos = t
            self.vel[:] = 0
            self._update_heading(crop)
            return self._pose("still", 0.0, True)
        rad = float(np.clip(cfg.vmax * dt + 10.0 + 8.0 * self.miss, cfg.local_min_radius, cfg.local_max_radius))
        gate = min(cfg.vmax * dt + 10.0 + 8.0 * self.miss, rad)
        z = self.m.local_level(s)
        st.local += 1
        res = self.m.local_match(frame, tuple(pred), rad, s=s, ref=self._ref(self.layer, z))
        ok = res.ok and res.score >= cfg.tau_local and res.margin >= cfg.tau_margin
        if ok and np.hypot(res.px0 - pred[0], res.py0 - pred[1]) > gate:
            ok = False                                  # far match in TRACKING = miss, never a teleport
        pos_est = np.array([res.px0, res.py0]) if ok else pred
        # ---- layer logic (only inside a zone)
        measured = np.array([res.px0, res.py0]) if ok else None
        score = res.score if res.ok else 0.0
        zone = self.layers.floors_at(*pos_est) if (self.layers is not None and cfg.layers_on) else []
        if not zone:
            self.layer, self.layer_pending, self.avg, self.was_zone = None, None, None, False
            self.layer_after_lock = False
        else:
            entered = not self.was_zone
            drop = (not ok) or (self.avg is not None and score < self.avg - cfg.tau_layer_drop)
            periodic = (t - self.t_layer) >= cfg.layer_period
            forced = self.layer_pending is not None or self.layer_after_lock
            if entered or drop or periodic or forced:
                self.layer_after_lock = False
                self.t_layer = t
                st.layer_checks += 1
                key, L, r, results = self._layer_check(frame, pred, rad, zone, res if res.ok else None, s, z)
                cur_id = self.layer.floor_id if self.layer else None
                cur_sc = results[cur_id][1].score if (cur_id in results and results[cur_id][1].ok) else 0.0
                r_ok = r.ok and r.score >= cfg.tau_local and r.margin >= cfg.tau_margin and \
                    np.hypot(r.px0 - pred[0], r.py0 - pred[1]) <= gate
                if key == cur_id or not r_ok:
                    self.layer_pending = None
                else:
                    cnt = (self.layer_pending[1] + 1) if (self.layer_pending and self.layer_pending[0] is L) else 1
                    if cnt >= cfg.layer_agree:                         # switch
                        self.layer, self.layer_pending, self.avg = L, None, None
                        ok, res, score = True, r, r.score
                        measured = np.array([r.px0, r.py0])
                    else:
                        self.layer_pending = (L, cnt)
                        if r.score >= cur_sc + cfg.layer_override:     # current layer is clearly worse: don't trust it
                            ok, measured = False, None
            self.was_zone = True
        # ---- filter update
        if ok:
            self.miss = 0
            self.avg = score if self.avg is None else (1 - cfg.avg_alpha) * self.avg + cfg.avg_alpha * score
            r_ = measured - pred
            newpos = pred + cfg.alpha * r_
            self.vel = self.vel + cfg.beta * r_ / dt
            sp = float(np.hypot(*self.vel))
            if sp > cfg.vmax:
                self.vel *= cfg.vmax / sp
            self.pos, self.t_pos = newpos, t
            self.ref_sig = sig
            self._update_heading(crop)
            return self._pose("local", score, True, res.confidence)
        if not (self.layer_pending is not None):
            self.miss += 1
        self.pos, self.t_pos = pred, t                   # coast on the prediction
        if self.miss >= cfg.max_miss:
            self.state = "LOST"
            self.reloc_from = "LOST"
            self.pending = None
        return self._pose("coast", score, False)

    # ------------------------------------------------------------------ RELOCATING / LOST
    def _accept_local(self, r, strict=True):
        cfg = self.cfg
        if not r.ok:
            return False
        if strict:
            return r.score >= cfg.tau_reloc_local and r.margin >= cfg.tau_reloc_local_margin
        return r.score >= cfg.tau_global and r.margin >= cfg.tau_global_margin

    def _relocate(self, frame, crop, t):
        cfg, st, m = self.cfg, self.stats, self.m
        s = self.calib.s
        if self.state == "LOST":
            self.t_last_full = t
            if self._ep is None:
                self._ep = dict(t0=t, src="LOST", cpu_ms=0.0, checks=0, t_lock=None, path=None)
        if cfg.skip_flat and screen.minimap_is_flat(crop):
            self.pending = None
            return self._pose("flat", 0.0, False)
        # --- 0. confirm a pending candidate (second agreeing result)
        if self.pending is not None:
            px, py, path = self.pending
            slack = 0.6 * cfg.vmax * max(0.0, t - self.pending_t)     # the player keeps moving between the two results
            st.local += 1
            r = m.local_match(frame, (px, py), cfg.confirm_radius + slack, s=s)
            if self._accept_local(r, strict=False) and np.hypot(r.px0 - px, r.py0 - py) <= cfg.reloc_agree + slack:
                self._lock((r.px0, r.py0), t, crop, path)
                self._update_heading(crop)
                return self._pose("confirm", r.score, True, r.confidence, path)
            self.pending = None
        cand = None                                      # (x, y, path, score)
        last = None if self.pos is None else (float(self.pos[0]), float(self.pos[1]))
        order = cfg.reloc_order == "targets"
        # --- 1. last known position
        if order and last is not None:
            st.local += 1
            r = m.local_match(frame, last, cfg.reloc_last_radius, s=s)
            if self._accept_local(r, strict=True):
                cand = (r.px0, r.py0, "last", r.score)
        # --- 2. teleport targets
        if cand is None and order and self.targets is not None and len(self.targets):
            first = "domain" if self.reloc_from == "DOMAIN" else ("statue" if self.reloc_from == "TRACKING" else None)
            prio, rest = self.targets.order(last, first, cfg.domain_near)
            for idx in (prio, rest):
                if len(idx) == 0:
                    continue
                st.target += 1
                r = m.target_match(frame, self.targets.xy[idx], s=s, topk=cfg.target_topk, win_z0=cfg.target_win)
                if self._accept_local(r, strict=False):
                    cand = (r.px0, r.py0, "targets", r.score)
                    break
        # --- 3. whole map
        if cand is None and order and self.state != "LOST" and self.n_fail < cfg.full_after_fails:
            self.n_fail += 1
            return self._pose("none", 0.0, False)
        if cand is None:
            self.n_fail += 1
            st.full += 1
            g = m.global_match(frame, s=s)
            if g.ok and g.score >= cfg.tau_global and g.margin >= cfg.tau_global_margin:
                cand = (g.px0, g.py0, "full", g.score)
        if cand is None:
            return self._pose("none", 0.0, False)
        self.pending = (cand[0], cand[1], cand[2])
        self.pending_t = t
        # first result only: wait for the second frame to agree
        return self._pose(cand[2], cand[3], False)
