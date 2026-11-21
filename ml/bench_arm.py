"""On ARM: face detector through OpenCV DNN (the app today) vs TFLite with XNNPACK, and the
segmentation candidates through OpenCV."""
import cv2, numpy as np, time, glob, os
from ai_edge_litert.interpreter import Interpreter
cv2.setNumThreads(os.cpu_count())
def t(fn, n=8):
    fn(); s = time.time()
    for _ in range(n): fn()
    return (time.time() - s) / n * 1000
img = np.random.rand(736, 1280, 3).astype(np.float32) * 255 - 110
net = cv2.dnn.readNetFromONNX('out/retina/retina_fp32.onnx')
blob = img.transpose(2, 0, 1)[None].copy()
def cvrun(): net.setInput(blob); net.forward(['loc', 'conf', 'landmarks'])
print(f'retina 1280x736 opencv fp32: {t(cvrun):.0f} ms')
for tfl in sorted(glob.glob('out/retina/tf/*.tflite')):
    for threads in (os.cpu_count(), 2):
        try:
            it = Interpreter(model_path=tfl, num_threads=threads)
            inp = it.get_input_details()[0]
            it.resize_tensor_input(inp['index'], [1, 736, 1280, 3]); it.allocate_tensors()
            it.set_tensor(inp['index'], img[None].astype(inp['dtype']))
            print(f'retina 1280x736 {os.path.basename(tfl)} threads={threads}: {t(it.invoke):.0f} ms')
        except Exception as e:
            print(f'{os.path.basename(tfl)} failed: {str(e)[:200]}')
for p in sorted(glob.glob('out/seg/*.onnx')):
    try:
        n = cv2.dnn.readNetFromONNX(p)
        w, h = map(int, os.path.basename(p).split('_')[-1].replace('fp16', '').replace('.onnx', '').strip('_').split('x')) if 'fp16' not in p else map(int, os.path.basename(p).split('_')[-2].split('x'))
        x = np.random.rand(1, 3, h, w).astype(np.float32)
        def r(): n.setInput(x); n.forward()
        print(f'{os.path.basename(p)} opencv: {t(r):.0f} ms')
    except Exception as e:
        print(f'{os.path.basename(p)} failed: {str(e)[:200]}')
pp = cv2.dnn.readNetFromONNX('app/src/main/assets/human_segmentation_pphumanseg_2023mar.onnx')
x = np.random.rand(1, 3, 192, 192).astype(np.float32)
def r2(): pp.setInput(x); pp.forward()
print(f'pphumanseg 192 x3 tiles (today): {3 * t(r2):.0f} ms')
