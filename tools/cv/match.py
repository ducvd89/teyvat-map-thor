"""Map matching: local tracking, global relocalisation, feature fallback, scale calibration.

All positions returned are continuous zoom-0 pixels (px0, py0); use MapInfo.to_pos for x_pos/y_pos.
"""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

import cv2
import numpy as np

from .mapdata import Level, MapInfo, level_geom
from .preprocess import Calib, Params, Template, build_prepped, make_template, represent


@dataclass
class MatchResult:
    ok: bool = False
    px0: float = 0.0
    py0: float = 0.0
    score: float = 0.0          # peak NCC
    second: float = 0.0         # best score outside the NMS radius of the peak
    z: int = 0                  # level the match was done at
    ms: float = 0.0
    s: float | None = None
    candidates: list = field(default_factory=list)   # [(px0, py0, score)]
    heat: np.ndarray | None = None
    tmpl: Template | None = None
    extra: dict = field(default_factory=dict)

    @property
    def margin(self):
        return self.score - self.second

    @property
    def confidence(self):
        """0..1: needs a high peak that clearly stands out from the runner-up."""
        return float(np.clip((self.score - 0.25) / 0.45, 0, 1) * np.clip(self.margin / 0.08, 0, 1))


def level_for_scale(s: float, zmin: int = -3, zmax: int = 0) -> int:
    """Finest useful level so that the template is down-sampled (not up-sampled) onto the map."""
    return int(np.clip(np.floor(np.log2(max(s, 1e-3)) + 1e-6), zmin, zmax))


def _parabola(a, b, c):
    d = a - 2 * b + c
    return 0.0 if abs(d) < 1e-9 else float(np.clip(0.5 * (a - c) / d, -1, 1))


def refine_peak(res: np.ndarray, iy: int, ix: int):
    """Sub-pixel peak position via 1-D parabolas (clamped at borders)."""
    h, w = res.shape
    dx = dy = 0.0
    if 0 < ix < w - 1:
        dx = _parabola(res[iy, ix - 1], res[iy, ix], res[iy, ix + 1])
    if 0 < iy < h - 1:
        dy = _parabola(res[iy - 1, ix], res[iy, ix], res[iy + 1, ix])
    return ix + dx, iy + dy


def second_peak(res: np.ndarray, iy: int, ix: int, rad: int) -> float:
    y0, y1 = max(0, iy - rad), min(res.shape[0], iy + rad + 1)
    x0, x1 = max(0, ix - rad), min(res.shape[1], ix + rad + 1)
    # exclude the disk around the peak without copying the whole map
    sub = res[y0:y1, x0:x1].copy()
    yy, xx = np.ogrid[y0:y1, x0:x1]
    keep_out = (yy - iy) ** 2 + (xx - ix) ** 2 <= rad * rad
    saved = res[y0:y1, x0:x1].copy()
    res[y0:y1, x0:x1][keep_out] = -1.0
    v = float(res.max())
    res[y0:y1, x0:x1] = saved
    return max(v, -1.0)


