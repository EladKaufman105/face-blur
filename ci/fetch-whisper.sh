#!/usr/bin/env bash
# Fetches whisper.cpp (MIT) at a pinned release into app/src/main/cpp/whisper.cpp for the native build.
set -euo pipefail
TAG=v1.9.5
DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/whisper.cpp"
if [ -f "$DIR/include/whisper.h" ] && grep -q "$TAG" "$DIR/.fetched-tag" 2>/dev/null; then exit 0; fi
rm -rf "$DIR"
git clone -q --depth 1 -b "$TAG" https://github.com/ggml-org/whisper.cpp "$DIR"
echo "$TAG" > "$DIR/.fetched-tag"
