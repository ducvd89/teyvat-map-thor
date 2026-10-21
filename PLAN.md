# Teyvat Second Screen: detailed plan

An offline interactive Genshin Impact map for the **AYN Thor Max** (Snapdragon 8 Gen 2, 16 GB RAM, 1 TB, Android 13).
The game runs on the top screen and this app runs on the bottom screen.

**Goals**
1. All HoYoLAB map data (tiles, points, icons, descriptions, images, underground layers) stored **on the device**.
2. Tap to mark collectibles as found, with progress counters, respawn timers and custom pins, synced through **Google Drive**.
3. **Live player position** read from the in-game minimap through Android's screen-capture API. Nothing touches the game.
4. **Updates**: a daily automatic check plus an "Update now" button. Only changed data is downloaded.

**Non-goals.** These are never done:
- root
- reading game memory or files
- capturing or decrypting network traffic (VPN capture was dropped)
- input injection or auto-clicking
- accessibility services
- drawing anything over the game screen
- distributing the app or its data publicly (it's for personal sideloading only)

---

## 1. Verified facts (checked against HoYoLAB on 2026-10-21)

### 1.1 API
| Purpose | Endpoint (GET) |
|---|---|
| Static base | `https://sg-public-api-static.hoyolab.com/common/map_user/ys_obc` |
| Dynamic base | `https://sg-public-api.hoyolab.com/common/map_user/ys_obc` |
| Common query | `app_sn=ys_obc&lang=<en-us\|vi-vn\|…>&map_id=<id>` |
| All maps | `/v1/map/list` (dynamic base) |
| Map metadata | `/v3/map/info` → `detail_v2 {total_size, padding, origin, map_version, min_zoom, max_zoom, original_map_size}` |
| Categories | `/v2/map/label/tree`: 15 top-level groups, 793 leaf labels (name, icon, `point_count`, sort) |
| Points | `/v3/map/point/list?label_ids=<top-level id>` → `{id, label_id, x_pos, y_pos, area_id, z_level, display_state, icon_sign, point_group{group_id, floor_id}, ctime}` |
| Point detail | `/v1/map/point/info?point_id=` → `content` (description), `img`, `url_list`, `version`, `last_update_time` |
| Underground layers | `/v2/map/point_group` → groups → floors, each with an overlay image, bounds and `point_ids` |
| Region names | `/v1/map/map_anchor/list` → name, bounding box, `area_id` (nested) |
| "What's new" | `/v1/map/get_point_changelog` → counts per category for each data update |

The web client also sends the header `x-rpc-map_version: 4.5`. Send the same header to stay compatible.

### 1.2 Tiles and coordinates
- **Tile URL:** `https://act-webstatic.hoyoverse.com/map_manage/map/{map_id}/{map_version}/{x}_{y}_{N|P}{|z|}.webp`
  - 256 px tiles; `N` means a negative zoom (e.g. `11_5_N3` = x 11, y 5, zoom −3).
- **Map space:** Leaflet's `CRS.Simple` with an identity transform, shifted by `origin`:
  - zoom-0 pixel `px = x_pos + origin[0]`, `py = y_pos + origin[1]`
  - at zoom `z`: `p · 2^z`; tile index = `floor(p · 2^z / 256)`
- **`map_version`** is a hash that appears in the tile path. When it changes, the tile set has changed.

### 1.3 Today's numbers
| Map | id | Size at zoom 0 | Zoom levels | Tiles |
|---|---|---|---|---|
| Teyvat | 2 | 36864 × 18432 | −3…0 | 13,770 (most sea tiles are 38-byte blanks) |
| Enkanomiya | 7 | 4096 × 4096 | −4…0 | 341 |
| The Chasm: Underground Mines | 9 | 4096 × 4096 | −4…0 | 341 |
| Ancient Sacred Mountain | 36 | 4096 × 4096 | −4…0 | 341 |

- **Points:** 88,715 on Teyvat across 793 categories. One top-level category list is about 200 KB of JSON.
- **Respawn data:** `rec_refresh_hour` is 0 for every label, so respawn timers need our own defaults (§5.4).

### 1.4 Storage estimate (all local, which is trivial on 1 TB)
| Data | Estimate |
|---|---|
| Teyvat tiles, all zoom levels | ~100–300 MB (sampled; exact in Phase 0) |
| Other 3 maps | ~30–80 MB |
| Underground overlays + 793 icons | ~20–50 MB |
| Points + categories + regions (Room DB) | ~40–80 MB |
| Point descriptions + screenshots | unknown, probably 1–5 GB (measured in Phase 0) |
| Image-matching reference data | ~0.5–1 GB |

---

## 2. Device facts (AYN Thor Max)
- **Top screen** (game): 6" AMOLED, 1920×1080, 120 Hz. This is Android's **default display** (display 0).
- **Bottom screen** (this app): 3.92" AMOLED, 1240×1080, 60 Hz. This is a secondary display.
- Android 13: MediaProjection asks for consent once per capture session and needs a foreground service of type `mediaProjection`.
- Android 10+ keeps apps on both screens running at once ("multi-resume"), so Genshin should keep running while the map is used. Verify in Phase 0.

---

## 3. Architecture

```
┌──────────── Bottom screen: Map app (single Activity, Compose) ───────────────┐
│ MapScreen (MapCompose) ─ markers / clusters / player arrow / underground overlays │
│ FilterDrawer ─ PointSheet ─ ProgressScreen ─ NearbyPanel ─ Settings ─ CalibrationUI │
└──────▲───────────────────────▲──────────────────────────▲──────────────────────┘
       │ Flow<Points/Marks>    │ StateFlow<PlayerPose>    │ Flow<SyncState/UpdateState>
┌──────┴──────┐   ┌────────────┴──────────────┐   ┌───────┴──────────────────────┐
│ Repository  │   │ TrackingService (FGS)     │   │ WorkManager workers          │
│ Room DB     │   │ MediaProjection → Image-  │   │  DataUpdateWorker (daily)     │
│ TileStore   │   │ Reader → CV pipeline       │   │  OfflinePackWorker (resumable)│
│ MediaStore  │   │ (OpenCV) → Kalman filter   │   │  DriveSyncWorker (debounced)  │
└──────▲──────┘   └────────────▲──────────────┘   │  CvIndexBuildWorker           │
       │                       │ reads display 0   └───────▲──────────────────────┘
       │                       │ pixels only               │
  HoYoLAB API + CDN      (Genshin on top screen)     Google Drive appDataFolder
```

### 3.1 Tech stack
Pin the current stable version of each library when scaffolding.
- **Language/UI:** Kotlin, Jetpack Compose (Material 3, pure-black AMOLED theme), Navigation-Compose.
- **Map:** **MapCompose** (`ovh.plrapps:mapcompose`). It's built for tiled maps that aren't real-world maps, and provides a `TileStreamProvider` that reads local files, markers, clustering, lazy marker loading, paths and smooth zoom.
- **Data:** Room (+ FTS4 for search), kotlinx.serialization, OkHttp (+ Retrofit), Coil for images.
- **Background work:** WorkManager. **Dependency injection:** Hilt.
- **Image matching:** OpenCV 4.x Android AAR (`org.opencv:opencv`) through its Java API. The inner loop moves to C++/JNI only if profiling says so.
- **Google:** Play Services `AuthorizationClient` with the `https://www.googleapis.com/auth/drive.appdata` scope, plus plain OkHttp calls to the Drive v3 REST API.
- **SDK levels:** minSdk 33 (Thor is Android 13), targetSdk = current.
- **Tools:** Python 3 + OpenCV + requests in `tools/`, for the image-matching prototype, the data pack builder and the test-set runner.

### 3.2 Project layout
This is one app module with clear packages; split it into modules only if build times hurt.
```
app/src/main/java/…/teyvatmap/
  App.kt, MainActivity.kt, di/
  dualscreen/        DisplayRouter, NonFocusableWindow, MoveToOtherScreen
  data/api/          HoyolabApi, DTOs, HeaderInterceptor
  data/db/           Room entities, DAOs, migrations, FTS
  data/store/        TileStore, MediaStore, PackManager (versioned dirs, atomic swap)
  data/update/       DataUpdateWorker, OfflinePackWorker, Differ, ChangeReport
  domain/            models, MapTransform (x_pos ↔ px ↔ tile), RespawnRules
  ui/map/            MapScreen, MarkerLayer, PlayerLayer, OverlayLayer, FilterDrawer, Search
  ui/point/          PointSheet (details, mark found/hide, note)
  ui/progress/       ProgressScreen (area × category), NearbyPanel
  ui/settings/       Settings, Language, Storage, Update, Sync, Tracking
  tracking/          TrackingService, FrameSource, MinimapDetector, Matcher (local/global),
                     HeadingEstimator, PoseFilter (Kalman), CvIndex, Calibration, DebugView
  sync/              GoogleAuth, DriveClient, SyncEngine (LWW merge), DriveSyncWorker
tools/
  fetch_pack.py      download everything → pack folder (for analysis, or adb push)
  cv_proto.py        minimap → map matching prototype
  cv_eval.py         run the labelled screenshot set → error / failure rate / ms per frame
  dataset/           Thor screenshots + ground-truth positions
```

### 3.3 Storage layout
- **Database:** internal storage, `databases/app.db`.
- **Large files:** in `getExternalFilesDir()` so they can be re-downloaded or pushed with adb from a PC.
```
files/
  tiles/{mapId}/{mapVersion}/{z}/{x}_{y}.webp
  overlays/{floorId}.png
  icons/{labelId}.png
  media/{pointId}/{n}.webp
  cv/{mapId}/{mapVersion}/coarse.bin, refine/{tx}_{ty}.bin, meta.json
  backups/marks-YYYYMMDD.json
```
The `DataVersion` table records which `mapVersion` is active. A new version downloads into a new folder, the pointer switches inside a database transaction, and only then is the old folder deleted. The app never shows half-downloaded data.

### 3.4 Room schema (main tables)
| Table | Columns |
|---|---|
| `maps` | id, name, totalW, totalH, originX, originY, minZoom, maxZoom, activeVersion |
| `labels` | id, parentId, mapId, name, iconPath, sort, pointCount, isLeaf |
| `points` | id, mapId, labelId, x, y, areaId, zLevel, groupId?, floorId?, displayState, ctime, detailVersion, removedAt? |
| `point_details` | pointId, content, imgPath?, urlList, version, lastUpdate, lang |
| `areas` | id, parentId, mapId, name, lX, lY, rX, rY, areaId |
| `floors` | id, groupId, name, overlayPath, lX, lY, rX, rY, defaultIndex |
| `user_marks` | pointId PK, state (FOUND / HIDDEN / NONE), updatedAt, deviceId, note? |
| `custom_pins` | uuid PK, mapId, x, y, floorId?, title, note, color, updatedAt, deleted |
| `respawn_rules` | labelId PK, hours (defaults shipped, editable) |
| `data_meta` | mapId, lang, mapVersion, pointsHash, lastChecked, lastUpdated |
| `points_fts` | name + description search index |

---

## 4. Offline data: download and update

### 4.1 First run: "Download everything" (OfflinePackWorker)
Runs on Wi-Fi, can be resumed, and shows progress per step.
1. Fetch `/v1/map/list` and `/v3/map/info` for each map (2, 7, 9, 36).
2. Download the category tree and all 793 icons at full size.
3. Download the points: one request per top-level category per map.
4. Download the region list (`map_anchor/list`) and underground floors (`point_group`), plus the floor overlay images.
5. Download all tiles, all zoom levels, all maps:
   - 8 at a time, with retries and backoff
   - a tile is skipped if it already exists in the same `mapVersion` folder
   - the 38-byte blank tiles are stored as a single shared "empty" marker
6. Download point descriptions and images for all 88k points:
   - limited to about 5 requests per second, so the first run takes a few hours
   - runs in the background while charging and can be resumed
   - descriptions open on demand (and are cached) until then
7. Build the image-matching reference data (§6.4).

There's also a faster option: `tools/fetch_pack.py` builds the same pack on the PC, and `adb push` copies it to `Android/data/<pkg>/files/`. The app checks it and imports it.

### 4.2 Updates (DataUpdateWorker)
- **When:** once a day on any network, at app start if the last check was over 12 h ago, and from the **Update now** button.
1. **Tiles:** compare `map_version` for each map. If it changed, download the new tile set into a new folder, switch over, rebuild the matching data, and delete the old version.
2. **Categories:** re-fetch the tree and update the rows; download any new icons.
3. **Points:** re-fetch every list and compare by `id`:
   - added → insert
   - moved or changed category → update
   - missing → set `removedAt` (marks stay in the database and are hidden)
   - fetch descriptions only for new or changed points
4. **Regions and floors:** re-fetch, compare, and download new overlays.
5. **Report:** a notification such as "Map updated: +312 points, new tiles (Nod-Krai)", using `get_point_changelog` for the per-category summary.
6. **Etiquette:** at most 8 parallel requests, retries with exponential backoff, and no more than one full check per 12 h unless started by hand.

### 4.3 Language
- The default is the device language, falling back to `en-us`.
- Switching language re-fetches categories, regions and descriptions in that language. Positions and marks don't change.
- Optionally keep two languages cached.

---

## 5. Collectible features

### 5.1 Map screen (bottom screen, 1240×1080, nearly square)
- The map fills the whole screen.
- **Top bar:** a search field that matches category and point names through FTS.
- **Right-side buttons:** follow-me, filter, underground layer, tracking on/off, sync status.
- **Markers:**
  - lazy loading plus clustering at low zoom
  - category icons with a ✓ on found items
  - an option to hide found items
- **Player arrow:** position and facing, a confidence ring, and a grey "last known" state.
- **Underground:** a layer chooser appears when the view is inside a floor group, showing that floor's overlay and points.

### 5.2 Filters
- A category tree with icons and found/total counts, e.g. "Pyroculus 42/50".
- Presets: Chests, Oculi, Daily ores, Specialties, Custom (user-saved).

### 5.3 Marking
- **Tap a marker** to open the point sheet: icon, name, region, description, screenshot (offline), and big **Found** / **Hide** buttons plus a note.
- **Long-press a marker** to toggle found instantly. This is the main gesture while playing.
- **Undo:** a snackbar for 5 s.
- **Custom pins:** long-press empty map → create a pin (title, note, color).
- **Nearby panel** (when tracking is on): uncollected items within N px of the player, nearest first, each with a direction arrow and a one-tap Found button.

### 5.4 Respawn timers
- Shipped defaults per category, editable in Settings. For example: ores 72 h, local specialties 48 h, crystalflies and animals 12–24 h.
- A respawnable item marked found shows a countdown and returns to "not found" by itself when the timer ends.

### 5.5 Progress screen
- Region × category tables (e.g. "Natlan · Chests 214/260"), with tap-through to show those items on the map.

---

## 6. Live location (the hard part)

### 6.0 Position sources (all from the top screen, read-only)
Real Thor frames showed that the minimap zoom **depends on the region** (zoomed in indoors and in cities, far out over open land and sea). So position comes from three screen sources that back each other up:

| Source | Gives | When | Accuracy |
|---|---|---|---|
| **Big map** (the in-game map, M) | Exact position anywhere, plus the region's zoom level for the minimap; the **teleport destination** when you pick a waypoint | Whenever the map is open | Best: same artwork as the HoYoLAB tiles, large and detailed |
| **Area-name banner** | Region or sub-area name, read with on-device OCR (ML Kit) and matched to `map_anchor` names in the **game's language** (Vietnamese on this Thor) | On entering an area | Coarse: a bounding box, but it narrows searches and sets the minimap zoom |
| **Minimap** | Continuous position between fixes | Always | Good when the region's zoom is known (§6.3 "Dynamic minimap zoom") |

How they combine:
- **Big map open** (detected by its fixed UI frame: close button, map controls): match the visible map region to the tiles with **feature matching** (SIFT/AKAZE + RANSAC similarity). The big map has user-controlled zoom, so a scale-invariant method is needed there, and the view is rich enough for it. The player arrow's screen position gives the exact player position. State shows "Fixed from map".
  - **Teleport destination:** when a waypoint is selected (the right-side panel with the Teleport button shows, and the map centers on the selected waypoint), the app records which waypoint it is: the HoYoLAB waypoint nearest the map's center pin. When the loading screen follows, `RELOCATING` checks that waypoint first, before last position and the other ~800 targets.
  - **Learning region zoom:** after each big-map fix, the next minimap frames are matched at that exact position over the scale ladder, and the winning scale is stored for that region (`region_zoom` table keyed by `area_id`/anchor). Next time in that region the zoom is already known.
- **Point data checked against the game:** on the Thor, 138/138 waypoint pins across 6 big-map views matched a HoYoLAB Teleport Waypoint (mean residual about 1–3 screen px), and no in-game waypoint was missing. HoYoLAB's coordinates can be trusted as they are. (An earlier "offset / missing waypoint" finding was a bug in a quick analysis script, not in the data.) A local correction layer isn't needed for now. If a region ever shows real errors, big-map fixes could detect icons and store corrections, but that isn't planned work.
- **Banner seen:** OCR the text, fuzzy-match it to anchor names, restrict `RELOCATING`/`LOST` searches to that anchor's bounding box, and preload that region's stored zoom.
- **Minimap:** continuous tracking as in §6.2–6.3, with the scale prior from the region table. At a region border both regions' zooms are tried.
- **Loading events:** detected from the screen only (uniform white/black thumbnail with the minimap absent; thresholds tuned on real Thor frames). Reading the game's own log lines was tried and dropped, because Android 13 asks for log-access consent on every run.
- **Map data language:** OCR matching needs the region names in the game's language. Fetch `map_anchor/list` in both the app language and the game language (setting: "Game language", default vi-vn here).


### 6.1 Capture
- `TrackingService` is a foreground service of type `mediaProjection`. Its notification has Pause and Stop.
- `MediaProjection` → `VirtualDisplay` at the top screen's resolution (1920×1080) → `ImageReader` (RGBA_8888, 2 buffers).
- Only the minimap area is copied out of each frame; the rest is discarded at once and never stored.
- **Check rate:** low by default and set by the tracking state (§6.2). Each tick takes a single frame with `acquireLatestImage`; frames in between are dropped without being read.
- **Optional:** grant the consent prompt once with `adb shell appops set <pkg> PROJECT_MEDIA allow` to skip it on every launch. Verify on the Thor.

### 6.2 Tracking states and check rate
Every check starts with two cheap tests (well under 1 ms):
- **Screen thumbnail:** the whole frame shrunk to 64×36. A loading screen is almost uniform (very low variance, mostly white or black).
- **Minimap ring:** compare the minimap's fixed border ring against the calibrated reference.

Only then does matching run, and only as much as the state needs:

| State | How it's detected | What runs | Check rate | Shown to the player |
|---|---|---|---|---|
| `TRACKING` | match accepted | **Local search only**, in a small window around the last position | 1 Hz; 2 Hz if moving fast; 0.5 Hz if the minimap hasn't changed for 10 s (standing still) | Arrow + position |
| `LOADING` | thumbnail is uniform for ≥ 2 checks | Nothing but the thumbnail test | 1 Hz | "Loading…", last position greyed |
| `RELOCATING` | the first frames after a loading screen ends | **Full search:** whole-map coarse pass on every map (2, 7, 9, 36), top-8 candidates refined, feature-matching fallback. A result must agree across 2 frames before it's accepted | 2 Hz until locked, max 10 s, then `LOST` | "Finding position…" |
| `DOMAIN` | ring is present but the minimap interior is black/textureless for ≥ 2 checks | Nothing but the thumbnail + ring tests (leaving a domain always shows a loading screen, which triggers `RELOCATING`) | 0.2 Hz | **"In a domain"** |
| `HIDDEN` | ring absent, screen not uniform (big map, menus, cutscenes, dialogue) | Nothing but the thumbnail + ring tests. When the minimap returns, try local search around the last position first, since you can't move far without a loading screen | 0.5 Hz | Last position, greyed |
| `LOST` | 3 local misses in a row, or `RELOCATING` timed out | Full search, **rate-limited to once every 3 s** (not every frame) | 0.33 Hz | "Position lost", last position greyed |

#### `RELOCATING` search order (cheapest likely answer first)
After a loading screen the player almost always stands at a known spot, so the whole-map scan is the last resort:
1. **Last known position** (local search, radius about 150 px), on the **last known floor** first. This covers leaving a domain, leaving the Serenitea Pot, respawning in place, and co-op joins/leaves that return you to where you were. It's checked first if the previous state was `DOMAIN`. If the last position was near a layer entrance, that entrance's floors are checked too.
2. **Teleport targets:** every point in HoYoLAB's top-level "Waypoints" category, about 800 on Teyvat plus those on maps 7, 9 and 36:
   - Teleport Waypoint 641, Domain 74, Statue of The Seven 62
   - meeting points, the Alcor, Jade Chamber, Palais Mermonia, Fortress of Meropide, Obsidian Totem Poles, etc.

   The 12 **underground Teleport Waypoints** (those listed in a floor's `entrance_ids`) are matched against their **linked floor overlay**, not the surface, so teleporting straight into a cave works.

   Each target gets a tiny match on the coarse map: a window of about ±60 px at zoom 0 around the target, since you spawn a few meters away. These are scored as one batch (about 800 small matches, tens of ms). The top 5 are refined at full detail. Domains near the last position are tried first after `DOMAIN`, and Statues of The Seven are tried first if the minimap vanished abruptly (fall/death respawn).
3. **Whole-map scan** (coarse pass + top-8 refine + feature fallback, all maps), only if steps 1 and 2 find nothing above τ. A portable waypoint, a Waverider or a quest teleport lands here.

Any step's result still needs the 2-frame agreement before it is accepted. The target list is rebuilt from the database after every data update, so new waypoints are included automatically.

Key rules:
- **Full searches run only after a loading screen** (teleport, domain entry/exit, entering a new area) **or when lost.** Normal walking, gliding and climbing only ever use the cheap local search.
- **Big jumps are only accepted after `LOADING`.** In `TRACKING`, a match far from the predicted position is treated as a miss, not a teleport.
- **"In a domain" comes from the black minimap, not guessing.** Domains, the Serenitea Pot and some quest instances show no map data in the minimap. The app keeps the last open-world position on the map and shows the banner.

#### Map layers (caves and underground areas)
Walking into a cave usually has **no loading screen**: the minimap simply switches to the underground floor's drawing. So layer detection runs inside `TRACKING`, using HoYoLAB's floor data (`point_group` → groups → floors, each with an overlay image, bounds and `point_ids`).
- **Layer zones:** at data import, each group's floor bounds are merged into a zone list with a spatial index. Checking "am I in a layer zone?" is a cheap point-in-rectangle lookup done on every accepted position.
- **Entry points:** each floor in the data lists `entrance_ids`, which are real points with a link to that floor. Teyvat has 488:
  - Cave 312, Bidirectional Pathway 130, Underwater Cavern 16
  - 12 underground Teleport Waypoints, 14 Upper/Lower Pathways
  - plus each group's `underground_entrance_x_pos/y_pos`

  At import these become an **entrance index**: entrance position → the floor(s) it leads to, plus the surface.
- **When the layer check runs** (never on every frame), entrances first:
  1. **Near an entrance** (within about 60 px at zoom 0 of an entrance point): check **that entrance's linked floor first**, then the surface, then the rest of the group's floors. While the player stays near the entrance, recheck at the normal tracking rate, because this is where floor changes actually happen. Walking out works the same way: near an entrance of the current floor, the surface (or the floor on the other side) is checked first.
  2. when the local match score **drops** while inside a zone (score < τ_layer, about 0.1 below the running average): check floors linked to the **nearest entrance** first, then the others
  3. right after `RELOCATING` locks on a position inside a zone
  4. otherwise every **10 s** while inside a zone, as a safety net (entrance checks make the old 5 s timer unnecessary)
- **What it does:** at the current position, run the local match against the candidate floors in the order above: the **surface** reference and each floor overlay of that group whose bounds contain the position (the overlays are preprocessed into reference tiles like the surface, §6.4). The highest score wins. A change of layer needs **2 checks in a row agreeing** (hysteresis), so a single noisy frame can't flip it.
- **Position while underground:** local tracking continues against the **current floor's overlay** (with surface tiles filling any area outside the overlay), so the position stays accurate in caves.
- **Relocation underground:** the surface tiles never match a floor's minimap, so `RELOCATING` / `LOST` also scan every floor overlay as a reference of its own (`JobKind.FLOORS`): inside the floor's bounds, at the coarse floor level (z-2..0), floors near the last position / destination / banner area and floors of underground waypoints first. The floor zoom differs from the surface (Fortress of Meropide: 1.49 where the surface is 0.752), so floors have their own scale rungs (`floorScanStep`, starting around `floorScaleAround`) interleaved with the surface ladder, and a lock on a floor learns the group's zoom (`RegionZoomTable.observeFloor`, tried first next time). Floor-target and floor-scan jobs are cut by the cost model like the whole-map scans.
- **Big map in layer mode:** the right-edge floor selector (white discs) marks it; the registration then also tries each floor group's mosaic (surface + its overlays) in priority order, a time-boxed batch per call. The fix carries the group; the floor is decided by the first minimap check after the map closes.
- **Leaving a zone:** the layer resets to the surface.
- **In the app:**
  - **entrance markers** are drawn on the map, with a distinct "layer entrance" style. Tapping one shows "Leads to: <floor name>" and a button that switches the map to that floor
  - the map shows that floor's overlay and only the points in that floor's `point_ids`
  - the bottom bar shows the floor name (e.g. "Mawtiyima: Concealed Sanctuary")
  - the layer chooser lights up the detected floor; picking a floor by hand overrides auto-detect until the player leaves the zone
- **Output:** `PlayerPose` gains `groupId?` and `floorId?` (null = surface).

### 6.3 Per-frame processing
1. **Prepare the crop** (only in states that run matching):
   - circular mask that removes the player arrow in the center (about 15% of the radius) and the faded edge (outside about 88%)
   - remove icon pixels (saturated colors and white icon shapes)
   - resize by the current scale `s` (tracked per check, see "Dynamic minimap zoom") to the matching zoom level
   - grayscale plus local contrast normalization (σ ≈ 1.2 px; CLAHE tested worse in the prototype)
2. **Local search (`TRACKING`):**
   - predict the next position (Kalman filter)
   - cut a reference window around it (radius = maximum speed × time + margin)
   - masked template matching (`matchTemplate`, `TM_CCORR_NORMED` with the mask)
   - take the peak and refine it to sub-pixel accuracy
   - confidence = peak score and how far it stands out from the second-best peak
3. **Accept or reject:** accept if the score ≥ τ and the jump is physically plausible. A big jump is accepted only right after `LOADING` (see §6.2). Otherwise count a miss; after 3 misses the state is `LOST`.
4. **Full search (`RELOCATING` and `LOST` only):**
   - coarse pass: template match on a downscaled map of the whole land area (~5400×4600 at zoom −2, kept in memory), starting with the last known region
   - refine the top 8 candidates at full detail
   - fallback: feature matching (ORB/AKAZE + RANSAC) on the candidate tiles
   - if Teyvat doesn't match, try maps 7, 9 and 36
5. **Facing direction:** threshold the gold arrow by color in the center crop, find its outline, and take the angle from its centroid to its tip, smoothed.
6. **Output:**
   - `StateFlow<PlayerPose(mapId, x_pos, y_pos, headingDeg, confidence, state)>`
   - `state` is one of TRACKING / LOADING / RELOCATING / DOMAIN / HIDDEN / LOST / OFF
   - convert with `x_pos = px − origin.x`, `y_pos = py − origin.y`

The Genshin minimap always points north (only the arrow and view cone turn), so matching only has to solve position, not rotation.

#### Dynamic minimap zoom
The game changes the minimap's zoom by region (e.g. zoomed in indoors, far out over open land and sea). So the scale `s` is **tracked per check, like the position**, not fixed by calibration.
- **Zoom is per region** (confirmed on real frames): the region table from §6.0 gives the expected scale. The ladder below is only for regions not learned yet and for confirming changes at region borders.
- **Scale ladder:** calibration measures the base scale `s0`. Matching uses a fixed ladder of scales around it: about 0.55×–1.45× `s0` in steps of about 4%, plus the discrete zoom levels learned from real frames. The minimap crop is resized once per scale; the reference map is never rescaled.
- **`TRACKING`:** each local search tests the **current scale and one step either side** (3 small matches, still a few ms). The best (position, scale) pair wins, and scale changes get the same 2-checks hysteresis as layers so one noisy frame can't flip the zoom.
- **Score drop or miss:** before counting a miss, sweep the **whole ladder** at the predicted position. A sudden zoom change looks exactly like a miss otherwise.
- **`RELOCATING` / `LOST`:** the teleport-target batch and the whole-map scan test a coarse subset of the ladder (about 5 scales). After a loading screen the game is usually at its normal zoom, so `s0` is tried first.
- **Output:** `PlayerPose` gains `scale`. The debug view shows the current scale and the scale score curve.
- **To confirm on real frames:** whether the zoom is continuous or a few fixed steps, what triggers it, and how fast it animates. If it's a few fixed steps, the ladder becomes just those steps (cheaper and more robust).

### 6.4 Matching reference data (CvIndexBuildWorker)
- Built from the local tiles every time `map_version` changes. It takes about 1–3 minutes in the background.
- **Coarse map:** the land area at zoom −2, preprocessed into one grayscale image (~25 MB) kept in memory.
- **Detailed tiles:** preprocessed grayscale tiles at the matching zoom, loaded through a memory map and an in-memory cache. The 16 GB of RAM allows a generous cache.
- **Floor overlays:** every underground floor overlay (293 images today) preprocessed the same way, plus the layer-zone index (§6.2 "Map layers").
- **Feature index** (only if the fallback is needed): ORB descriptors per tile.

### 6.5 Calibration (once per resolution/UI setup)
1. Stand in the open world and tap **Calibrate**. The app captures one frame and finds the minimap circle in the top-left area (circle detection). A preview on the bottom screen has drag handles to fine-tune it.
2. **Base scale:** stand still (so the game is at its normal zoom), run the whole-map search over a range of scales (0.3–1.5) and keep the best `s0`. Tapping your rough position on the map first makes this faster. The live scale is then tracked around `s0` (§6.3 "Dynamic minimap zoom").
3. Save the profile (resolution, circle, scale, border-ring reference). It can be re-run at any time.

### 6.6 Debug view
A toggle on the bottom screen showing:
- the crop, the mask and the match score map
- score, confidence, frames per second, ms per stage
- a "save this frame" button that adds it to `tools/dataset` for regression tests

This view is how the matching gets tuned. It isn't optional during development.

### 6.7 Targets (measured on the labelled screenshot set)
| Measure | Target |
|---|---|
| Median position error | ≤ 8 px at zoom 0 |
| Failure rate (lost while the minimap is visible) | < 3% |
| Time per frame, follow mode | < 30 ms |
| Whole-map search | < 1.5 s |
| Effect on game frame rate | not noticeable |
| Extra battery use | < 5% per hour |

### 6.8 Known limits
- **Underground and layered areas:** detected automatically from HoYoLAB's floor overlays (§6.2 "Map layers"). This only works where HoYoLAB has drawn the floor. Caves with no floor data stay on the surface layer, and tracking may drop to `LOST` until the player comes out.
- **New regions on game update day:** these only work once HoYoLAB publishes the tiles. The app reports "no map data here" instead of guessing.
- **Domains and the teapot:** the black minimap gives the `DOMAIN` state and an "In a domain" banner; the last open-world position stays on screen. Cutscenes and the big map give `HIDDEN`.

---

## 7. Google Drive sync
- **Auth:** `Identity.getAuthorizationClient(...).authorize(drive.appdata)`, which gives an access token. The app asks again silently when it expires.
- **Google Cloud setup (yours):**
  - one project with an OAuth client of type Android (package name + SHA-1 of the debug and release keys)
  - consent screen set to **In production**, because Testing mode makes you sign in again every week
- **File:** `marks.json` (gzipped) in `appDataFolder`, which is hidden from your normal Drive view:
  ```json
  { "schema": 1, "device": "thor-max-…", "marks": { "105872": {"s":"F","t":1792425600123} },
    "pins": { "<uuid>": {"x":…, "y":…, "title":"…", "t":…, "del":false} },
    "respawn": { "<labelId>": 72 } }
  ```
- **Merge:**
  - each point (and each pin) is a separate entry; the newer `t` wins
  - un-marking writes `s:"N"` with a timestamp, so the change still syncs
  - the merge is commutative and idempotent (unit-tested)
- **When:**
  - 10 s after the last change (`DriveSyncWorker` with a network constraint)
  - at app start
  - every 6 h
  - from a manual "Sync now" button
- **Steps:** get the remote file → merge → save locally → upload if anything changed.
- **Backups:**
  - a daily local snapshot in `files/backups/`, keeping the last 14
  - export and import a JSON file through the system file picker

---

## 8. Dual-screen behavior
- **Launch:** a small `DisplayRouter` finds the non-default display through `DisplayManager` and opens the main activity there (`ActivityOptions.setLaunchDisplayId`). There's also a "Move to other screen" button. The Thor's own per-app screen setting also works.
- **Controller focus:** the map window has `FLAG_NOT_FOCUSABLE`, so touches work but controller and keyboard input stay with Genshin. The flag is removed only while the search field is being typed in.
- **Display:** `FLAG_KEEP_SCREEN_ON` while tracking; pure-black theme; layouts designed for 1240×1080.
- **Consent dialog:** check which screen the MediaProjection prompt appears on (Phase 0).

---

## 9. Phases

Effort is rough focused-work days for one developer: I write the code, you test on the Thor.

### Phase 0: Feasibility tests (3–5 days). Go/no-go gate
| # | Task | Done when |
|---|---|---|
| 0.1 | Tiny Thor test app: launch on the bottom screen, capture display 0 while Genshin runs, save PNGs | Frames aren't black; Genshin keeps running and keeps sound while the bottom screen is touched; controller stays with the game when `FLAG_NOT_FOCUSABLE` is set; note which screen the consent prompt uses |
| 0.2 | `tools/fetch_pack.py`: download everything, then render points over tiles | Exact pack size known; coordinate transform confirmed visually on 20 known landmarks |
| 0.3 | Screenshot set: about 60 frames from the Thor (`adb exec-out screencap -d <top display id>`) covering city, forest, desert, snow, water, night, Fontaine underwater, caves, Natlan, Nod-Krai | 30 frames labelled with true positions |
| 0.4 | `tools/cv_proto.py`: calibrate scale, then follow mode + whole-map search | Meets §6.7 on the set (on PC), or a clear list of what fails; pick template matching vs feature matching |

### Phase 1: Data layer + offline map viewer (5–7 days)
- Gradle project, Hilt, Room schema, API client, PackManager, OfflinePackWorker (§4.1)
- MapCompose with local tiles for all 4 maps, map switcher, markers with clustering, filter drawer, search, point sheet (read-only)
- DisplayRouter + non-focusable window
- **Done when:** in airplane mode, every map, category and point description opens on the bottom screen, and pan/zoom stays smooth at 60 fps.

### Phase 2: Collectibles (4–6 days)
- Found/hide by tap and long-press, undo, found-item display options, custom pins, notes
- Progress screen, respawn rules and countdowns, local backup and JSON export/import
- **Done when:** marks survive an app restart and a data update (tested by deleting and re-adding points in a fake update).

### Phase 3: Google Drive sync (2–3 days)
- AuthorizationClient, DriveClient, SyncEngine + unit tests (merge properties), DriveSyncWorker, sync status UI
- **Done when:** marks made on the Thor appear on a second device or emulator, and conflicting edits resolve by newest timestamp.

### Phase 4: Live location v1, Teyvat surface (7–10 days)
- TrackingService, FrameSource, calibration UI, MinimapDetector, tracking states (§6.2: loading screen, domain, hidden, lost), local search, full search after loading screens, map-layer detection for caves, facing arrow, Kalman filter, CvIndexBuildWorker (incl. floor overlays)
- Player layer + follow mode + Nearby panel, debug view, adaptive rate
- **Done when:** §6.7 targets are met in a 30-minute real play session. Check the game's fps and temperature with the Thor's performance overlay.

### Phase 5: Auto-update (2–3 days)
- DataUpdateWorker with comparison logic (§4.2), atomic version switch, matching-data rebuild trigger, "what's new" notification, Update now button, last-checked time in Settings
- **Done when:** a simulated new `map_version` and changed point list update correctly with no lost marks.

### Phase 6: Extras (ongoing)
- Matching for Enkanomiya, the Chasm and Ancient Sacred Mountain
- Proximity hints (a soft vibration when an uncollected chest is within reach)
- Route paths (import HoYoLAB recommended routes later: `get_recommend_path`, `get_route_path_detail`)
- A second cached language
- Battery tuning

**Total for a fully working v1 (Phases 0–5):** roughly 5–7 weeks of focused work.

---

## 10. Testing
- **Unit tests:**
  - `MapTransform` (x_pos ↔ pixel ↔ tile, round trips)
  - update comparison
  - LWW merge (property-based: commutative, idempotent)
  - respawn timer maths
- **Image-matching regression:** `tools/cv_eval.py` on the labelled set, run after every matcher change. The same frames run on the device as an instrumented test, to compare speed on the PC and on the Thor.
- **Device checklist (Thor):**
  - launch screen
  - focus behavior
  - consent prompt
  - 30-min session: fps, temperature, battery
  - airplane-mode browsing
  - sync across devices
  - update simulation

---

## 11. Risks
| Risk | Impact | Mitigation |
|---|---|---|
| HoYoLAB changes or protects the API | Updates break (offline data still works) | Everything behind `HoyolabApi`; the response format is checked; the app keeps the last good data |
| Minimap matching unreliable in some biomes | Position jumps or gets lost | Phase 0 gate, masks, confidence gating, whole-map search fallback, labelled regression set |
| Controller input goes to the bottom screen | Annoying while playing | `FLAG_NOT_FOCUSABLE` (verified in Phase 0.1) |
| Capture affects game fps or heat | Worse play | Crop-only processing, adaptive 0.2–4 Hz, measure in Phase 4 |
| Google "Testing" mode token expiry | Sign in again every week | Publish the consent screen to Production (no verification needed for `drive.appdata`) |
| HoYoverse terms | Account risk | Read-only screen capture; no memory, network or input involvement; nothing drawn over the game; personal use only |

---

## 12. Assumptions (change any of these)
1. Data language: English by default and switchable; Vietnamese (`vi-vn`) is supported.
2. Genshin on the top screen at 1920×1080 with the default HUD. Other setups are handled by calibration.
3. Development: Android Studio on this CachyOS machine; the Thor connected with USB debugging (`adb`).
4. Personal use only: sideloaded APK, your own Google Cloud project.
