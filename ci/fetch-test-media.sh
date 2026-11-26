#!/usr/bin/env bash
# Fetches the instrumented tests' input (clips from the test-media branch and Whisper "tiny") into
# app/src/androidTest/assets. The app's own models (faces, people, speakers) come from
# ci/fetch-models.sh, pinned by the model registry. Run from the repository root.
set -euo pipefail
git fetch --depth 1 origin test-media
mkdir -p app/src/androidTest/assets/samples
git archive FETCH_HEAD | tar -x -C app/src/androidTest/assets/samples
ls -la app/src/androidTest/assets/samples
# Whisper "tiny" (MIT) for the subtitles test.
mkdir -p app/src/androidTest/assets/models
curl -sSfL --retry 4 --retry-all-errors --max-time 120 -o app/src/androidTest/assets/models/ggml-tiny.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin
ls -la app/src/androidTest/assets/models
