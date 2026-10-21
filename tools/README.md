# tools/

## fetch_pack.py: offline pack builder

Stdlib-only Python 3. Downloads HoYoLAB Genshin interactive map data into `data/pack/` (git-ignored).
Resumable: files that already exist with nonzero size are skipped; writes are atomic (tmp + rename).
Requests retry with exponential backoff on 429/5xx/timeouts.

```
python3 tools/fetch_pack.py --out data/pack --lang en-us --maps 2,7,9,36 \
    --steps meta,icons,points,tiles,overlays --workers 8 --rate 8
```

| Step | Writes |
|---|---|
| meta | `maps/list.json`, `maps/{id}/{info,labels,anchors,point_groups,changelog}.json` |
| icons | `icons/{labelId}.png` (full size, `?x-oss-process` stripped) |
| points | `maps/{id}/points.json` (`point_list` deduped by id + `label_list`) |
| tiles | `tiles/{mapId}/{mapVersion}/{z}/{x}_{y}.webp` |
| overlays | `overlays/{floorId}.png` |
| details (opt-in) | `details/{mapId}/{pointId}.json` (88k requests for all points) |
| media (opt-in) | `media/{pointId}/{n}.<ext>` (images from the detail `img` / `url_list`; needs details first) |

Default steps are everything except `details` and `media`.

Options:
- `--rate N` limits API requests/sec (global, default 8). `--cdn-rate N` limits tile/icon/media CDN requests (default 0 = only `--workers` bounds it).
- `--limit-details N` processes N evenly spaced points (sorted by id) for details/media, useful for size estimates. Without it, all points.
- `--force-meta` re-fetches meta JSON and points.
- Exit code 1 if any request ultimately failed (re-run to retry).

`manifest.json` is rewritten at the end of every run: schema, lang, per-map `detail_v2`, tile counts per zoom, the list of empty (blank, <= 64 bytes) tiles, byte totals per category, and any icons that could not be downloaded.

## render_check.py: coordinate-transform check

Stitches one zoom level of a map and draws circles at the points of chosen labels
(`px = x_pos + origin[0]`, `py = y_pos + origin[1]`, scaled by `2^z`). Needs Pillow:

```
python3 -m venv tools/.venv && tools/.venv/bin/pip install pillow
tools/.venv/bin/python tools/render_check.py --map 2 --zoom -3 --labels 3,2
# -> data/render_check_map2.png
```
