#!/usr/bin/env python3
"""Build an offline "pack" of HoYoLAB Genshin interactive map data.

Stdlib only. Resumable (existing non-empty files are skipped), atomic writes,
retries with exponential backoff, global rate limiting.

See tools/README.md for usage and the pack layout.
"""
import argparse
import concurrent.futures as cf
import json
import math
import os
import random
import re
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

PACK_SCHEMA = 1
STATIC = "https://sg-public-api-static.hoyolab.com/common/map_user/ys_obc"
DYNAMIC = "https://sg-public-api.hoyolab.com/common/map_user/ys_obc"
TILE_BASE = "https://act-webstatic.hoyoverse.com/map_manage/map"
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Safari/537.36")
HEADERS = {
    "User-Agent": UA,
    "x-rpc-map_version": "4.5",
    "Origin": "https://act.hoyolab.com",
    "Referer": "https://act.hoyolab.com/",
    "Accept": "*/*",
}
ALL_STEPS = ["meta", "icons", "points", "tiles", "overlays", "details", "media"]
DEFAULT_STEPS = ["meta", "icons", "points", "tiles", "overlays"]
EMPTY_TILE_MAX_BYTES = 64  # blank sea tiles are 38 bytes
CATEGORIES = ["tiles", "icons", "overlays", "maps", "details", "media"]


# --------------------------------------------------------------------------
# infrastructure
# --------------------------------------------------------------------------
class RateLimiter:
    """Global requests/sec limiter (evenly spaced slots). rate<=0 disables."""

    def __init__(self, rate):
        self.interval = 1.0 / rate if rate and rate > 0 else 0.0
        self.lock = threading.Lock()
        self.next_slot = time.monotonic()

    def wait(self):
        if not self.interval:
            return
        with self.lock:
            now = time.monotonic()
            slot = max(self.next_slot, now)
            self.next_slot = slot + self.interval
        delay = slot - now
        if delay > 0:
            time.sleep(delay)

    def penalize(self, seconds):
        """Push every future slot back (used on 429)."""
        with self.lock:
            self.next_slot = max(self.next_slot, time.monotonic()) + seconds


class Progress:
    def __init__(self, label, total, interval=5.0):
        self.label, self.total, self.interval = label, total, interval
        self.done = self.skipped = self.failed = self.bytes = 0
        self.lock = threading.Lock()
        self.t0 = time.monotonic()
        self.last_print = 0.0
        self.new_done = 0

    def tick(self, nbytes=0, skipped=False, failed=False):
        with self.lock:
            self.done += 1
            if skipped:
                self.skipped += 1
            elif failed:
                self.failed += 1
            else:
                self.new_done += 1
                self.bytes += nbytes
            now = time.monotonic()
            if now - self.last_print >= self.interval:
                self.last_print = now
                self._print(now)

    def _print(self, now):
        el = now - self.t0
        rate = self.new_done / el if el > 0 else 0
        remaining = self.total - self.done
        eta = remaining / rate if rate > 0 else float("nan")
        print(f"[{self.label}] {self.done}/{self.total} "
              f"(skipped {self.skipped}, failed {self.failed}) "
              f"{fmt_bytes(self.bytes)} new, {rate:.1f} req/s, ETA {fmt_dur(eta)}",
              flush=True)

    def finish(self):
        with self.lock:
            self._print(time.monotonic())


