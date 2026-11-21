#!/usr/bin/env bash
# The minimal OpenCV build (opencv-min2 branch, made by the opencv-src branch's workflow) into
# jniLibs; the app then leaves out the OpenCV package's full library. The libraries are pinned
# by hash, so the tested build and the released one get the same bits: after rebuilding
# OpenCV, put the new hashes here.
set -euo pipefail
declare -A SHA=(
  [arm64-v8a]=44fc66a136d3da08d4a19d83792611aa8032954514d4265b2582a8d3ee0be013
  [x86_64]=b099f64f3f4cf6cc4927c23ac56ab5a340d6148e35819c36bd55f23cbb84772c
)
dest=app/src/main/jniLibs
git fetch -q --depth 1 origin opencv-min2
for abi in "${!SHA[@]}"; do
  mkdir -p "$dest/$abi"
  git show "FETCH_HEAD:$abi/libopencv_java_min.so" > "$dest/$abi/libopencv_java_min.so"
  echo "${SHA[$abi]}  $dest/$abi/libopencv_java_min.so" | sha256sum -c -
done
