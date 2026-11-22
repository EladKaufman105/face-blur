"""The face model for the web app, from the Android app's (which ships float16 weights that
OpenCV reads as float32; ONNX Runtime doesn't mix the two):
  - retinaface_fp32.onnx: float32 throughout (the CPU, and GPUs without half precision);
  - retinaface_fp16.onnx: float16 inside, float32 input and output (GPUs with shader-f16).

Run: python3 model_web.py <in.onnx> <out folder>   (needs onnx, numpy, onnxconverter-common)
"""
import os
import sys
import numpy as np
import onnx
from onnx import numpy_helper, TensorProto
from onnxconverter_common import float16

m = onnx.load(sys.argv[1])
out = sys.argv[2]

def widen(t):
    if t.data_type == TensorProto.FLOAT16:
        t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float32), t.name))

for t in m.graph.initializer:
    widen(t)
for n in m.graph.node:
    for a in n.attribute:
        if a.type == onnx.AttributeProto.TENSOR:
            widen(a.t)
        if n.op_type == 'Cast' and a.name == 'to' and a.i == TensorProto.FLOAT16:
            a.i = TensorProto.FLOAT
for vi in list(m.graph.value_info):
    if vi.type.tensor_type.elem_type == TensorProto.FLOAT16:
        vi.type.tensor_type.elem_type = TensorProto.FLOAT
onnx.checker.check_model(m)
os.makedirs(out, exist_ok=True)
onnx.save(m, os.path.join(out, 'retinaface_fp32.onnx'))
onnx.save(float16.convert_float_to_float16(m, keep_io_types=True), os.path.join(out, 'retinaface_fp16.onnx'))
