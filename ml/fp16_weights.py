"""Rewrites a float32 TFLite model so conv / fully-connected weights are stored as float16 and
DEQUANTIZEd to float32 at load (TFLite's standard 'float16 quantization'): half the size, same
float32 inputs, outputs and math. GPU delegates read the float16 weights directly."""
import sys, importlib.util, numpy as np, flatbuffers
spec = importlib.util.spec_from_file_location('schema', sys.argv[3]); S = importlib.util.module_from_spec(spec); spec.loader.exec_module(S)
buf = open(sys.argv[1], 'rb').read()
m = S.ModelT.InitFromObj(S.Model.GetRootAsModel(buf, 0))
codes = m.operatorCodes
def code_of(op): c = codes[op.opcodeIndex]; return max(c.builtinCode, c.deprecatedBuiltinCode)
deq = next((i for i, c in enumerate(codes) if max(c.builtinCode, c.deprecatedBuiltinCode) == S.BuiltinOperator.DEQUANTIZE), None)
if deq is None:
    c = S.OperatorCodeT(); c.builtinCode = S.BuiltinOperator.DEQUANTIZE; c.deprecatedBuiltinCode = S.BuiltinOperator.DEQUANTIZE; c.version = 2
    codes.append(c); deq = len(codes) - 1
WEIGHTED = {S.BuiltinOperator.CONV_2D, S.BuiltinOperator.DEPTHWISE_CONV_2D, S.BuiltinOperator.FULLY_CONNECTED, S.BuiltinOperator.TRANSPOSE_CONV}
done = 0
for sg in m.subgraphs:
    converted = {}
    new_ops = []
    for op in sg.operators:
        if code_of(op) in WEIGHTED:
            wi = op.inputs[1] if code_of(op) != S.BuiltinOperator.TRANSPOSE_CONV else op.inputs[1]
            t = sg.tensors[wi]
            b = m.buffers[t.buffer] if t.buffer < len(m.buffers) else None
            if wi not in converted and t.type == S.TensorType.FLOAT32 and b is not None and b.data is not None and len(b.data) > 0:
                w = np.frombuffer(bytes(bytearray(b.data)), dtype=np.float32)
                nb = S.BufferT(); nb.data = np.frombuffer(w.astype(np.float16).tobytes(), dtype=np.uint8)
                m.buffers.append(nb)
                nt = S.TensorT(); nt.shape = t.shape; nt.type = S.TensorType.FLOAT16; nt.buffer = len(m.buffers) - 1
                nt.name = (t.name or b'w') + b'_fp16' if isinstance(t.name, bytes) else str(t.name) + '_fp16'
                sg.tensors.append(nt)
                b.data = None; b.offset = 0; b.size = 0
                d = S.OperatorT(); d.opcodeIndex = deq; d.inputs = [len(sg.tensors) - 1]; d.outputs = [wi]
                new_ops.append(d); converted[wi] = True; done += 1
    sg.operators = new_ops + list(sg.operators)
# A real default input size (the signature stays dynamic): delegates are applied before the
# first resize, and a 1x1 input would leave nothing to build.
if len(sys.argv) > 4:
    h, w = map(int, sys.argv[4].split('x'))
    for sg in m.subgraphs:
        for i in sg.inputs:
            sg.tensors[i].shape = np.array([1, h, w, 3], dtype=np.int32)
b = flatbuffers.Builder(1024)
b.Finish(m.Pack(b), file_identifier=b'TFL3')
open(sys.argv[2], 'wb').write(b.Output())
print('weights to float16:', done)
