#!/usr/bin/env bash
# The minimal OpenCV build (opencv-min branch, made by the opencv-src branch's workflow), if
# it's there, into jniLibs; the app then leaves out the OpenCV package's full library.
set -u
dest=app/src/main/jniLibs
if git fetch -q --depth 1 origin opencv-min 2>/dev/null; then
  for abi in arm64-v8a x86_64; do
    if git cat-file -e "FETCH_HEAD:$abi/libopencv_min.so" 2>/dev/null; then
      mkdir -p "$dest/$abi"
      git show "FETCH_HEAD:$abi/libopencv_min.so" > "$dest/$abi/libopencv_min.so"
    fi
  done
  ls -la "$dest"/*/ 2>/dev/null || echo "opencv-min has no libraries"
else
  echo "no opencv-min branch: using the full OpenCV library"
fi
