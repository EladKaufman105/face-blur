#!/usr/bin/env bash
# The face detector as static TFLite models for LiteRT's GPU delegate (published on the
# gpu-model branch). Needs: pip install tensorflow-cpu onnx2tf onnx onnx_graphsurgeon sng4onnx psutil ai-edge-litert
# Input: the float32 RetinaFace ONNX (git show d220a08~1:app/src/main/assets/retinaface_mv2.onnx).
set -euo pipefail
src=$1; out=${2:-out}
mkdir -p "$out"
python ml/retina_up2.py "$src" "$out/retina_up2.onnx"
for size in 1280x736 736x1280; do
  w=${size%x*}; h=${size#*x}
  onnx2tf -i "$out/retina_up2.onnx" -o "$out/tf_$size" -b 1 -ois "input:1,3,$h,$w"
  python ml/fp16_weights.py "$out/tf_$size/retina_up2_float32.tflite" "$out/retinaface-mv2-$size.tflite" "$out/tf_$size/schema_generated.py"
done
