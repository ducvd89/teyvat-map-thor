# Minimap localisation prototype (`tools/cv`)

Phase 0.3/0.4 of PLAN.md: capture Thor frames, label them, evaluate the matcher, tune it.
All commands run from the repo root with the project venv (`tools/.venv/bin/python`, OpenCV + numpy + Pillow).
Output artefacts live in `data/cv/` (mosaics, prepared maps, debug images, `eval_report.md`).

## Layout
| File | Purpose |
|---|---|
| `mapdata.py` | read `data/pack`, build grayscale/colour land mosaics per zoom level (`data/cv/mosaic_*.npy`, memory-mapped) |
| `preprocess.py` | minimap crop, ring mask (0.15R..0.88R), icon suppression, local-contrast-normalised representation (`lcn`, also `clahe`, `grad`), prepared mosaics (`prep_*.npy`), circle finder |
| `synth.py` | synthetic minimap frames (icons, arrow+cone, rim fade, transparency, blur, jpeg, colour jitter) + trajectories |
| `match.py` | local masked NCC tracking, global search (coarse z-2 -> top-K -> fine refine), ORB/AKAZE+RANSAC fallback, multi-scale `estimate_scale` |
| `heading.py` | gold arrow angle (colour mask, principal axis, skew for the tip) |
| `screen.py` | cheap per-frame tests: `thumbnail`, `is_loading_screen`, `ring_present` (+`fit_ring_ref`), `minimap_is_black`, `frame_changed` (all < 0.15 ms) |
| `track.py` | **the §6.2 state machine** (TRACKING/LOADING/RELOCATING/DOMAIN/HIDDEN/LOST/OFF), `next_check_delay()`, relocation search order, layer detection, counters |
| `layers.py` | layer zones (`point_groups.json` floors), rect index, floor overlay reference patches (`data/cv/layers/`) |
| `targets.py` | teleport targets (label tree "Waypoints" -> points) for RELOCATING step 2 |
| `track_legacy.py` | the old 4 Hz tracker, only used as the baseline in `cv_eval --scenarios --legacy` |
| `label_helper.py` | propose/accept labels for real screenshots |

Tools one level up: `tools/capture_thor.sh`, `tools/cv_proto.py`, `tools/cv_eval.py`.

## 0. One-time: build the map caches
```
tools/.venv/bin/python -m tools.cv.mapdata --z -3 -2 -1 0          # gray mosaics (needs all tiles in data/pack)
tools/.venv/bin/python -m tools.cv.mapdata --z 0 --color            # colour z0/-1 are used by synth + debug views
tools/.venv/bin/python -m tools.cv.mapdata --z -1 --color
```
(Prepared maps `prep_*` are built lazily on first use, about 5-10 s for all levels.)
If `map_version` changes the file names change automatically; delete old `data/cv/*.npy` to reclaim space.

## 1. Capture frames on the Thor
```
tools/capture_thor.sh                    # every 2 s -> tools/dataset/raw/YYYYmmdd-HHMMSS.png, Ctrl+C to stop
tools/capture_thor.sh -i 1 -n 60         # 60 frames, 1 s apart
tools/capture_thor.sh -d <displayId>     # force a display id (see `adb shell dumpsys SurfaceFlinger --display-id`)
```
Aim for ~60 frames: city, forest, desert, snow, water, night, caves, Natlan, Nod-Krai, with and without
icons/quest markers/pins nearby, minimap at its default size. Walk around between shots so positions differ.
Frames must be full 1920x1080 captures of the **top** screen (minimap top-left).

## 2. Calibrate (circle + scale)
```
tools/.venv/bin/python tools/cv_proto.py tools/dataset/raw/ --calibrate
```
Detects the minimap circle (Hough, top-left corner) and estimates the scale `s` (minimap px per z0 map px)
by a multi-scale whole-map search per frame; the median is saved to `data/cv/calib.json`.
If the global scale search is ambiguous, pass a rough position of one frame (`--prior X_POS Y_POS` read from
the in-game map / HoYoLAB), or set `--calib CX CY R` and `--scale S` by hand. Check with `--debug`
(`data/cv/debug/*_debug.png`: crop | mask | template | heatmap | map patch at best match | overlay).

## 3. Label
```
tools/.venv/bin/python -m tools.cv.label_helper                       # propose top-3 candidates per unlabelled frame
#   -> tools/dataset/candidates/NAME.png  (minimap crop | #0 | #1 | #2 map patches at the minimap's scale)
tools/.venv/bin/python -m tools.cv.label_helper --accept NAME.png 0   # write the label for candidate 0
tools/.venv/bin/python -m tools.cv.label_helper --set NAME.png X_POS Y_POS   # manual label
```
Open the PNGs, pick the candidate whose terrain matches the minimap (icons/arrow are expected to differ).
Labels go to `tools/dataset/labels.json` (`{"NAME.png": {"x_pos":..,"y_pos":..}}`, optional `"s"`).
Aim for >= 30 labelled frames. Note labels from the matcher itself are biased toward what it can already match,
so also hand-label (`--set`) a few hard frames (snow, water, caves) from the big map.