class Matcher:
    def __init__(self, info: MapInfo, params: Params = Params(), calib: Calib = Calib(),
                 coarse_z: int = -2, local_z: int | None = None, threads: int = 8):
        self.info, self.p, self.calib = info, params, calib
        self.coarse_z, self.local_z_fixed, self.threads = coarse_z, local_z, threads
        self._prep: dict = {}
        self._lv: dict = {}

    # ------------------------------------------------------------------ data
    def prepped(self, z: int) -> np.ndarray:
        if z not in self._prep:
            self._prep[z] = build_prepped(self.info, z, self.p)
        return self._prep[z]

    def level(self, z: int) -> Level:
        if z not in self._lv:
            self._lv[z] = level_geom(self.info, z)
        return self._lv[z]

    def local_level(self, s: float) -> int:
        return self.local_z_fixed if self.local_z_fixed is not None else level_for_scale(s, -2, 0)

    def template(self, frame, z: int, s: float, calib: Calib | None = None) -> Template:
        return make_template(frame, calib or self.calib, z, self.p, s=s)

    # ------------------------------------------------------------------ (a) local
    def local_match(self, frame=None, prior=None, radius_z0: float = 60.0, s: float | None = None,
                    tmpl: Template | None = None, z: int | None = None, keep_heat: bool = False,
                    ref=None) -> MatchResult:
        """Masked NCC in a window around prior=(px0, py0) (template-centre search radius in z0 px).
        ref=(prepared_patch, Level) matches against another reference (a floor overlay) instead of the surface."""
        t0 = time.perf_counter()
        s = self.calib.s if s is None else s
        z = self.local_level(s) if z is None else z
        tm = tmpl if tmpl is not None else self.template(frame, z, s)
        lv, P = (ref[1], ref[0]) if ref is not None else (self.level(z), self.prepped(z))
        T = tm.T
        cu, cv_ = lv.from_z0(*prior)
        rad = int(np.ceil(radius_z0 * lv.f))
        x0, y0 = int(np.floor(cu - T / 2)) - rad, int(np.floor(cv_ - T / 2)) - rad
        n = T + 2 * rad + 1
        res, ox, oy = self._ncc_window(P, tm, x0, y0, n, n)
        return self._finish(res, ox, oy, tm, lv, z, t0, keep_heat, s)

    def _ncc_window(self, P, tm: Template, x0, y0, w, h):
        H, W = P.shape
        xa, ya, xb, yb = max(0, x0), max(0, y0), min(W, x0 + w), min(H, y0 + h)
        if xb - xa < tm.T or yb - ya < tm.T:
            return None, xa, ya
        win = np.asarray(P[ya:yb, xa:xb]).astype(np.float32)
        win -= 128.0
        res = cv2.matchTemplate(win, tm.img, cv2.TM_CCORR_NORMED, mask=tm.mask)
        np.nan_to_num(res, copy=False, nan=0.0, posinf=0.0, neginf=0.0)
        return res, xa, ya

    def _finish(self, res, ox, oy, tm, lv, z, t0, keep_heat, s) -> MatchResult:
        if res is None:
            return MatchResult(False, z=z, ms=(time.perf_counter() - t0) * 1e3, s=s)
        iy, ix = np.unravel_index(int(res.argmax()), res.shape)
        score = float(res[iy, ix])
        nms = max(3, int(tm.T / 6))
        sec = second_peak(res, iy, ix, nms)
        fx, fy = refine_peak(res, iy, ix)
        u, v = ox + fx + tm.T / 2.0, oy + fy + tm.T / 2.0
        px0, py0 = lv.to_z0(u, v)
        return MatchResult(True, px0, py0, score, sec, z, (time.perf_counter() - t0) * 1e3, s,
                           [(px0, py0, score)], res if keep_heat else None, tm)

    # ------------------------------------------------------------------ (b) global
    def coarse_search(self, tm: Template, topk: int = 5, region=None, threads: int | None = None):
        """Whole-land masked NCC at the coarse level -> list of (score, u, v) level-px template centres."""
        z = self.coarse_z
        P = self.prepped(z)
        lv = self.level(z)
        H, W = P.shape
        threads = self.threads if threads is None else threads
        T = tm.T
        # split into horizontal stripes processed in parallel (cv2 releases the GIL)
        nstripe = max(1, threads)
        rows = H - T + 1
        step = int(np.ceil(rows / nstripe))
        jobs = [(y, min(rows, y + step)) for y in range(0, rows, step)]

        def run(job):
            ya, yb = job
            res, ox, oy = self._ncc_window(P, tm, 0, ya, W, yb - ya + T - 1)
            return res, ya

        if len(jobs) == 1:
            outs = [run(jobs[0])]
        else:
            with ThreadPoolExecutor(len(jobs)) as ex:
                outs = list(ex.map(run, jobs))
        res = np.concatenate([o[0] for o in outs], axis=0) if len(outs) > 1 else outs[0][0]
        cands = []
        nms = int(max(T * 0.6, 6))
        for _ in range(topk):
            iy, ix = np.unravel_index(int(res.argmax()), res.shape)
            sc = float(res[iy, ix])
            if sc <= 0.05:
                break
            fx, fy = refine_peak(res, iy, ix)
            cands.append((sc, fx + T / 2.0, fy + T / 2.0))
            y0, y1, x0, x1 = max(0, iy - nms), iy + nms + 1, max(0, ix - nms), ix + nms + 1
            res[y0:y1, x0:x1] = -1.0
        return cands

    def global_match(self, frame, s: float | None = None, topk: int = 8, refine_radius_z0: float = 24.0,
                     calib: Calib | None = None, threads: int | None = None) -> MatchResult:
        t0 = time.perf_counter()
        s = self.calib.s if s is None else s
        calib = calib or self.calib
        zc = self.coarse_z
        zf = self.local_level(s)
        tm_c = make_template(frame, calib, zc, self.p, s=s)
        cands = self.coarse_search(tm_c, topk, threads=threads)
        lvc = self.level(zc)
        tm_f = make_template(frame, calib, zf, self.p, s=s) if zf != zc else tm_c
        best, allc = None, []
        for sc, u, v in cands:
            prior = lvc.to_z0(u, v)
            rad = max(refine_radius_z0, 2.5 * 2 ** (-zc))   # a few coarse pixels
            r = self.local_match(prior=prior, radius_z0=rad, s=s, tmpl=tm_f, z=zf)
            if not r.ok:
                continue
            r.extra["coarse_score"] = sc
            allc.append((r.px0, r.py0, r.score))
            if best is None or r.score > best.score:
                best = r
        if best is None:
            return MatchResult(False, ms=(time.perf_counter() - t0) * 1e3, s=s, tmpl=tm_f)
        # runner-up among *distinct* candidates (>= template radius apart) for the ratio test
        others = [c[2] for c in allc if np.hypot(c[0] - best.px0, c[1] - best.py0) > 0.3 * tm_f.T / 2 ** zf]
        best.second = max([best.second] * 0 + others + [0.0]) if others else best.second
        best.candidates = sorted(allc, key=lambda c: -c[2])
        best.ms = (time.perf_counter() - t0) * 1e3
        return best

    # ------------------------------------------------------------------ (b2) teleport targets
    def target_match(self, frame, targets_xy: np.ndarray, s: float | None = None, topk: int = 5,
                     win_z0: float = 60.0, z_t: int = -3, refine_radius_z0: float = 24.0,
                     calib: Calib | None = None, threads: int | None = None) -> MatchResult:
        """Score a tiny coarse-level window (+-win_z0 z0 px) around every target, refine the top `topk`."""
        t0 = time.perf_counter()
        s = self.calib.s if s is None else s
        calib = calib or self.calib
        threads = self.threads if threads is None else threads
        if len(targets_xy) == 0:
            return MatchResult(False, s=s)
        zf = self.local_level(s)
        tm_t = make_template(frame, calib, z_t, self.p, s=s)
        P, lv = self.prepped(z_t), self.level(z_t)
        T = tm_t.T
        rad = int(np.ceil(win_z0 * lv.f))

        def one(i):
            u, v = lv.from_z0(*targets_xy[i])
            x0, y0 = int(np.floor(u - T / 2)) - rad, int(np.floor(v - T / 2)) - rad
            n = T + 2 * rad + 1
            res, ox, oy = self._ncc_window(P, tm_t, x0, y0, n, n)
            if res is None:
                return -1.0, 0.0, 0.0
            iy, ix = np.unravel_index(int(res.argmax()), res.shape)
            return float(res[iy, ix]), ox + ix + T / 2.0, oy + iy + T / 2.0

        idx = range(len(targets_xy))
        if threads > 1 and len(targets_xy) > 32:
            with ThreadPoolExecutor(threads) as ex:
                out = list(ex.map(one, idx))
        else:
            out = [one(i) for i in idx]
        order = sorted(range(len(out)), key=lambda i: -out[i][0])[:topk]
        tm_f = make_template(frame, calib, zf, self.p, s=s) if zf != z_t else tm_t
        best, allc = None, []
        for i in order:
            sc, u, v = out[i]
            if sc <= 0.05:
                continue
            prior = lv.to_z0(u, v)
            r = self.local_match(prior=prior, radius_z0=refine_radius_z0, s=s, tmpl=tm_f, z=zf)
            if not r.ok:
                continue
            r.extra["coarse_score"] = sc
            r.extra["target"] = int(i)
            allc.append((r.px0, r.py0, r.score))
            if best is None or r.score > best.score:
                best = r
        if best is None:
            return MatchResult(False, ms=(time.perf_counter() - t0) * 1e3, s=s, tmpl=tm_f)
        others = [c[2] for c in allc if np.hypot(c[0] - best.px0, c[1] - best.py0) > 0.3 * tm_f.T / 2 ** zf]
        best.second = max(others) if others else best.second
        best.candidates = sorted(allc, key=lambda c: -c[2])
        best.ms = (time.perf_counter() - t0) * 1e3
        return best

    # ------------------------------------------------------------------ (c) features
    def feature_match(self, frame, cands: list, s: float | None = None, z: int | None = None,
                      method: str = "orb", margin: float = 1.6) -> MatchResult:
        """ORB/AKAZE + RANSAC on candidate regions of the prepared map; returns the best by inliers."""
        t0 = time.perf_counter()
        s = self.calib.s if s is None else s
        z = self.local_level(s) if z is None else z
        lv, P = self.level(z), self.prepped(z)
        tm = self.template(frame, z, s)
        timg = np.clip(tm.img + 128, 0, 255).astype(np.uint8)
        tmask = cv2.erode((tm.mask > 0).astype(np.uint8) * 255, np.ones((5, 5), np.uint8))
        det = cv2.ORB_create(1500, 1.2, 4, 15, 0, 2, cv2.ORB_HARRIS_SCORE, 31, 15) if method == "orb" \
            else cv2.AKAZE_create()
        kt, dt = det.detectAndCompute(timg, tmask)
        best = MatchResult(False, z=z, s=s)
        if dt is None or len(kt) < 8:
            best.ms = (time.perf_counter() - t0) * 1e3
            return best
        norm = cv2.NORM_HAMMING
        bf = cv2.BFMatcher(norm)
        for (px, py, *_rest) in cands:
            u, v = lv.from_z0(px, py)
            half = int(tm.T * margin / 2)
            x0, y0 = int(u) - half, int(v) - half
            xa, ya = max(0, x0), max(0, y0)
            xb, yb = min(P.shape[1], x0 + 2 * half), min(P.shape[0], y0 + 2 * half)
            if xb - xa < tm.T or yb - ya < tm.T:
                continue
            win = np.asarray(P[ya:yb, xa:xb])
            kw, dw = det.detectAndCompute(win, None)
            if dw is None or len(kw) < 8:
                continue
            ms_ = bf.knnMatch(dt, dw, k=2)
            good = [m[0] for m in ms_ if len(m) == 2 and m[0].distance < 0.8 * m[1].distance]
            if len(good) < 8:
                continue
            src = np.float32([kt[m.queryIdx].pt for m in good])
            dst = np.float32([kw[m.trainIdx].pt for m in good])
            A, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=3.0)
            if A is None:
                continue
            sc = float(np.hypot(A[0, 0], A[1, 0]))
            rot = float(np.degrees(np.arctan2(A[1, 0], A[0, 0])))
            if not (0.9 < sc < 1.1) or abs(rot) > 6:
                continue
            n_in = int(inl.sum())
            if n_in > best.extra.get("inliers", 0):
                c = A @ np.array([tm.T / 2.0, tm.T / 2.0, 1.0])
                pz0 = lv.to_z0(xa + c[0], ya + c[1])
                best = MatchResult(n_in >= 10, pz0[0], pz0[1], min(1.0, n_in / 40.0), 0.0, z,
                                   0.0, s, [(pz0[0], pz0[1], n_in)], None, tm, {"inliers": n_in})
        best.ms = (time.perf_counter() - t0) * 1e3
        return best

    # ------------------------------------------------------------------ (d) scale calibration
    def _refine_scale(self, frame, calib, s0, pos, rad0, step, z=None, iters=3):
        """Local 1-D search of the scale around s0 (position re-estimated at each step)."""
        cur, best = s0, None
        for it in range(iters):
            fine = np.exp(np.linspace(np.log(cur) - np.log(step) * 0.9, np.log(cur) + np.log(step) * 0.9, 7))
            rs = []
            zz0 = z if z is not None else self.local_level(cur)
            for s in fine:
                # common working level for all scales of one iteration so scores are comparable
                r = self.local_match(frame, pos, rad0 if it == 0 else 10.0, s=s, z=zz0)
                rs.append((s, r))
            sc = np.array([r.score if r.ok else 0 for _, r in rs])
            k = int(sc.argmax())
            ls = np.log([s for s, _ in rs])
            if 0 < k < len(rs) - 1:
                cur = float(np.exp(ls[k] + _parabola(sc[k - 1], sc[k], sc[k + 1]) * (ls[1] - ls[0])))
            else:
                cur = float(rs[k][0])
            best = rs[k][1]
            pos = (best.px0, best.py0)
            step = step ** 0.35
        zz = z if z is not None else self.local_level(cur)
        final = self.local_match(frame, pos, 12.0, s=cur, z=zz)
        final.s = cur
        return cur, final

    def estimate_scale(self, frame, s_range=(0.3, 1.5), n_coarse: int = 14, hint=None,
                       hint_radius_z0: float = 400.0, calib: Calib | None = None, z: int | None = None,
                       n_verify: int = 5):
        """Multi-scale search; returns (s_best, MatchResult-at-best, table[(s, score)]).

        Without hint: coarse global search per scale on the coarse level; the best few distinct
        (scale, position) hypotheses are then refined at the working level and the one with the
        highest fine score wins.  With hint=(px0,py0): a local scan over the scale grid only.
        """
        calib = calib or self.calib
        grid = np.exp(np.linspace(np.log(s_range[0]), np.log(s_range[1]), n_coarse))
        step = float(grid[1] / grid[0])
        if hint is not None:
            table = []
            for s in grid:
                zz = z if z is not None else self.local_level(s)
                r = self.local_match(frame, hint, hint_radius_z0, s=s, z=zz)
                table.append((s, r.score if r.ok else 0.0))
            s0 = max(table, key=lambda t: t[1])[0]
            cur, final = self._refine_scale(frame, calib, s0, hint, hint_radius_z0, step, z)
            return cur, final, table

        def one(s):
            tm = make_template(frame, calib, self.coarse_z, self.p, s=s)
            return s, self.coarse_search(tm, topk=3, threads=1)
        with ThreadPoolExecutor(min(self.threads, len(grid))) as ex:
            outs = list(ex.map(one, grid))
        lvc = self.level(self.coarse_z)
        table = [(s, c[0][0] if c else 0.0) for s, c in outs]
        hyps = sorted(((c[0], s, c[1], c[2]) for s, cs in outs for c in cs), reverse=True)
        picked = []
        for sc, s, u, v in hyps:                       # distinct positions only
            pz = lvc.to_z0(u, v)
            if all(np.hypot(pz[0] - q[2][0], pz[1] - q[2][1]) > 30 for q in picked):
                picked.append((sc, s, pz))
            if len(picked) >= n_verify:
                break
        rad0 = 2.5 * 2 ** (-self.coarse_z) + 6
        best = None
        for sc, s, pz in picked:
            cur, final = self._refine_scale(frame, calib, s, pz, rad0, step, z)
            if final.ok and (best is None or final.score > best[1].score):
                best = (cur, final)
        if best is None:
            return picked[0][1], MatchResult(False), table
        return best[0], best[1], table
