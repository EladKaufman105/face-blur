#!/usr/bin/env bash
# Runs the end-to-end instrumented test on the CI emulator and collects its outputs.
set -x
OUT=e2e-results
mkdir -p "$OUT"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
# A slow emulator can show "app isn't responding" dialogs over the test.
adb shell settings put global hide_error_dialogs 1 || true
# The home screen app isn't needed and its "not responding" dialog steals the keys.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true

timeout 3300 adb shell am instrument -w -e class com.elad.faceblur.EndToEndTest,com.elad.faceblur.EditorScreenTest,com.elad.faceblur.SubtitlesTest,com.elad.faceblur.ProjectsVoiceTest \
  com.elad.faceblur.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"

adb logcat -d -v time > "$OUT/logcat_full.txt"
grep -iE "faceblur|Transformer|ExoPlayer|MediaCodec|opencv|AndroidRuntime|FATAL|GL error|Exception" "$OUT/logcat_full.txt" | tail -n 1500 > "$OUT/logcat.txt"
rm -f "$OUT/logcat_full.txt"

adb pull /sdcard/Android/data/com.elad.faceblur/files/results "$OUT/" || \
  adb shell "run-as com.elad.faceblur ls -la" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/editor "$OUT/" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/subs "$OUT/" || true
adb pull /sdcard/Android/data/com.elad.faceblur/files/projects "$OUT/" || true
ls -la "$OUT" "$OUT/results" || true
grep -q "^OK (10 tests)" "$OUT/instrument.txt" || exit 1

# The shipped build (minified by R8): installed over the test build, it runs its self-test
# (every native / library pipeline once) on a video copied into its folder.
adb install -r -g app/build/outputs/apk/release/app-release.apk || exit 1
ST=/sdcard/Android/data/com.elad.faceblur/files/selftest
adb shell mkdir -p $ST
adb push app/src/androidTest/assets/samples/walk.mp4 $ST/walk.mp4
adb push app/src/androidTest/assets/samples/speech_two.mp4 $ST/speech.mp4
adb push app/src/androidTest/assets/models/ggml-tiny.bin $ST/ggml-tiny.bin
adb push app/src/androidTest/assets/models/speaker.onnx $ST/speaker.onnx
adb push app/src/androidTest/assets/models/lraspp_mbv3_512x288_fp16.onnx $ST/
adb push app/src/androidTest/assets/models/lraspp_mbv3_288x512_fp16.onnx $ST/
adb shell rm -f $ST/report.txt
adb logcat -c
adb shell am start -n com.elad.faceblur/.MainActivity --es selftest walk.mp4
for i in $(seq 1 120); do
  sleep 5
  adb shell cat $ST/report.txt > "$OUT/selftest.txt" 2>/dev/null
  grep -qE "^(PASS|FAIL)" "$OUT/selftest.txt" && break
done
adb shell screencap -p /sdcard/selftest.png && adb pull /sdcard/selftest.png "$OUT/selftest.png" || true
adb logcat -d -v time | grep -iE "AndroidRuntime|FATAL|faceblur|EDITIT|R8|NoSuchMethod|ClassNotFound" | tail -n 300 > "$OUT/selftest_logcat.txt"
cat "$OUT/selftest.txt"
grep -q "^PASS" "$OUT/selftest.txt"
