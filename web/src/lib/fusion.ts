// Pure parts of the face detector (no ONNX Runtime, so a Node test can import them): the box
// geometry, YuNet's output decoding and the fusion of RetinaFace with YuNet, as the app's
// FaceDetector.kt (RetinaFace is the main detector; YuNet adds the faces it misses and raises
// the score where both agree).
import type { Det } from './types'
// The values: shared/detect/detect.json, shared with the app (generated). With the extension:
// tests/fusion.cjs runs this file in Node as it is.
import { CLEAR, FUSE_IOU, FUSE_NMS, FUSE_YUNET_ONLY, FUSE_YUNET_ONLY_SCALE, FUSE_YUNET_WEIGHT, RF_THRESHOLD, YUNET_NMS, YUNET_SIDE, YUNET_THRESHOLD } from '../gen/detect.ts'

export function iou(a: Det, b: Det): number {
  const iw = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
  const ih = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
  if (iw <= 0 || ih <= 0) return 0
  const inter = iw * ih
  return inter / (a.w * a.h + b.w * b.h - inter)
}

export function nmsIou(dets: Det[], thr: number): Det[] {
  const keep: Det[] = []
  for (const d of [...dets].sort((a, b) => b.score - a.score)) {
    if (keep.every((k) => iou(k, d) < thr)) keep.push(d)
  }
  return keep
}

/** RetinaFace's score threshold (as the app's RF_THRESHOLD) and the lower one of the reduced mode. */
export { RF_THRESHOLD }
export const RF_THRESHOLD_REDUCED = 0.25
/** YuNet's score threshold (as the app's FaceDetectorYN.create) and the lower one of the reduced mode. */
export { YUNET_THRESHOLD }
export const YUNET_THRESHOLD_REDUCED = 0.3
/** YuNet's NMS IoU (the app's FaceDetectorYN.create). */
export { YUNET_NMS }
/** The model's fixed input side (the app runs it at 640 px at most). */
export { YUNET_SIDE }
/** A face this sure counts as strong evidence by itself (the app's FaceDetector.CLEAR). */
export { CLEAR }

const STRIDES = [8, 16, 32]

/** One level of YuNet's outputs (cls_N, obj_N, bbox_N), in the model's order of strides 8, 16, 32. */
export interface YunetLevels {
  cls: ArrayLike<number>[]
  obj: ArrayLike<number>[]
  bbox: ArrayLike<number>[]
}

const clamp01 = (v: number) => (v < 0 ? 0 : v > 1 ? 1 : v)

/**
 * YuNet's faces, as FaceDetectorYN decodes them (boxes cut to the picture): the model's input is [side] x [side] px, the
 * picture drawn in its top left corner at [w] x [h] px; boxes come back as fractions of that
 * picture. Score = sqrt(class x objectness), kept at [threshold] or more, then NMS at [YUNET_NMS].
 */
export function decodeYunet(o: YunetLevels, w: number, h: number, threshold: number, side = YUNET_SIDE): Det[] {
  const raw: Det[] = []
  STRIDES.forEach((stride, k) => {
    const cols = Math.floor(side / stride)
    const n = cols * cols
    const cls = o.cls[k], obj = o.obj[k], bb = o.bbox[k]
    for (let i = 0; i < n; i++) {
      const score = Math.sqrt(clamp01(cls[i]) * clamp01(obj[i]))
      if (score < threshold) continue
      const r = Math.floor(i / cols), c = i % cols
      const cx = (c + bb[i * 4]) * stride
      const cy = (r + bb[i * 4 + 1]) * stride
      const bw = Math.exp(bb[i * 4 + 2]) * stride
      const bh = Math.exp(bb[i * 4 + 3]) * stride
      if (bw <= 1 || bh <= 1) continue
      // The part of the box in the model's padding is cut off (a face at the edge keeps its visible part); a box wholly in it is none.
      const x1 = Math.max(0, cx - bw / 2), x2 = Math.min(w, cx + bw / 2)
      const y1 = Math.max(0, cy - bh / 2), y2 = Math.min(h, cy + bh / 2)
      if (x2 - x1 <= 1 || y2 - y1 <= 1) continue
      raw.push({ cx: (x1 + x2) / 2 / w, cy: (y1 + y2) / 2 / h, w: (x2 - x1) / w, h: (y2 - y1) / h, score, verify: 0 })
    }
  })
  return nmsIou(raw, YUNET_NMS)
}

/**
 * RetinaFace's faces [rf] and YuNet's [yn] as one list (the app's FaceDetector.detect):
 *  - a RetinaFace face that a YuNet face overlaps (IoU over 0.3) gets the noisy-or of both scores
 *    (YuNet's weighed 0.8);
 *  - a YuNet face of [yunetOnly] (0.6) or more that no RetinaFace face overlaps is added, at 0.85 of its score;
 *  - then NMS at 0.4. New objects: [rf] and [yn] aren't changed.
 */
export function fuse(rf: Det[], yn: Det[], yunetOnly = FUSE_YUNET_ONLY): Det[] {
  const fused: Det[] = []
  const used = new Set<number>()
  for (const d of rf) {
    let score = d.score
    yn.forEach((y, j) => {
      if (iou(d, y) > FUSE_IOU) {
        score = 1 - (1 - score) * (1 - y.score * FUSE_YUNET_WEIGHT)
        used.add(j)
      }
    })
    fused.push({ ...d, score })
  }
  yn.forEach((y, j) => {
    if (!used.has(j) && y.score >= yunetOnly && rf.every((r) => iou(y, r) <= FUSE_IOU)) fused.push({ ...y, score: y.score * FUSE_YUNET_ONLY_SCALE })
  })
  return nmsIou(fused, FUSE_NMS)
}
