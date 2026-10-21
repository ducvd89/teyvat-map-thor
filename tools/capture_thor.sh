#!/usr/bin/env bash
# capture_thor.sh - grab screenshots of the Thor's top (1920x1080) screen over adb.
#
# Usage:
#   tools/capture_thor.sh [-i SECONDS] [-n MAX_FRAMES] [-o OUT_DIR] [-d DISPLAY_ID] [-s ADB_SERIAL]
#
#   -i  interval between captures in seconds (default 2; fractional OK, e.g. 0.5)
#   -n  stop after this many frames (default: run until Ctrl+C)
#   -o  output directory (default: tools/dataset/raw next to this script)
#   -d  force a SurfaceFlinger display id instead of auto-detecting
#   -s  adb serial when several devices are attached
#
# The top/internal display is the first id printed by
#   adb shell dumpsys SurfaceFlinger --display-id
# (the "default" display, 1920x1080 on the AYN Thor). If detection fails the
# script falls back to `screencap` without -d, which captures the default display.
# Files are named YYYYmmdd-HHMMSS.png (a numeric suffix is added on collisions).
# Frames that are not valid PNGs (e.g. a failed screencap) are discarded.
#
# Tip: move around the open world between shots; vary biome, time of day, UI.
# Afterwards label them with:  tools/.venv/bin/python -m tools.cv.label_helper (see tools/cv/README.md)
set -u
INTERVAL=2; MAXN=0; DISPLAY_ID=""; SERIAL=""
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/dataset/raw"

while getopts "i:n:o:d:s:h" opt; do
  case "$opt" in
    i) INTERVAL="$OPTARG" ;;
    n) MAXN="$OPTARG" ;;
    o) OUT="$OPTARG" ;;
    d) DISPLAY_ID="$OPTARG" ;;
    s) SERIAL="$OPTARG" ;;
    h|*) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
  esac
done

command -v adb >/dev/null 2>&1 || { echo "adb not found in PATH" >&2; exit 1; }
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
mkdir -p "$OUT"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "No adb device in 'device' state. Check USB/wireless debugging (adb devices)." >&2
  exit 1
fi

if [ -z "$DISPLAY_ID" ]; then
  # Output looks like: "Display 4619827259835644672 (HWC display 0): port=0 ... " (Android 12+)
  # or "Display 0 ..." / bare ids on other builds. Take the first id (the internal/default panel).
  IDS="$("${ADB[@]}" shell dumpsys SurfaceFlinger --display-id 2>/dev/null | tr -d '\r' \
        | grep -oE 'Display [0-9]+' | awk '{print $2}')"
  DISPLAY_ID="$(printf '%s\n' "$IDS" | head -n1)"
  if [ -n "$DISPLAY_ID" ]; then
    echo "Displays found: $(printf '%s' "$IDS" | tr '\n' ' ')"
    echo "Using top display id: $DISPLAY_ID"
  else
    echo "Could not detect display id; falling back to screencap without -d" >&2
  fi
fi

trap 'echo; echo "Stopped after $COUNT frame(s). Output: $OUT"; exit 0' INT TERM
COUNT=0
echo "Capturing every ${INTERVAL}s into $OUT (Ctrl+C to stop)"
while :; do
  START=$(date +%s.%N)
  NAME="$(date +%Y%m%d-%H%M%S)"
  F="$OUT/$NAME.png"; K=1
  while [ -e "$F" ]; do F="$OUT/${NAME}_$K.png"; K=$((K+1)); done
  TMP="$F.part"
  if [ -n "$DISPLAY_ID" ]; then
    "${ADB[@]}" exec-out screencap -p -d "$DISPLAY_ID" > "$TMP" 2>/dev/null
  else
    "${ADB[@]}" exec-out screencap -p > "$TMP" 2>/dev/null
  fi
  # PNG magic check
  if [ -s "$TMP" ] && [ "$(head -c 4 "$TMP" | tail -c 3)" = "PNG" ]; then
    mv "$TMP" "$F"; COUNT=$((COUNT+1)); echo "[$COUNT] $F"
  else
    rm -f "$TMP"; echo "capture failed (device busy/offline?), retrying" >&2
  fi
  if [ "$MAXN" -gt 0 ] && [ "$COUNT" -ge "$MAXN" ]; then break; fi
  # sleep for the remainder of the interval (awk instead of bc, which is often missing)
  REST="$(awk -v s="$START" -v e="$(date +%s.%N)" -v i="$INTERVAL" 'BEGIN{r=i-(e-s); if(r<0)r=0; printf "%.3f", r}')"
  sleep "$REST"
done
echo "Done: $COUNT frame(s) in $OUT"