def fmt_bytes(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def fmt_dur(s):
    if s != s or s == float("inf"):
        return "?"
    s = int(s)
    h, r = divmod(s, 3600)
    m, sec = divmod(r, 60)
    return f"{h}h{m:02d}m" if h else f"{m}m{sec:02d}s"


class NotFound(Exception):
    pass


class Fetcher:
    def __init__(self, api_rate, cdn_rate, retries=6):
        self.api_limit = RateLimiter(api_rate)
        self.cdn_limit = RateLimiter(cdn_rate)
        self.retries = retries
        self.log_lock = threading.Lock()
        self.stats = {"requests": 0, "retries": 0, "r429": 0, "r5xx": 0, "timeouts": 0}
        self.stats_lock = threading.Lock()

    def _bump(self, k):
        with self.stats_lock:
            self.stats[k] += 1

    def get(self, url, limiter="cdn", accept_404=False):
        lim = self.api_limit if limiter == "api" else self.cdn_limit
        last = None
        for attempt in range(self.retries + 1):
            lim.wait()
            self._bump("requests")
            req = urllib.request.Request(url, headers=HEADERS)
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    return r.read()
            except urllib.error.HTTPError as e:
                last = f"HTTP {e.code}"
                if e.code in (404, 403) and accept_404:
                    raise NotFound(url)
                if e.code == 429:
                    self._bump("r429")
                    ra = e.headers.get("Retry-After")
                    pen = float(ra) if ra and ra.isdigit() else 2.0 * (attempt + 1)
                    lim.penalize(pen)
                elif 500 <= e.code < 600:
                    self._bump("r5xx")
                elif e.code in (404, 403):
                    raise RuntimeError(f"{last} for {url}")
                else:
                    raise RuntimeError(f"{last} for {url}")
            except (urllib.error.URLError, socket.timeout, TimeoutError,
                    ConnectionError, OSError) as e:
                self._bump("timeouts")
                last = f"{type(e).__name__}: {e}"
            if attempt < self.retries:
                self._bump("retries")
                time.sleep(min(60, (2 ** attempt) * 0.5) * (0.75 + random.random() / 2))
        raise RuntimeError(f"giving up after {self.retries + 1} attempts ({last}): {url}")

    def api(self, base, path, lang, map_id, **params):
        q = {"app_sn": "ys_obc", "lang": lang, "map_id": map_id}
        q.update(params)
        url = f"{base}{path}?{urllib.parse.urlencode(q)}"
        last = None
        for attempt in range(3):
            raw = self.get(url, "api")
            try:
                j = json.loads(raw)
            except ValueError as e:
                last = f"bad JSON: {e}"
                time.sleep(1 + attempt)
                continue
            if j.get("retcode") == 0:
                return j["data"]
            last = f"retcode {j.get('retcode')} {j.get('message')}"
            time.sleep(1 + 2 * attempt)
        raise RuntimeError(f"API error ({last}): {url}")


def ok_file(p):
    try:
        return p.stat().st_size > 0
    except OSError:
        return False


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def write_json(path, obj, indent=None):
    data = json.dumps(obj, ensure_ascii=False, separators=(",", ":") if indent is None else None,
                      indent=indent).encode("utf-8")
    atomic_write(path, data)
    return len(data)


def read_json(path, default=None):
    try:
        with open(path, "rb") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def run_pool(label, items, fn, workers):
    """Run fn(item) -> nbytes|None (None = skipped) for each item. Returns failures."""
    prog = Progress(label, len(items))
    failures = []
    if not items:
        print(f"[{label}] nothing to do", flush=True)
        return failures

    def wrap(item):
        try:
            r = fn(item)
            if r is None:
                prog.tick(skipped=True)
            else:
                prog.tick(nbytes=r)
        except NotFound:
            prog.tick(skipped=True)
            raise
        except Exception as e:  # noqa: BLE001
            prog.tick(failed=True)
            failures.append((item, str(e)))

    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(wrap, it) for it in items]
        try:
            for f in cf.as_completed(futs):
                try:
                    f.result()
                except NotFound:
                    pass
        except KeyboardInterrupt:
            print("\ninterrupted; cancelling pending work (re-run to resume)", flush=True)
            for f in futs:
                f.cancel()
            raise
    prog.finish()
    for item, err in failures[:10]:
        print(f"  FAILED {item!r}: {err}", flush=True)
    if len(failures) > 10:
        print(f"  ... and {len(failures) - 10} more failures", flush=True)
    return failures


