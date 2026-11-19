#!/usr/bin/env bash
# Runs the end-to-end instrumented test on the CI emulator and collects its outputs.
set -x
OUT=e2e-results
mkdir -p "$OUT"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c

timeout 2400 adb shell am instrument -w -e class com.elad.faceblur.EndToEndTest,com.elad.faceblur.EditorScreenTest \
  com.elad.faceblur.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"

adb logcat -d -v time > "$OUT/logcat_full.txt"
grep -iE "faceblur|Transformer|ExoPlayer|MediaCodec|opencv|AndroidRuntime|FATAL|GL error|Exception" "$OUT/logcat_full.txt" | tail -n 1500 > "$OUT/logcat.txt"
rm -f "$OUT/logcat_full.txt"

adb pull /sdcard/Android/data/com.elad.faceblur/files/results "$OUT/" || \
  adb shell "run-as com.elad.faceblur ls -la" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/editor "$OUT/" || true
ls -la "$OUT" "$OUT/results" || true
grep -q "^OK (3 tests)" "$OUT/instrument.txt"
