"""RetinaFace (the app's face detector) to TFLite with onnx2tf, then checks the TFLite model at
several input sizes against onnxruntime on real frames."""
import subprocess, numpy as np, cv2, onnxruntime as ort, os, sys, glob
os.makedirs('out/retina', exist_ok=True)
src = 'out/retina/retina_fp32.onnx'
subprocess.run(['onnx2tf', '-i', src, '-o', 'out/retina/tf', '-osd', '-cotof'], check=False)
print(sorted(glob.glob('out/retina/tf/*')))
from ai_edge_litert.interpreter import Interpreter
sess = ort.InferenceSession(src)
cap = cv2.VideoCapture(sys.argv[1]); frames = []
while len(frames) < 4:
    ok, f = cap.read()
    if not ok: break
    frames.append(f)
for tfl in sorted(glob.glob('out/retina/tf/*float32.tflite')) + sorted(glob.glob('out/retina/tf/*float16.tflite')):
    print('==', os.path.basename(tfl), f'{os.path.getsize(tfl)/1e6:.1f} MB')
    for (w, h) in [(1280, 736), (736, 1280), (480, 256), (192, 192)]:
        try:
            it = Interpreter(model_path=tfl)
            inp = it.get_input_details()[0]
            it.resize_tensor_input(inp['index'], [1, h, w, 3]); it.allocate_tensors()
            worst = 0
            for f in frames[:2]:
                img = cv2.resize(f, (w, h)).astype(np.float32) - np.array([104, 117, 123], np.float32)
                ref = sess.run(None, {'input': img.transpose(2, 0, 1)[None]})
                it.set_tensor(inp['index'], img[None]); it.invoke()
                outs = {o['name']: it.get_tensor(o['index']) for o in it.get_output_details()}
                for r in ref:
                    match = [v for v in outs.values() if v.shape == r.shape]
                    if not match: worst = 99; continue
                    worst = max(worst, min(float(np.abs(v - r).max()) for v in match))
            print(f'  {w}x{h}: worst diff vs onnx {worst:.4f}; outputs {[ (o["name"], tuple(o["shape"])) for o in it.get_output_details()]}')
        except Exception as e:
            print(f'  {w}x{h}: FAILED {str(e)[:300]}')
