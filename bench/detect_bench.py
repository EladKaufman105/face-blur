"""Speed of EDITIT's per-frame face detection with OpenCV DNN on an ARM CPU: fp32 vs fp16
target, and one detector using all threads vs several detectors side by side."""
import sys, time, threading, queue
import cv2, numpy as np

RETINA, YUNET, VIDEO = sys.argv[1], sys.argv[2], sys.argv[3]
cv2.setNumThreads(cv2.getNumberOfCPUs())
print("opencv", cv2.__version__, "cpus", cv2.getNumberOfCPUs())
print("fp16 support:", cv2.checkHardwareSupport(cv2.CPU_NEON_FP16) if hasattr(cv2, "CPU_NEON_FP16") else "?")

cap = cv2.VideoCapture(VIDEO)
frames = []
while len(frames) < 40:
    ok, f = cap.read()
    if not ok: break
    s = min(1.0, 1280 / max(f.shape[:2]))
    frames.append(cv2.resize(f, None, fx=s, fy=s, interpolation=cv2.INTER_AREA))
print("frames", len(frames), frames[0].shape)

def blob(img, side):
    h, w = img.shape[:2]
    s = min(1.0, side / max(w, h))
    iw, ih = max(32, round(w * s / 32) * 32), max(32, round(h * s / 32) * 32)
    return cv2.dnn.blobFromImage(cv2.resize(img, (iw, ih), interpolation=cv2.INTER_AREA), 1.0, (iw, ih), (104, 117, 123), False, False)

class Det:
    def __init__(self, target):
        self.net = cv2.dnn.readNetFromONNX(RETINA)
        self.net.setPreferableTarget(target)
        self.yn = None
    def run(self, img):
        h, w = img.shape[:2]
        outs = []
        for side in (max(w, h), 480):
            self.net.setInput(blob(img, side))
            outs.append(self.net.forward(["loc", "conf", "landmarks"]))
        if self.yn is None:
            self.yn = cv2.FaceDetectorYN.create(YUNET, "", (w, h), 0.4, 0.3, 5000)
        self.yn.detect(img)
        return outs

def timed(fn, n):
    t = time.perf_counter()
    for i in range(n): fn(i)
    return (time.perf_counter() - t) / n * 1000

for name, target in (("fp32", cv2.dnn.DNN_TARGET_CPU), ("fp16", cv2.dnn.DNN_TARGET_CPU_FP16)):
    d = Det(target)
    for f in frames[:3]: d.run(f)
    ms = timed(lambda i: d.run(frames[i % len(frames)]), len(frames))
    print(f"{name}: {ms:.1f} ms/frame (retina 2 passes + yunet)")

a, b = Det(cv2.dnn.DNN_TARGET_CPU), Det(cv2.dnn.DNN_TARGET_CPU_FP16)
worst = 0.0
for f in frames[:10]:
    for (l1, c1, _), (l2, c2, _) in zip(a.run(f), b.run(f)):
        p1 = np.exp(c1[..., 1]) / (np.exp(c1[..., 0]) + np.exp(c1[..., 1])) if c1.min() < 0 or c1.max() > 1 else c1[..., 1]
        p2 = np.exp(c2[..., 1]) / (np.exp(c2[..., 0]) + np.exp(c2[..., 1])) if c2.min() < 0 or c2.max() > 1 else c2[..., 1]
        worst = max(worst, float(np.abs(p1 - p2).max()), float(np.abs(l1 - l2).max()))
print(f"fp16 vs fp32 worst diff (scores, boxes): {worst:.4f}")

def throughput(workers, target, threads):
    cv2.setNumThreads(threads)
    dets = [Det(target) for _ in range(workers)]
    for d in dets: d.run(frames[0])
    q = queue.Queue()
    for i in range(len(frames) * 2): q.put(i)
    def work(d):
        while True:
            try: i = q.get_nowait()
            except queue.Empty: return
            d.run(frames[i % len(frames)])
    t = time.perf_counter()
    th = [threading.Thread(target=work, args=(d,)) for d in dets]
    for x in th: x.start()
    for x in th: x.join()
    return len(frames) * 2 / (time.perf_counter() - t)

n = cv2.getNumberOfCPUs()
for target, tn in ((cv2.dnn.DNN_TARGET_CPU, "fp32"), (cv2.dnn.DNN_TARGET_CPU_FP16, "fp16")):
    for workers, threads in ((1, n), (2, n), (2, max(1, n // 2)), (3, n), (4, 1)):
        print(f"{tn} workers={workers} threads={threads}: {throughput(workers, target, threads):.2f} frames/s")