# --------------------------------------------------------------------------
# pack logic
# --------------------------------------------------------------------------
class Pack:
    def __init__(self, args):
        self.args = args
        self.out = Path(args.out)
        self.lang = args.lang
        self.maps = [int(m) for m in args.maps.split(",") if m.strip()]
        self.f = Fetcher(args.rate, args.cdn_rate)
        self.workers = args.workers
        self.failures = {}  # step -> list
        self.manifest_path = self.out / "manifest.json"
        self.manifest = read_json(self.manifest_path, {}) or {}

    # ----- paths
    def mp(self, map_id, name):
        return self.out / "maps" / str(map_id) / name

    def detail_v2(self, map_id):
        info = read_json(self.mp(map_id, "info.json"))
        if not info:
            raise RuntimeError(f"map {map_id}: info.json missing; run the 'meta' step first")
        d = info["info"]["detail_v2"]
        return d

    # ----- meta
    def step_meta(self):
        force = self.args.force_meta
        jobs = []
        lst = self.out / "maps" / "list.json"
        jobs.append((lst, lambda: self.f.api(DYNAMIC, "/v1/map/list", self.lang, self.maps[0])))
        for m in self.maps:
            jobs += [
                (self.mp(m, "info.json"), lambda m=m: self.f.api(STATIC, "/v3/map/info", self.lang, m)),
                (self.mp(m, "labels.json"), lambda m=m: self.f.api(STATIC, "/v2/map/label/tree", self.lang, m)),
                (self.mp(m, "anchors.json"), lambda m=m: self.f.api(STATIC, "/v1/map/map_anchor/list", self.lang, m)),
                (self.mp(m, "point_groups.json"), lambda m=m: self.f.api(STATIC, "/v2/map/point_group", self.lang, m)),
                (self.mp(m, "changelog.json"), lambda m=m: self.f.api(STATIC, "/v1/map/get_point_changelog", self.lang, m)),
            ]

        def do(job):
            path, getter = job
            if ok_file(path) and not force:
                return None
            return write_json(path, getter())

        self.failures["meta"] = run_pool("meta", jobs, do, self.workers)

    # ----- labels helpers
    def label_tree(self, map_id):
        d = read_json(self.mp(map_id, "labels.json"))
        if not d:
            raise RuntimeError(f"map {map_id}: labels.json missing; run the 'meta' step first")
        return d["tree"]

    @staticmethod
    def leaves(nodes):
        for n in nodes:
            ch = n.get("children") or []
            if ch:
                yield from Pack.leaves(ch)
            else:
                yield n

    # ----- icons
    def step_icons(self):
        icons = {}
        for m in self.maps:
            for n in self.leaves(self.label_tree(m)):
                url = n.get("icon") or ""
                if url:
                    icons.setdefault(n["id"], url.split("?")[0])
        items = sorted(icons.items())

        def do(item):
            lid, url = item
            dest = self.out / "icons" / f"{lid}.png"
            if ok_file(dest):
                return None
            data = self.f.get(url)
            atomic_write(dest, data)
            return len(data)

        self.failures["icons"] = run_pool("icons", items, do, self.workers)

    # ----- points
    def step_points(self):
        jobs = []
        for m in self.maps:
            for top in self.label_tree(m):
                jobs.append((m, top["id"]))
        pts = {m: {} for m in self.maps}
        lbls = {m: {} for m in self.maps}
        lock = threading.Lock()
        todo_maps = {m for m in self.maps
                     if not ok_file(self.mp(m, "points.json")) or self.args.force_meta}
        jobs = [j for j in jobs if j[0] in todo_maps]

        def do(job):
            m, lid = job
            d = self.f.api(STATIC, "/v3/map/point/list", self.lang, m, label_ids=lid)
            with lock:
                for p in d.get("point_list") or []:
                    pts[m][p["id"]] = p
                for l in d.get("label_list") or []:
                    lbls[m][l["id"]] = l
            return len(json.dumps(d))

        fails = run_pool("points", jobs, do, self.workers)
        self.failures["points"] = fails
        failed_maps = {j[0] for j, _ in fails}
        for m in todo_maps - failed_maps:
            plist = sorted(pts[m].values(), key=lambda p: p["id"])
            write_json(self.mp(m, "points.json"),
                       {"point_list": plist, "label_list": sorted(lbls[m].values(), key=lambda l: l["id"])})
            print(f"  map {m}: {len(plist)} points", flush=True)
        for m in failed_maps:
            print(f"  map {m}: NOT written because some label requests failed", flush=True)

    # ----- tiles
    def tile_list(self, map_id):
        d = self.detail_v2(map_id)
        w, h = d["total_size"]
        ver = d["map_version"]
        out = []
        for z in range(d["min_zoom"], d["max_zoom"] + 1):
            s = 2.0 ** z
            nx = math.ceil(w * s / 256)
            ny = math.ceil(h * s / 256)
            for x in range(nx):
                for y in range(ny):
                    out.append((map_id, ver, z, x, y))
        return out

    @staticmethod
    def tile_url(map_id, ver, z, x, y):
        zz = ("N" if z < 0 else "P") + str(abs(z))
        return f"{TILE_BASE}/{map_id}/{ver}/{x}_{y}_{zz}.webp"

    def tile_path(self, map_id, ver, z, x, y):
        return self.out / "tiles" / str(map_id) / ver / str(z) / f"{x}_{y}.webp"

    def step_tiles(self):
        items = []
        for m in self.maps:
            items += self.tile_list(m)
        missing = []

        def do(t):
            dest = self.tile_path(*t)
            if ok_file(dest):
                return None
            try:
                data = self.f.get(self.tile_url(*t), accept_404=True)
            except NotFound:
                missing.append(t)
                raise
            atomic_write(dest, data)
            return len(data)

        self.failures["tiles"] = run_pool("tiles", items, do, self.workers)
        self.tiles_missing = missing
        if missing:
            print(f"  {len(missing)} tiles returned 404/403 (not on server)", flush=True)

    # ----- overlays
    def overlay_items(self):
        items = {}
        for m in self.maps:
            pg = read_json(self.mp(m, "point_groups.json"))
            if not pg:
                continue
            for g in pg.get("list") or []:
                for fl in g.get("floors") or []:
                    url = (fl.get("overlay") or {}).get("url")
                    if url:
                        items.setdefault(fl["id"], url.split("?")[0])
        return sorted(items.items())

    def step_overlays(self):
        def do(item):
            fid, url = item
            dest = self.out / "overlays" / f"{fid}.png"
            if ok_file(dest):
                return None
            data = self.f.get(url)
            atomic_write(dest, data)
            return len(data)

        self.failures["overlays"] = run_pool("overlays", self.overlay_items(), do, self.workers)

    # ----- details / media
    def detail_targets(self):
        """(map_id, point_id) list, optionally evenly subsampled by --limit-details."""
        allp = []
        for m in self.maps:
            d = read_json(self.mp(m, "points.json"))
            if not d:
                raise RuntimeError(f"map {m}: points.json missing; run the 'points' step first")
            allp += [(m, p["id"]) for p in d["point_list"]]
        allp.sort(key=lambda t: (t[1], t[0]))
        n = self.args.limit_details
        if n and n < len(allp):
            stride = len(allp) / n
            allp = [allp[int(i * stride)] for i in range(n)]
        return allp

    def step_details(self):
        items = self.detail_targets()

        def do(t):
            m, pid = t
            dest = self.out / "details" / str(m) / f"{pid}.json"
            if ok_file(dest):
                return None
            d = self.f.api(STATIC, "/v1/map/point/info", self.lang, m, point_id=pid)
            return write_json(dest, d)

        self.failures["details"] = run_pool("details", items, do, self.workers)

    @staticmethod
    def media_urls(detail):
        info = (detail or {}).get("info") or {}
        urls = []
        img = info.get("img")
        if isinstance(img, str):
            urls += [u.strip() for u in re.split(r"[,\s]+", img) if u.strip().startswith("http")]
        elif isinstance(img, list):
            urls += [u for u in img if isinstance(u, str)]
        ul = info.get("url_list")
        if isinstance(ul, list):
            for u in ul:
                if isinstance(u, str):
                    urls.append(u)
                elif isinstance(u, dict):
                    uu = u.get("url") or u.get("img")
                    if uu:
                        urls.append(uu)
        seen, res = set(), []
        for u in urls:
            u = u.split("?")[0]
            if u not in seen:
                seen.add(u)
                res.append(u)
        return res

    def step_media(self):
        items = []
        for m, pid in self.detail_targets():
            d = read_json(self.out / "details" / str(m) / f"{pid}.json")
            if d is None:
                continue
            for n, url in enumerate(self.media_urls(d)):
                items.append((pid, n, url))

        def do(t):
            pid, n, url = t
            ext = os.path.splitext(urllib.parse.urlparse(url).path)[1].lower() or ".bin"
            dest = self.out / "media" / str(pid) / f"{n}{ext}"
            if ok_file(dest):
                return None
            data = self.f.get(url, accept_404=True)
            atomic_write(dest, data)
            return len(data)

        self.failures["media"] = run_pool("media", items, do, self.workers)

    # ----- manifest / summary
    def dir_stats(self, root):
        n = size = 0
        if root.is_dir():
            for dp, _, fns in os.walk(root):
                for fn in fns:
                    if fn.startswith("."):
                        continue
                    n += 1
                    try:
                        size += os.path.getsize(os.path.join(dp, fn))
                    except OSError:
                        pass
        return n, size

    def build_manifest(self):
        man = self.manifest
        man["schema"] = PACK_SCHEMA
        man.setdefault("created", datetime.now(timezone.utc).isoformat(timespec="seconds"))
        man["updated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        man["lang"] = self.lang
        maps = man.setdefault("maps", {})
        total = {c: [0, 0] for c in CATEGORIES}
        for m in self.maps:
            e = maps.setdefault(str(m), {})
            info = read_json(self.mp(m, "info.json"))
            if info:
                e["name"] = info["info"].get("name")
                d = info["info"].get("detail_v2")
                e["detail_v2"] = d
                ver = d["map_version"]
                zooms = {}
                empty = []
                tot = emp = 0
                tbytes = 0
                for t in self.tile_list(m):
                    p = self.tile_path(*t)
                    z = t[2]
                    zz = zooms.setdefault(str(z), {"expected": 0, "present": 0, "empty": 0})
                    zz["expected"] += 1
                    try:
                        sz = p.stat().st_size
                    except OSError:
                        continue
                    if sz <= 0:
                        continue
                    zz["present"] += 1
                    tot += 1
                    tbytes += sz
                    if sz <= EMPTY_TILE_MAX_BYTES:
                        zz["empty"] += 1
                        emp += 1
                        empty.append(f"{z}/{t[3]}_{t[4]}")
                e["tiles"] = {
                    "map_version": ver,
                    "expected": sum(v["expected"] for v in zooms.values()),
                    "present": tot, "empty": emp, "bytes": tbytes,
                    "per_zoom": zooms,
                    "empty_tiles": empty,
                }
            pts = read_json(self.mp(m, "points.json"))
            if pts:
                e["points"] = len(pts["point_list"])
            lab = read_json(self.mp(m, "labels.json"))
            if lab:
                e["leaf_labels"] = sum(1 for _ in self.leaves(lab["tree"]))
                e["top_level_labels"] = len(lab["tree"])
            det = self.out / "details" / str(m)
            dn, ds = self.dir_stats(det)
            e["details"] = {"count": dn, "bytes": ds}
            mn, ms = self.dir_stats(self.out / "maps" / str(m))
            e["bytes"] = {"tiles": e.get("tiles", {}).get("bytes", 0), "maps_json": ms, "details": ds}
        exp_icons = {}
        for m in self.maps:
            if ok_file(self.mp(m, "labels.json")):
                for n in self.leaves(self.label_tree(m)):
                    if n.get("icon"):
                        exp_icons[n["id"]] = n["icon"].split("?")[0]
        miss = {str(i): u for i, u in sorted(exp_icons.items())
                if not ok_file(self.out / "icons" / f"{i}.png")}
        man["icons"] = {"expected": len(exp_icons), "present": len(exp_icons) - len(miss),
                        "missing": miss}
        cats = {}
        for c in CATEGORIES:
            cats[c] = dict(zip(("files", "bytes"), self.dir_stats(self.out / c)))
        man["categories"] = cats
        man["empty_tile_max_bytes"] = EMPTY_TILE_MAX_BYTES
        write_json(self.manifest_path, man, indent=1)
        return man

    def print_summary(self, man):
        print("\n=== pack size summary ===", flush=True)
        for c, v in man["categories"].items():
            print(f"  {c:<10} {v['files']:>8} files  {fmt_bytes(v['bytes']):>10}")
        total = sum(v["bytes"] for v in man["categories"].values())
        print(f"  {'TOTAL':<10} {'':>8}        {fmt_bytes(total):>10}")
        print("\nper map:")
        for m in self.maps:
            e = man["maps"].get(str(m), {})
            t = e.get("tiles")
            if t:
                print(f"  map {m} ({e.get('name')}): tiles {t['present']}/{t['expected']} "
                      f"({t['empty']} empty), {fmt_bytes(t['bytes'])}; "
                      f"points {e.get('points', '?')}; "
                      f"details {e['details']['count']} files {fmt_bytes(e['details']['bytes'])}; "
                      f"maps json {fmt_bytes(e['bytes']['maps_json'])}")
        st = self.f.stats
        print(f"\nHTTP: {st['requests']} requests, {st['retries']} retries, "
              f"{st['r429']} x429, {st['r5xx']} x5xx, {st['timeouts']} timeouts/net errors")
        nf = sum(len(v) for v in self.failures.values())
        if nf:
            print(f"FAILURES: {nf} ({', '.join(f'{k}={len(v)}' for k, v in self.failures.items() if v)})"
                  " -- re-run to retry")

    def run(self, steps):
        self.out.mkdir(parents=True, exist_ok=True)
        t0 = time.monotonic()
        for s in ALL_STEPS:
            if s in steps:
                print(f"\n--- step: {s} ---", flush=True)
                getattr(self, f"step_{s}")()
        man = self.build_manifest()
        self.print_summary(man)
        print(f"\nelapsed {fmt_dur(time.monotonic() - t0)}")
        return 1 if any(self.failures.values()) else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default="data/pack")
    ap.add_argument("--lang", default="en-us")
    ap.add_argument("--maps", default="2,7,9,36")
    ap.add_argument("--steps", default=",".join(DEFAULT_STEPS),
                    help=f"comma list from: {','.join(ALL_STEPS)} (default: {','.join(DEFAULT_STEPS)})")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--rate", type=float, default=8.0,
                    help="max API requests/sec across all workers (0 = unlimited)")
    ap.add_argument("--cdn-rate", type=float, default=0.0,
                    help="max tile/icon/media CDN requests/sec (0 = limited only by --workers)")
    ap.add_argument("--limit-details", type=int, default=0,
                    help="process only N evenly-spaced points for details/media (0 = all)")
    ap.add_argument("--force-meta", action="store_true",
                    help="re-fetch meta JSON and points even if present")
    args = ap.parse_args()
    steps = [s.strip() for s in args.steps.split(",") if s.strip()]
    bad = [s for s in steps if s not in ALL_STEPS]
    if bad:
        ap.error(f"unknown steps: {bad}")
    try:
        return Pack(args).run(steps)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
