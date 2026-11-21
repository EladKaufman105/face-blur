"""Does RetinaFace on OpenCV's fp16 CPU path (ARMv8.2+) find the same faces as fp32? The app's
detection as is: fine pass at the frame's size (multiple of 32), coarse pass at 480 (boxes >= 64
px), threshold 0.35, NMS 0.4, and each face's verification crop (192 px, threshold 0.05)."""
import sys, glob, os, time
import cv2, numpy as np
MODEL = sys.argv[1]; VIDS = sys.argv[2:]
cv2.setNumThreads(cv2.getNumberOfCPUs())
STEPS = [8, 16, 32]; MS = [[16, 32], [64, 128], [256, 512]]
_pc = {}
def priors(h, w):
    if (h, w) not in _pc:
        out = []
        for k, s in enumerate(STEPS):
            fh, fw = -(-h // s), -(-w // s)
            for i in range(fh):
                for j in range(fw):
                    for m in MS[k]: out.append([(j + .5) * s / w, (i + .5) * s / h, m / w, m / h])
        _pc[(h, w)] = np.array(out, np.float32)
    return _pc[(h, w)]
def nms(boxes, s, t=0.4):
    order = np.argsort(-s); keep = []
    while len(order):
        i = order[0]; keep.append(i)
        b = boxes[order[1:]]
        xx1 = np.maximum(boxes[i, 0], b[:, 0]); yy1 = np.maximum(boxes[i, 1], b[:, 1]); xx2 = np.minimum(boxes[i, 2], b[:, 2]); yy2 = np.minimum(boxes[i, 3], b[:, 3])
        inter = np.clip(xx2 - xx1, 0, None) * np.clip(yy2 - yy1, 0, None)
        a = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
        order = order[1:][inter / (a[i] + a[order[1:]] - inter) <= t]
    return keep
class Retina:
    def __init__(self, target):
        self.net = cv2.dnn.readNetFromONNX(MODEL); self.net.setPreferableTarget(target)
    def run(self, img, side, thr, minbox):
        h, w = img.shape[:2]; s = min(1, side / max(w, h))
        iw, ih = max(32, round(w * s / 32) * 32), max(32, round(h * s / 32) * 32)
        x = img if (iw, ih) == (w, h) else cv2.resize(img, (iw, ih), interpolation=cv2.INTER_AREA)
        self.net.setInput(cv2.dnn.blobFromImage(x, 1.0, (iw, ih), (104, 117, 123), False, False))
        loc, conf = self.net.forward(["loc", "conf"])
        p = priors(ih, iw); loc = loc.reshape(-1, 4); sc = conf.reshape(-1, 2)[:, 1]
        k = sc >= thr; p, l, sc = p[k], loc[k], sc[k]
        cx = p[:, 0] + l[:, 0] * .1 * p[:, 2]; cy = p[:, 1] + l[:, 1] * .1 * p[:, 3]
        bw = p[:, 2] * np.exp(l[:, 2] * .2); bh = p[:, 3] * np.exp(l[:, 3] * .2)
        ok = np.minimum(bw * iw, bh * ih) >= minbox
        cx, cy, bw, bh, sc = cx[ok], cy[ok], bw[ok], bh[ok], sc[ok]
        boxes = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], 1)
        keep = nms(boxes, sc) if len(sc) else []
        return [(boxes[i], float(sc[i])) for i in keep]
    def detect(self, img):
        h, w = img.shape[:2]
        out = self.run(img, max(w, h), 0.35, 0)
        if max(w, h) > 480 * 1.3: out += self.run(img, 480, 0.35, 64)
        if not out: return []
        b = np.array([o[0] for o in out]); s = np.array([o[1] for o in out])
        return [out[i] for i in nms(b, s)]
    def verify(self, img, box):
        h, w = img.shape[:2]
        cx, cy = (box[0] + box[2]) / 2 * w, (box[1] + box[3]) / 2 * h
        r = max((box[2] - box[0]) * w, (box[3] - box[1]) * h); k = 192 / (2 * r)
        m = np.array([[k, 0, -(cx - r) * k], [0, k, -(cy - r) * k]], np.float64)
        crop = cv2.warpAffine(img, m, (192, 192), flags=cv2.INTER_AREA if k < 1 else cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        best = 0.0
        for b, s in self.run(crop, 192, 0.05, 0):
            bcx, bcy, bw = (b[0] + b[2]) / 2 * 192, (b[1] + b[3]) / 2 * 192, (b[2] - b[0]) * 192
            if abs(bcx - 96) < 48 and abs(bcy - 96) < 48 and bw > 38.4: best = max(best, s)
        return best
def iou(a, b):
    xx1, yy1, xx2, yy2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, xx2 - xx1) * max(0, yy2 - yy1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
print("opencv", cv2.__version__, "cpus", cv2.getNumberOfCPUs())
A, B = Retina(cv2.dnn.DNN_TARGET_CPU), Retina(cv2.dnn.DNN_TARGET_CPU_FP16)
tot = dict(ref=0, missed=0, extra=0, frames=0, ta=0.0, tb=0.0); sdiff = []; vdiff = []; missed_scores = []; extra_scores = []
for v in VIDS:
    cap = cv2.VideoCapture(v); n = 0; r = dict(ref=0, missed=0, extra=0)
    while True:
        ok, f = cap.read()
        if not ok: break
        n += 1
        if n % 3: continue
        s = min(1.0, 1280 / max(f.shape[:2]))
        f = cv2.resize(f, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        t = time.perf_counter(); da = A.detect(f); tot["ta"] += time.perf_counter() - t
        t = time.perf_counter(); db = B.detect(f); tot["tb"] += time.perf_counter() - t
        tot["frames"] += 1; r["ref"] += len(da); used = set()
        for ba, sa in da:
            best = max(((iou(ba, bb), j) for j, (bb, _) in enumerate(db)), default=(0, -1))
            if best[0] > 0.5:
                used.add(best[1]); sdiff.append(abs(sa - db[best[1]][1]))
                vdiff.append(abs(A.verify(f, ba) - B.verify(f, ba)))
            else:
                r["missed"] += 1; missed_scores.append(round(sa, 3))
        for j, (bb, sb) in enumerate(db):
            if j not in used: r["extra"] += 1; extra_scores.append(round(sb, 3))
    print(f"{os.path.basename(v)}: faces {r['ref']}, missed by fp16 {r['missed']}, extra {r['extra']}")
    for k in ("ref", "missed", "extra"): tot[k] += r[k]
sd = np.array(sdiff or [0]); vd = np.array(vdiff or [0])
print(f"TOTAL frames {tot['frames']}: fp32 faces {tot['ref']}, missed by fp16 {tot['missed']} (scores {missed_scores[:20]}), extra {tot['extra']} (scores {extra_scores[:20]})")
print(f"matched score diff: mean {sd.mean():.4f}, p99 {np.percentile(sd, 99):.4f}, max {sd.max():.4f}; verify diff: mean {vd.mean():.4f}, max {vd.max():.4f}")
print(f"time per frame: fp32 {tot['ta'] / tot['frames'] * 1000:.0f} ms, fp16 {tot['tb'] / tot['frames'] * 1000:.0f} ms")
