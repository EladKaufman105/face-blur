#!/usr/bin/env bash
# The minimal OpenCV build (opencv-min branch, made by the opencv-src branch's workflow) into
# jniLibs; the app then leaves out the OpenCV package's full library. The libraries are pinned
# by hash, so the tested build and the released one get the same bits: after rebuilding
# OpenCV, put the new hashes here.
set -euo pipefail
declare -A SHA=(
  [arm64-v8a]=c3cad4dfe2c7f67a6b447c0fa5f139c6440ce2cfd5d90ac20aa97ff5c712d970
  [x86_64]=7e3b763c0238f3bea5257ed7706587b668ab3ad5335a505404c1352a0fb3f660
)
dest=app/src/main/jniLibs
git fetch -q --depth 1 origin opencv-min
for abi in "${!SHA[@]}"; do
  mkdir -p "$dest/$abi"
  git show "FETCH_HEAD:$abi/libopencv_java_min.so" > "$dest/$abi/libopencv_java_min.so"
  echo "${SHA[$abi]}  $dest/$abi/libopencv_java_min.so" | sha256sum -c -
done
