#!/usr/bin/env bash
# Runs the end-to-end instrumented test on the CI emulator and collects its outputs.
# CI runs it in shards (build.yml matrix), each with its own part of the work:
#   E2E_CLASSES   comma-separated test classes to run (default: all eight)
#   E2E_SELFTEST  1 (default) to finish with the release build's self-test, 0 to skip it
#   E2E_SHARD, E2E_SHARDS  run every E2E_SHARDS-th clip run of EndToEndTest, starting at E2E_SHARD (0-based)
#   E2E_FULL      true: everything (the weekly run): @Weekly tests too, and -e full true (all 13
#                 EndToEndTest clip runs). Otherwise @Weekly tests are left out and EndToEndTest
#                 does its 6 default runs.
set -x
ALL_CLASSES=com.elad.faceblur.EndToEndTest,com.elad.faceblur.EditorScreenTest,com.elad.faceblur.SubtitlesTest,com.elad.faceblur.ProjectsVoiceTest,com.elad.faceblur.LabsTest,com.elad.faceblur.BatchTest,com.elad.faceblur.models.ModelGateTest,com.elad.faceblur.models.ModelDownloaderTest
CLASSES="${E2E_CLASSES:-$ALL_CLASSES}"
SHARD_ARGS=""
[ -n "${E2E_SHARDS:-}" ] && SHARD_ARGS="-e e2eShards $E2E_SHARDS -e e2eShard ${E2E_SHARD:-0}"
if [ "${E2E_FULL:-false}" = true ]; then
  SCOPE_ARGS="-e full true"
else
  SCOPE_ARGS="-e notAnnotation com.elad.faceblur.Weekly"
fi
OUT=e2e-results
mkdir -p "$OUT"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
# A slow emulator can show "app isn't responding" dialogs over the test.
adb shell settings put global hide_error_dialogs 1 || true
# The home screen app isn't needed and its "not responding" dialog steals the keys.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true

timeout 3300 adb shell am instrument -w -e class "$CLASSES" $SHARD_ARGS $SCOPE_ARGS \
  com.elad.faceblur.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"

adb logcat -d -v time > "$OUT/logcat_full.txt"
grep -iE "faceblur|Transformer|ExoPlayer|MediaCodec|opencv|AndroidRuntime|FATAL|GL error|Exception" "$OUT/logcat_full.txt" | tail -n 1500 > "$OUT/logcat.txt"
rm -f "$OUT/logcat_full.txt"

adb pull /sdcard/Android/data/com.elad.faceblur/files/results "$OUT/" || \
  adb shell "run-as com.elad.faceblur ls -la" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/editor "$OUT/" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/subs "$OUT/" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/projects "$OUT/" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/labs "$OUT/" || true
ls -la "$OUT" "$OUT/results" || true
grep -qE "^OK \([0-9]+ tests?\)" "$OUT/instrument.txt" || exit 1
[ "${E2E_SELFTEST:-1}" = 1 ] || exit 0

# The shipped build (minified by R8): installed over the test build, it runs its self-test
# (every native / library pipeline once) on a video copied into its folder. Its files come from the
# test APK (build-test put them in), else they are downloaded again. The model packs (faces with
# the GPU models, people with PP-HumanSeg, speakers) go to selftest/models: the self-test installs
# them through the app's store (registry and SHA-256 checks, under R8) and fails without faces.
adb install -r -g app/build/outputs/apk/release/app-release.apk || exit 1
M=e2e-selftest-media
rm -rf "$M" && mkdir -p "$M"
unzip -q -j -o app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    assets/samples/walk.mp4 assets/samples/speech_two.mp4 assets/models/ggml-tiny.bin \
    assets/models/retinaface_mv2.onnx assets/models/face_detection_yunet_2023mar.onnx \
    assets/models/retinaface-mv2-1280x736.tflite assets/models/retinaface-mv2-736x1280.tflite \
    assets/models/human_segmentation_pphumanseg_2023mar.onnx \
    assets/models/lraspp_mbv3_512x288_fp16.onnx assets/models/lraspp_mbv3_288x512_fp16.onnx \
    assets/models/speaker-wespeaker-r34lm-fp16.onnx -d "$M" || {
  bash ci/fetch-test-media.sh || exit 1
  bash ci/fetch-models.sh || exit 1
  cp app/src/androidTest/assets/samples/walk.mp4 app/src/androidTest/assets/samples/speech_two.mp4 app/src/androidTest/assets/models/* "$M/"
}
ls -la "$M"
ST=/sdcard/Android/data/com.elad.faceblur/files/selftest
adb shell mkdir -p $ST
adb push "$M/walk.mp4" $ST/walk.mp4
adb push "$M/speech_two.mp4" $ST/speech.mp4
adb push "$M/ggml-tiny.bin" $ST/ggml-tiny.bin
adb shell mkdir -p $ST/models
for f in "$M"/*.onnx "$M"/*.tflite; do adb push "$f" $ST/models/; done
adb shell rm -f $ST/report.txt
adb logcat -c
adb shell am start -n com.elad.faceblur/.MainActivity --es selftest walk.mp4
for i in $(seq 1 120); do
  sleep 5
  adb shell cat $ST/report.txt > "$OUT/selftest.txt" 2>/dev/null
  grep -qE "^(PASS|FAIL)" "$OUT/selftest.txt" && break
done
adb shell screencap -p /sdcard/selftest.png && adb pull /sdcard/selftest.png "$OUT/selftest.png" || true
adb logcat -d -v time | grep -iE "AndroidRuntime|FATAL|faceblur|ONIT|R8|NoSuchMethod|ClassNotFound" | tail -n 300 > "$OUT/selftest_logcat.txt"
cat "$OUT/selftest.txt"
status=0
grep -q "^PASS" "$OUT/selftest.txt" || status=1

# For information, never a pass or fail (docs/shared-code.md, 4.2): the release build's cold start,
# `am start -W` TotalTime after a force-stop, 5 times; the median goes in coldstart.txt and the job summary.
for _ in 1 2 3 4 5; do
  timeout 30 adb shell am force-stop com.elad.faceblur
  sleep 2
  timeout 60 adb shell am start -W -n com.elad.faceblur/.MainActivity 2>/dev/null | tr -d '\r' | sed -n 's/^TotalTime: *//p'
done > "$OUT/coldstart.txt" || true
timeout 30 adb shell am force-stop com.elad.faceblur || true
cold=$(grep -E '^[0-9]+$' "$OUT/coldstart.txt" | sort -n | awk '{ a[NR] = $1 } END { if (NR) print a[int((NR + 1) / 2)] }')
echo "cold_start_ms (median of $(grep -cE '^[0-9]+$' "$OUT/coldstart.txt")): ${cold:-none}" | tee -a "$OUT/coldstart.txt"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "Release build cold start (median of 5, emulator): ${cold:-none} ms" >> "$GITHUB_STEP_SUMMARY"
exit $status
