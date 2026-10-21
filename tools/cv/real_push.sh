#!/bin/bash
# Pushes the real-Thor fixtures (tools/cv/export_real_fixtures.py) to the EMULATOR and runs the real-frame instrumented tests.
#   tools/cv/real_push.sh [--no-test]
# Only ever talks to emulator-5554 (a real Thor may be connected as another serial: never touched).
set -e
ADB="$HOME/Android/Sdk/platform-tools/adb -s emulator-5554"
SRC="$(cd "$(dirname "$0")/../.." && pwd)/data/cv/real_fixtures"
F=/sdcard/Android/data/com.teyvatmap.thor/files
[ -d "$SRC" ] || { echo "run: tools/.venv/bin/python -m tools.cv.export_real_fixtures"; exit 1; }
# adb push of a tree needs the directories to exist (app-private external dir)
$ADB shell "rm -rf $F/real $F/fake/scenarios/real $F/fake/scenarios/real_e2e; mkdir -p $F/real/seq/roi $F/real/seq/thumb $F/real/full $F/real/bigmap $F/fake/scenarios/real/roi $F/fake/scenarios/real/thumb $F/fake/scenarios/real_e2e/frames"
for d in seq/roi seq/thumb full bigmap; do $ADB push "$SRC/$d/." "$F/real/$d/" >/dev/null; done
$ADB push "$SRC/seq/index.json" "$F/real/seq/index.json" >/dev/null
# the debug FakeGameActivity replays these on display 0 (adb shell am start --display 0 -n com.teyvatmap.thor/.debug.FakeGameActivity -e scenario real_e2e --ez loop true)
$ADB push "$SRC/seq/roi/." "$F/fake/scenarios/real/roi/" >/dev/null
$ADB push "$SRC/seq/thumb/." "$F/fake/scenarios/real/thumb/" >/dev/null
$ADB push "$SRC/seq/index.json" "$F/fake/scenarios/real/index.json" >/dev/null
$ADB push "$SRC/e2e/frames/." "$F/fake/scenarios/real_e2e/frames/" >/dev/null
$ADB push "$SRC/e2e/index.json" "$F/fake/scenarios/real_e2e/index.json" >/dev/null
echo "pushed"
[ "$1" = "--no-test" ] || $ADB shell am instrument -w -e class com.teyvatmap.thor.cv.RealSequenceTest,com.teyvatmap.thor.cv.BigMapTest,com.teyvatmap.thor.cv.BannerOcrTest com.teyvatmap.thor.test/androidx.test.runner.AndroidJUnitRunner
