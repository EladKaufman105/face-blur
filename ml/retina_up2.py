"""RetinaFace's FPN resizes go to the size of the next feature map, computed from tensor shapes.
With inputs in multiples of 32 that size is always exactly twice: this rewrites them as fixed 2x
upsampling (identical outputs) so converters see no shape arithmetic."""
import sys, onnx, numpy as np
from onnx import numpy_helper
m = onnx.load(sys.argv[1])
m.graph.initializer.append(numpy_helper.from_array(np.array([1, 1, 2, 2], np.float32), 'up2_scales'))
for n in m.graph.node:
    if n.op_type == 'Resize':
        ins = list(n.input); ins[2] = 'up2_scales'
        del n.input[:]; n.input.extend(ins[:3])
nodes = list(m.graph.node)
while True:
    need = {o.name for o in m.graph.output}
    for n in nodes: need.update(n.input)
    keep = [n for n in nodes if any(o in need for o in n.output)]
    if len(keep) == len(nodes): break
    nodes = keep
del m.graph.node[:]; m.graph.node.extend(nodes)
onnx.checker.check_model(m)
onnx.save(m, sys.argv[2])
