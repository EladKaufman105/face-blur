#!/usr/bin/env bash
# Fetches the instrumented tests' input (clips from the test-media branch and the models the app
# downloads at runtime) into app/src/androidTest/assets. Run from the repository root.
set -euo pipefail
git fetch --depth 1 origin test-media
mkdir -p app/src/androidTest/assets/samples
git archive FETCH_HEAD | tar -x -C app/src/androidTest/assets/samples
ls -la app/src/androidTest/assets/samples
# Whisper "tiny" (MIT) for the subtitles test.
mkdir -p app/src/androidTest/assets/models
curl -sSfL --retry 4 --retry-all-errors --max-time 120 -o app/src/androidTest/assets/models/ggml-tiny.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin
# The speaker model (WeSpeaker, CC BY 4.0), as the app downloads it.
curl -sSfL --retry 4 --retry-all-errors --max-time 120 -o app/src/androidTest/assets/models/speaker.onnx \
  https://raw.githubusercontent.com/EladKaufman105/face-blur/speaker-model/speaker-wespeaker-r34lm-fp16.onnx
# Background removal's second model (LR-ASPP, BSD-3), as the app downloads it.
for m in lraspp_mbv3_512x288_fp16.onnx lraspp_mbv3_288x512_fp16.onnx; do
  curl -sSfL --retry 4 --retry-all-errors --max-time 120 -o app/src/androidTest/assets/models/$m \
    https://raw.githubusercontent.com/EladKaufman105/face-blur/187ecd0f0bdb7e082b132a0e844e3a6785fb463b/seg/$m
done
ls -la app/src/androidTest/assets/models
