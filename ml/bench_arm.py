"""On ARM: face detector through OpenCV DNN (the app today) vs TFLite with XNNPACK, and the
segmentation candidates through OpenCV."""
import cv2, numpy as np, time, glob, os
cv2.setNumThreads(os.cpu_count())
def t(fn, n=8):
    fn(); s = time.time()
    for _ in range(n): fn()
    return (time.time() - s) / n * 1000
for p in sorted(glob.glob('out/seg/*.onnx')):
    try:
        n = cv2.dnn.readNetFromONNX(p)
        dims = [x for x in os.path.basename(p).replace('.onnx', '').split('_') if 'x' in x and x[0].isdigit()][0]
        w, h = map(int, dims.split('x'))
        x = np.random.rand(1, 3, h, w).astype(np.float32)
        def r(): n.setInput(x); n.forward()
        print(f'{os.path.basename(p)} opencv: {t(r):.0f} ms')
    except Exception as e:
        print(f'{os.path.basename(p)} failed: {str(e)[:200]}')
pp = cv2.dnn.readNetFromONNX('app/src/main/assets/human_segmentation_pphumanseg_2023mar.onnx')
x = np.random.rand(1, 3, 192, 192).astype(np.float32)
def r2(): pp.setInput(x); pp.forward()
print(f'pphumanseg 192 x3 tiles (today): {3 * t(r2):.0f} ms')
