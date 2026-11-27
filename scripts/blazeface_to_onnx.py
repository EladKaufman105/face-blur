#!/usr/bin/env python3
"""Converts MediaPipe's BlazeFace TFLite models (Apache-2.0) to ONNX for the web app.

Usage: python3 scripts/blazeface_to_onnx.py blaze_face_full_range.tflite models/blazeface_full_range.onnx
Needs: pip install tflite onnx numpy   (no TensorFlow). Handles exactly the ops BlazeFace uses
(DEQUANTIZE of constants, CONV_2D, DEPTHWISE_CONV_2D, RELU, ADD, PAD, MAX_POOL_2D, RESIZE_BILINEAR,
RESHAPE) and stops on any other, so a different model never converts silently wrong.

The ONNX takes the image as NCHW float32 [1,3,S,S] in -1..1 (TFLite takes NHWC) and returns
`regressors` [1,N,16] and `scores` [1,N,1] (raw logits), as the TFLite model's two outputs.
"""
import sys
import numpy as np
import onnx
import tflite
from onnx import TensorProto, helper, numpy_helper

NAMES = {v: k for k, v in vars(tflite.BuiltinOperator).items() if not k.startswith('_')}
F32, F16, I32 = 0, 1, 2


def main(src, dst):
    m = tflite.Model.GetRootAsModel(open(src, 'rb').read(), 0)
    sg = m.Subgraphs(0)

    def const(i):
        t = sg.Tensors(i)
        buf = m.Buffers(t.Buffer())
        if buf.DataLength() == 0:
            return None
        dt = {F32: np.float32, F16: np.float16, I32: np.int32}[t.Type()]
        return np.frombuffer(buf.DataAsNumpy().tobytes(), dtype=dt).reshape(t.ShapeAsNumpy())

    nodes, inits, vals = [], [], {}
    name = lambda i: f't{i}'

    def add_const(i, arr):
        inits.append(numpy_helper.from_array(arr, name(i)))
        vals[i] = arr

    def tfl_pad(size, k, s):
        out = -(-size // s)
        total = max((out - 1) * s + k - size, 0)
        return total // 2, total - total // 2

    def pads(op_in, kh, kw, sh, sw, padding):
        if padding == 1:  # VALID
            return [0, 0, 0, 0]
        h, w = sg.Tensors(op_in).ShapeAsNumpy()[1:3]
        t, b = tfl_pad(int(h), kh, sh)
        l, r = tfl_pad(int(w), kw, sw)
        return [t, l, b, r]

    def act(op_out, fused, src_name):
        if fused == 0:
            return src_name
        assert fused == 1, f'fused activation {fused}'
        nodes.append(helper.make_node('Relu', [src_name], [name(op_out) + '_a']))
        return name(op_out) + '_a'

    S = int(sg.Tensors(sg.Inputs(0)).ShapeAsNumpy()[1])
    inp = sg.Inputs(0)
    for i in range(sg.OperatorsLength()):
        op = sg.Operators(i)
        code = m.OperatorCodes(op.OpcodeIndex())
        kind = NAMES[max(code.BuiltinCode(), code.DeprecatedBuiltinCode())]
        ins, outs = list(op.InputsAsNumpy()), list(op.OutputsAsNumpy())
        if kind == 'DEQUANTIZE':
            c = const(ins[0])
            assert c is not None, 'DEQUANTIZE of a non-constant'
            add_const(outs[0], c.astype(np.float32))
        elif kind in ('CONV_2D', 'DEPTHWISE_CONV_2D'):
            o = tflite.Conv2DOptions() if kind == 'CONV_2D' else tflite.DepthwiseConv2DOptions()
            o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
            assert o.DilationHFactor() == 1 and o.DilationWFactor() == 1
            w = vals[ins[1]] if ins[1] in vals else const(ins[1]).astype(np.float32)
            b = vals[ins[2]] if ins[2] in vals else const(ins[2]).astype(np.float32)
            if kind == 'CONV_2D':
                kh, kw, group = w.shape[1], w.shape[2], 1
                w = w.transpose(0, 3, 1, 2)  # OHWI -> OIHW
            else:
                assert o.DepthMultiplier() == 1
                kh, kw, group = w.shape[1], w.shape[2], w.shape[3]
                w = w.transpose(3, 0, 1, 2)  # 1HWC -> C1HW
            wn, bn = name(outs[0]) + '_w', name(outs[0]) + '_b'
            inits.append(numpy_helper.from_array(np.ascontiguousarray(w), wn))
            inits.append(numpy_helper.from_array(b, bn))
            src_n = 'input' if ins[0] == inp else name(ins[0])
            res = name(outs[0]) + '_c'
            nodes.append(helper.make_node('Conv', [src_n, wn, bn], [res], kernel_shape=[kh, kw], group=group,
                                          strides=[o.StrideH(), o.StrideW()],
                                          pads=pads(ins[0], kh, kw, o.StrideH(), o.StrideW(), o.Padding())))
            fin = act(outs[0], o.FusedActivationFunction(), res)
            nodes.append(helper.make_node('Identity', [fin], [name(outs[0])]))
        elif kind == 'RELU':
            nodes.append(helper.make_node('Relu', [name(ins[0])], [name(outs[0])]))
        elif kind == 'ADD':
            o = tflite.AddOptions()
            o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
            res = name(outs[0]) + '_s'
            nodes.append(helper.make_node('Add', [name(ins[0]), name(ins[1])], [res]))
            nodes.append(helper.make_node('Identity', [act(outs[0], o.FusedActivationFunction(), res)], [name(outs[0])]))
        elif kind == 'PAD':
            p = const(ins[1])  # NHWC [[bn,en],[bh,eh],[bw,ew],[bc,ec]]
            order = [0, 3, 1, 2]
            pv = np.array([p[a][0] for a in order] + [p[a][1] for a in order], dtype=np.int64)
            pn = name(outs[0]) + '_p'
            inits.append(numpy_helper.from_array(pv, pn))
            nodes.append(helper.make_node('Pad', [name(ins[0]), pn], [name(outs[0])], mode='constant'))
        elif kind == 'MAX_POOL_2D':
            o = tflite.Pool2DOptions()
            o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
            assert o.FusedActivationFunction() == 0
            nodes.append(helper.make_node('MaxPool', [name(ins[0])], [name(outs[0])],
                                          kernel_shape=[o.FilterHeight(), o.FilterWidth()],
                                          strides=[o.StrideH(), o.StrideW()],
                                          pads=pads(ins[0], o.FilterHeight(), o.FilterWidth(), o.StrideH(), o.StrideW(), o.Padding())))
        elif kind == 'RESIZE_BILINEAR':
            o = tflite.ResizeBilinearOptions()
            o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
            size = const(ins[1])
            mode = 'align_corners' if o.AlignCorners() else ('half_pixel' if o.HalfPixelCenters() else 'asymmetric')
            c = int(sg.Tensors(ins[0]).ShapeAsNumpy()[3])
            sn, rn = name(outs[0]) + '_sz', name(outs[0]) + '_roi'
            inits.append(numpy_helper.from_array(np.array([1, c, int(size[0]), int(size[1])], dtype=np.int64), sn))
            inits.append(numpy_helper.from_array(np.zeros(0, dtype=np.float32), rn))
            inits.append(numpy_helper.from_array(np.zeros(0, dtype=np.float32), name(outs[0]) + '_sc'))
            nodes.append(helper.make_node('Resize', [name(ins[0]), rn, name(outs[0]) + '_sc', sn], [name(outs[0])],
                                          mode='linear', coordinate_transformation_mode=mode))
        elif kind == 'RESHAPE':
            shp = [int(x) for x in sg.Tensors(outs[0]).ShapeAsNumpy()]
            # NCHW -> NHWC first, so the flat order is tflite's (anchor-major: y, x, then the anchor's values)
            tn, sn = name(outs[0]) + '_t', name(outs[0]) + '_shape'
            nodes.append(helper.make_node('Transpose', [name(ins[0])], [tn], perm=[0, 2, 3, 1]))
            inits.append(numpy_helper.from_array(np.array(shp, dtype=np.int64), sn))
            nodes.append(helper.make_node('Reshape', [tn, sn], [name(outs[0])]))
        else:
            sys.exit(f'op {kind} is not handled')

    o_names = {sg.Outputs(k): sg.Tensors(sg.Outputs(k)).ShapeAsNumpy() for k in range(sg.OutputsLength())}
    reg = [i for i, s in o_names.items() if s[2] == 16][0]
    sco = [i for i, s in o_names.items() if s[2] == 1][0]
    n_anchor = int(o_names[reg][1])
    nodes.append(helper.make_node('Identity', [name(reg)], ['regressors']))
    nodes.append(helper.make_node('Identity', [name(sco)], ['scores']))
    used = {x for n in nodes for x in n.input}
    inits = [t for t in inits if t.name in used]  # the DEQUANTIZE outputs a conv re-laid-out are not needed
    g = helper.make_graph(
        nodes, 'blazeface',
        [helper.make_tensor_value_info('input', TensorProto.FLOAT, [1, 3, S, S])],
        [helper.make_tensor_value_info('regressors', TensorProto.FLOAT, [1, n_anchor, 16]),
         helper.make_tensor_value_info('scores', TensorProto.FLOAT, [1, n_anchor, 1])],
        initializer=inits)
    model = helper.make_model(g, opset_imports=[helper.make_opsetid('', 17)], producer_name='blazeface_to_onnx')
    model.ir_version = 8
    onnx.checker.check_model(model)
    onnx.save(model, dst)
    print(f'{dst}: {len(nodes)} nodes, input {S}x{S}, {n_anchor} anchors')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
