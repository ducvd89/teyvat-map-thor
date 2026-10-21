"""Export the REAL Thor frames as Android test fixtures (pushed to the emulator, never packed into the APK).

    tools/.venv/bin/python -m tools.cv.export_real_fixtures [--out data/cv/real_fixtures] [--bigmap <dir with bm1.png zoom1.png>]
        [--ui-out app/src/main/resources/cv/bigmap_ui]

Reads tools/dataset/raw (450 frames, 1920x1080, one per 2 s) and tools/dataset/labels.json and writes

  seq/            the whole sequence in the format of `tools/cv/export_fixtures.py` scenarios (so the debug FakeGameActivity can
                  replay it): per frame the minimap ROI (same rectangle as FrameExtractor.roiRect) as lossless webp and the 64x36
                  gray thumbnail; `index.json` holds the real times, the labels (x_pos, y_pos, scale) and the expected state.
                  Only the ROI + thumbnail are stored (the app never reads more), so the player UID in the corner is not in there.
  full/           ~60 full frames spread over the 4 places, both zooms, the white loading frames, the big map, dusk/night, with the
                  UID area blanked + index.json (labels). For the full-frame path (ring / loading / FrameExtractor) tests.
  e2e/            ~25 full frames with compressed timing (town -> big map -> white loading -> tundra); two frames carry a SYNTHETIC
                  area-name banner (PIL text) because the recording has none; replayed by the debug FakeGameActivity on display 0
  bigmap/         bm1 and zoom1 (UID blanked) + truth.json: the player is at (-8223.4, -6098.1) (data/cv/real_report.md)
  <ui-out>/*.png  small glyph templates of the big map's bottom control bar (cut from bm1) for the big-map detector

Push: `adb -s emulator-5554 push data/cv/real_fixtures/. /sdcard/Android/data/com.teyvatmap.thor/files/real/` and
`... push data/cv/real_fixtures/seq /sdcard/Android/data/com.teyvatmap.thor/files/fake/scenarios/real` (see tools/cv/README.md).
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

from tools.cv import screen                      # noqa: E402

RAW = ROOT / "tools" / "dataset" / "raw"
LABELS = ROOT / "tools" / "dataset" / "labels.json"
CALIB = dict(cx=210.5, cy=156.1, r=133.4, s=0.752, frameW=1920, frameH=1080)
MARGIN = 8
UID_BLANK = (1600, 1020, 1920, 1080)     # x0, y0, x1, y1: the bottom-right corner holds the player UID
UID_BLANK_BIGMAP = (1650, 1050, 1920, 1080)   # on the big map the UID sits lower; keep the control bar intact
TRUTH_BIGMAP = (-8223.4, -6098.1)
# bottom control bar glyphs of the big map in bm1 (x0, y0, x1, y1)
UI_PATCHES = {"X": (44, 996, 92, 1044), "gear": (106, 998, 152, 1042), "LT": (886, 998, 934, 1042), "RT": (1148, 998, 1196, 1042),
              "A": (1400, 996, 1448, 1044), "B": (1668, 996, 1716, 1044), "RS": (536, 996, 584, 1048)}


def roi_rect(w=1920, h=1080):
    x0 = max(0, math.floor(CALIB["cx"] - CALIB["r"] - MARGIN))
    y0 = max(0, math.floor(CALIB["cy"] - CALIB["r"] - MARGIN))
    x1 = min(w, math.ceil(CALIB["cx"] + CALIB["r"] + MARGIN) + 1)
    y1 = min(h, math.ceil(CALIB["cy"] + CALIB["r"] + MARGIN) + 1)
    return x0, y0, max(1, x1 - x0), max(1, y1 - y0)


def secs(name):
    return int(name[9:11]) * 3600 + int(name[11:13]) * 60 + int(name[13:15])


def kind_of(i):
    if i <= 46:
        return "town"
    if i in (47, 48):
        return "bigmap"
    if i in (49, 50):
        return "loading"
    return "tundra"


def blank(img, rect=UID_BLANK):
    x0, y0, x1, y1 = rect
    img = img.copy()
    img[y0:y1, x0:x1] = 0
    return img


def synthetic_banner(img, text):
    """Draws an area-name banner (large white serif text with a dark outline, centre-top) on a copy: the e2e test of the OCR path.
    Clearly synthetic -- the recording contains no real banner frame."""
    from PIL import Image, ImageDraw, ImageFont
    pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    d = ImageDraw.Draw(pil)
    font = None
    for f in ("/usr/share/fonts/noto/NotoSerif-Bold.ttf", "/usr/share/fonts/TTF/DejaVuSerif-Bold.ttf", "/usr/share/fonts/noto/NotoSerif-Regular.ttf"):
        if Path(f).exists():
            font = ImageFont.truetype(f, 64)
            break
    font = font or ImageFont.load_default()
    d.text((960, 150), text, font=font, fill=(255, 255, 255), anchor="mm", stroke_width=4, stroke_fill=(40, 55, 80))
    return cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)


def pick_full(n=60):
    """Spread over the sequence: both zooms, the 2 loading + 2 big-map frames, dusk -> night, the walks."""
    must = [0, 1, 20, 31, 32, 38, 44, 46, 47, 48, 49, 50, 51, 52, 53, 100, 150, 209, 210, 215, 225, 236, 237, 300, 330, 360, 380, 400, 420, 440, 449]
    rest = [i for i in range(0, 450, 14) if i not in must]
    return sorted(set(must + rest[: max(0, n - len(must))]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "data" / "cv" / "real_fixtures"))
    ap.add_argument("--bigmap", default="/tmp/claude-1000/-home-cid-Genshin-Interactive-Map/4ec8e6e2-35b0-4778-9467-ae361367505e/scratchpad/thor/bigmap")
    ap.add_argument("--ui-out", default=str(ROOT / "app" / "src" / "main" / "resources" / "cv" / "bigmap_ui"))
    a = ap.parse_args()
    out = Path(a.out)
    names = sorted(p.name for p in RAW.glob("*.png"))
    labels = json.loads(LABELS.read_text())
    x, y, w, h = roi_rect()
    t0 = secs(names[0])
    seq = out / "seq"
    (seq / "roi").mkdir(parents=True, exist_ok=True)
    (seq / "thumb").mkdir(parents=True, exist_ok=True)
    frames = []
    for i, nm in enumerate(names):
        fr = cv2.imread(str(RAW / nm), cv2.IMREAD_COLOR)
        roi = fr[y:y + h, x:x + w]
        cv2.imwrite(str(seq / "roi" / f"{i:03d}.webp"), roi, [cv2.IMWRITE_WEBP_QUALITY, 101])
        cv2.imwrite(str(seq / "thumb" / f"{i:03d}.png"), screen.thumbnail(fr), [cv2.IMWRITE_PNG_COMPRESSION, 9])
        lab = labels.get(nm)
        k = kind_of(i)
        exp = {"town": "TRACKING", "tundra": "TRACKING", "bigmap": "HIDDEN", "loading": "LOADING"}[k]
        frames.append(dict(
            t=float(secs(nm) - t0), file=f"scenarios/real/roi/{i:03d}.webp", thumb=f"scenarios/real/thumb/{i:03d}.png",
            expected=exp, kind=k, inWorld=lab is not None, grace=False, i=i, name=nm,
            x_pos=None if lab is None else lab["x_pos"], y_pos=None if lab is None else lab["y_pos"],
            scale=None if lab is None else lab["scale"], region=None if lab is None else lab["region"],
            py=dict(state=exp),
        ))
    (seq / "index.json").write_text(json.dumps(dict(roi=[x, y, w, h], calib=CALIB, duration=float(secs(names[-1]) - t0) + 2.0, frames=frames), indent=0))
    print("seq:", len(frames), "frames, roi", (x, y, w, h))

    full = out / "full"
    full.mkdir(parents=True, exist_ok=True)
    sel = pick_full()
    rows = []
    for i in sel:
        fr = cv2.imread(str(RAW / names[i]), cv2.IMREAD_COLOR)
        cv2.imwrite(str(full / f"{i:03d}.png"), blank(fr, UID_BLANK_BIGMAP if i in (47, 48) else UID_BLANK), [cv2.IMWRITE_PNG_COMPRESSION, 6])
        lab = labels.get(names[i])
        rows.append(dict(i=i, name=names[i], t=float(secs(names[i]) - t0), kind=kind_of(i), label=lab))
    (full / "index.json").write_text(json.dumps(dict(calib=CALIB, frames=rows), indent=0))
    print("full:", len(sel), "frames")

    # end-to-end replay (FakeGameActivity, full-frame scenario): town -> big map -> teleport (white loading) -> tundra -> night
    e2e = out / "e2e"
    (e2e / "frames").mkdir(parents=True, exist_ok=True)
    plan = [(0, 2), (20, 2), (31, 2), (38, 2), (44, 2), (46, 2), ("bm1", 4), ("bm1", 2), (49, 1), (50, 1), (50, 1), (51, 2), (52, 2), (53, 2), (100, 2), (150, 2),
            (209, 2), (215, 2), (225, 2), (236, 2), (237, 2), (300, 2), (400, 2), (440, 2), (449, 2)]
    ef, t = [], 0.0
    bm = Path(a.bigmap)
    for k, (src, dt) in enumerate(plan):
        if src == "bm1":
            if not (bm / "bm1.png").exists():
                continue
            fr = blank(cv2.imread(str(bm / "bm1.png")), UID_BLANK_BIGMAP)
            kind, lab, exp = "bigmap", None, "HIDDEN"
        else:
            fr = blank(cv2.imread(str(RAW / names[src])), UID_BLANK_BIGMAP if src in (47, 48) else UID_BLANK)
            kind, lab, exp = kind_of(src), labels.get(names[src]), {"town": "TRACKING", "tundra": "TRACKING", "bigmap": "HIDDEN", "loading": "LOADING"}[kind_of(src)]
        if src in (51, 52):
            fr = synthetic_banner(fr, "Lãnh Nguyên Volkodlak")      # SYNTHETIC: no real frame of the area-name banner exists yet
        cv2.imwrite(str(e2e / "frames" / f"{k:02d}.png"), fr, [cv2.IMWRITE_PNG_COMPRESSION, 6])
        ef.append(dict(t=t, full=f"scenarios/real_e2e/frames/{k:02d}.png", expected=exp, kind=kind, src=src, x_pos=None if lab is None else lab["x_pos"], y_pos=None if lab is None else lab["y_pos"]))
        t += dt
    (e2e / "index.json").write_text(json.dumps(dict(roi=[x, y, w, h], calib=CALIB, duration=t, frames=ef), indent=0))
    print("e2e:", len(ef), "frames,", t, "s")

    bm = Path(a.bigmap)
    if (bm / "bm1.png").exists():
        (out / "bigmap").mkdir(parents=True, exist_ok=True)
        for n in ("bm1", "zoom1"):
            cv2.imwrite(str(out / "bigmap" / f"{n}.png"), blank(cv2.imread(str(bm / f"{n}.png")), UID_BLANK_BIGMAP), [cv2.IMWRITE_PNG_COMPRESSION, 6])
        (out / "bigmap" / "truth.json").write_text(json.dumps(dict(x_pos=TRUTH_BIGMAP[0], y_pos=TRUTH_BIGMAP[1], note="player position at the end of the recording, SIFT on bm1..3")))
        ui = Path(a.ui_out)
        ui.mkdir(parents=True, exist_ok=True)
        bm1 = cv2.imread(str(bm / "bm1.png"))
        for k, (x0, y0, x1, y1) in UI_PATCHES.items():
            cv2.imwrite(str(ui / f"{k}.png"), bm1[y0:y1, x0:x1], [cv2.IMWRITE_PNG_COMPRESSION, 9])
        print("bigmap + ui templates written")
    else:
        print("big-map captures not found:", bm)


if __name__ == "__main__":
    main()
