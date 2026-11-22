"""The Android app ships the face model with float16 weights (OpenCV reads them as float32).
ONNX Runtime doesn't mix the two, so the web app gets a float32 copy.

Run: python3 model_fp32.py <in.onnx> <out.onnx>   (needs onnx and numpy)
"""
import sys
import numpy as np
import onnx
from onnx import numpy_helper, TensorProto

m = onnx.load(sys.argv[1])

def fix(t):
    if t.data_type == TensorProto.FLOAT16:
        a = numpy_helper.to_array(t).astype(np.float32)
        t.CopyFrom(numpy_helper.from_array(a, t.name))

for t in m.graph.initializer:
    fix(t)
for n in m.graph.node:
    for a in n.attribute:
        if a.type == onnx.AttributeProto.TENSOR:
            fix(a.t)
        if n.op_type == 'Cast' and a.name == 'to' and a.i == TensorProto.FLOAT16:
            a.i = TensorProto.FLOAT
for vi in list(m.graph.value_info):
    if vi.type.tensor_type.elem_type == TensorProto.FLOAT16:
        vi.type.tensor_type.elem_type = TensorProto.FLOAT
onnx.checker.check_model(m)
onnx.save(m, sys.argv[2])