## 4. Evaluate
```
tools/.venv/bin/python tools/cv_eval.py                    # synthetic N=300 + timing + scale + tracking + real labels -> data/cv/eval_report.md
tools/.venv/bin/python tools/cv_eval.py --n 90 --quick      # accuracy only, ~25 s
tools/.venv/bin/python tools/cv_eval.py --real-only         # only the labelled real set
tools/.venv/bin/python tools/cv_eval.py --sigma 2 --repr lcn  # try other representations
```
Metrics: median / p90 error in z0 px, failure rate (error > 25 px), "accepted but wrong" (the dangerous
case for the tracker), ms per frame for local and global modes (1 thread and N threads), scale-estimation error.
Synthetic numbers are optimistic (same imagery as the reference).

### State-machine scenarios
```
tools/.venv/bin/python tools/cv_eval.py --scenarios --legacy       # ~10 min -> data/cv/scenario_report.md
tools/.venv/bin/python tools/cv_eval.py --scenarios --seeds 1 --tele-seeds 1 --cave-seeds 1 --no-screen   # quick
```
Simulated-time runs of the tracker on synthetic timelines (`synth.Scenario`: walk, stand, big map, menu, cut-scene, teleports to
waypoints/statues/domains/random places, domain with black minimap, sea, caves with floor changes). Frames are rendered at the instants
`next_check_delay()` asks for. The report has the confusion matrix, errors, relock times, per-path relocation stats, layer accuracy and the
check/CPU budget against the old 4 Hz design. `cv_proto.py FILE|DIR --mode track` prints state, ring correlation and thumbnail stats per frame.

Other CLI: `tools/cv_proto.py FILE|DIR [--mode global|local|track|both] [--prior X Y] [--debug] [--features]`,
`tools/cv_proto.py --synthetic 5 --debug`.

## Tunables (see `Params`, `TrackCfg`, `Matcher`)
`sigma` (LCN scale, working px), `coarse_z` (-2), `topk` (8), mask radii (0.15R / 0.88R), icon thresholds,
`tau_*` accept thresholds. Thresholds were set on synthetic data; **re-tune them on the real set**.


## State machine and parameters (reference for the Kotlin port)

`track.Tracker.update(frame, t)` is called every `next_check_delay()` seconds. Every check runs, in this order (first hit returns):

1. **Thumbnail** (`screen.thumbnail`: every 10th pixel, 3x3 mean -> 64x36 gray). `is_loading_screen`: median >= 228 or <= 26 **and** >= 92 % of
   pixels within +-12 of the median, **and the minimap ring is absent** (a dark/bright but uniform HUD scene or a domain interior with the ring on screen is not a
   loading screen; the real HUD is assumed hidden while loading, confirm on the Thor). Uniform: `n_uni += 1`; `n_uni >= 2` -> `LOADING` (from any state). While `n_uni == 1` the next check is in 1 s.
   First non-uniform check in `LOADING` -> `RELOCATING` (the same check already searches).
2. **Ring** (`screen.ring_present`): gray samples on 180 angles x 8 radii (R-1.5 .. R+5.5 step 1) at the calibrated centre, Pearson correlation with the
   reference stored at calibration (`calib.ring_ref`); present if >= 0.70. Absent: TRACKING/DOMAIN/LOST -> `HIDDEN` (remembers where it came from),
   RELOCATING/HIDDEN stay.
3. **Black interior** (`screen.minimap_is_black`, annulus 0.62R..0.88R of the minimap crop): median <= 8 **and** p90 <= 24 **and** edge fraction
   (|Sobel x|+|Sobel y| > 96) <= 3 %. Twice in a row (`n_black`; the second check comes after 1 s) -> `DOMAIN`. Sea tiles (gray ~22) and the underground
   background (~20) are deliberately *not* black.
4. Only now matching, per state:

| State | Runs | Delay |
|---|---|---|
| TRACKING | local masked-NCC search, radius `clip(45*dt + 10 + 8*miss, 24, 90)` z0 px around the alpha-beta prediction; accept if score >= 0.40, margin >= 0.02 **and** distance to prediction <= the same gate (a far match is a miss, never a jump). 3 misses in a row -> `LOST`. Unchanged minimap (`frame_changed`: 32x32 signature, annulus 0.55R..0.88R, mean abs diff > 1.5) -> matching skipped ("still"), every 5th unchanged check still searches | 1 s; 0.5 s if speed > 0.12 * (R/s) z0 px/s; 2 s once the minimap has been unchanged for 10 s; 0.5 s while a layer switch is pending |
| LOADING | nothing | 1 s |
| RELOCATING | search order below, 2-frame agreement (results within 30 z0 px); > 10 s -> `LOST` | 0.5 s |
| DOMAIN | nothing (interior no longer black without a loading screen -> `RELOCATING`: a short loading screen can fall between two 5 s checks) | 5 s |
| HIDDEN | nothing; when the ring is back: black -> DOMAIN path; else local search (radius 135) around the last position, miss -> `RELOCATING` | 2 s |
| LOST | one `RELOCATING` search order pass per 3 s, 2-frame agreement | 3 s |

**Relocation search order** (`_relocate`), first result must be confirmed by a second agreeing result; the confirmation is a local search (radius 40)
around the first result on the next frame (radius 40 + 0.6*45*dt, agreement distance 30 + 0.6*45*dt, because the player keeps moving; accept: score >= 0.50,
margin >= 0.08):
1. local search around the last known position, radius 150 z0 px; accept score >= 0.55 and margin >= 0.10;
2. (before step 3: the whole-map scan only runs once a RELOCATING frame in a row has already found nothing in steps 1-2, which skips the washed-out first
   frames of a fade-in; in `LOST` it runs at once. A flat minimap (`minimap_is_flat`: 10th-90th percentile spread <= 6 in the outer annulus) skips the search entirely.)
2. teleport targets (`targets.py`: all points under label 1 "Waypoints", 801 on Teyvat): z-3 template score in a +-60 z0 px window around each, top 5 refined at
   the local level; accept score >= 0.50 and margin >= 0.08. After `DOMAIN` the domains within 3000 px of the last position are scored first; after an abrupt loss
   (previous state TRACKING) the statues first; then all remaining targets;
3. whole-map coarse search + top-8 refine (`Matcher.global_match`), accept score >= 0.50, margin >= 0.08.

**Layers** (`layers.py`, inside TRACKING only): a layer check (local match at the predicted position against the surface and every floor whose bounds contain it,
current layer +0.03 bonus) runs on (a) entering a zone, (b) a local miss or a score more than 0.10 below the running average (EMA 0.3), (c) every 5 s inside
a zone, (d) after a RELOCATING lock inside a zone, (e) every check while a switch is pending. A switch needs 2 consecutive checks agreeing; while one is pending
the current layer's result is rejected if the candidate scores >= 0.15 higher. No zone -> surface. Floor reference = surface gray patch with the floor overlay
(stretched to its bounds, composited over gray 20) pasted in, same LCN representation, padded by 220 z0 px.

Counters (`trk.stats`): `checks, full, target, local, layer_checks, cheap, cpu_ms, wall_ms, cheap_ms, by_state`; relocation episodes in `trk.episodes`.

**Caveat:** all thresholds above were set on synthetic frames. Real Thor screenshots must confirm: loading-screen look (white/black, how much is non-uniform, whether
the HUD is hidden), the black domain minimap level, the underground minimap background level and whether it is flat enough to be mistaken for a domain, the
ring correlation threshold (the real ring is not our synthetic ring), walking speeds (`vmax`, fast threshold) and the floor-overlay placement of real caves.

## Real Thor frames (Kotlin port of data/cv/real_report.md)

`export_real_fixtures.py` turns `tools/dataset/raw` + `labels.json` into test fixtures for the Android instrumented tests
(never packed into the APK): the whole 450-frame sequence as minimap ROI + 64x36 thumbnail (the app never reads more),
~60 full frames with the player UID blanked, bm1/zoom1 big-map captures (truth: player at x_pos -8223.4, y_pos -6098.1), the
glyph templates of the big map's bottom bar (written to `app/src/main/resources/cv/bigmap_ui/`) and a compressed full-frame
replay (`e2e/`, with two frames carrying a SYNTHETIC area-name banner: the recording has no real one).

    tools/.venv/bin/python -m tools.cv.export_real_fixtures
    tools/cv/real_push.sh            # push to emulator-5554 + run RealSequenceTest, BigMapTest, BannerOcrTest

End-to-end on the emulator (tracking from display 2 while the fake game plays on display 0):
`adb -s emulator-5554 shell appops set com.teyvatmap.thor PROJECT_MEDIA allow`, write the real calibration
(`{"cx":210.5,"cy":156.1,"r":133.4,"s":0.752}`) to `files/cv/calibration.json`, start the app on display 2, tap "Track", then
`am start --display 0 -n com.teyvatmap.thor/.debug.FakeGameActivity -e scenario real_e2e --ez loop true`.
