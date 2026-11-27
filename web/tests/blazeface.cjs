// Selfie mode's pure parts: BlazeFace's anchors, box decoding, sigmoid and threshold, weighted NMS and
// letterbox, against MediaPipe's own results (tests/blazeface-fixture.json, from scripts/blazeface_fixture.py: the
// Python reference, which reproduces MediaPipe's FaceDetector task to within 1e-6 in score and 2 px in box, on three
// frames); the sensitivity's threshold (0.5 at 0 exactly, 0.3 at 1, lower at every step); the mode's choice
// (extended and fast win); the pure parts of a template's selfie key; the tracker's use of a selfie detection.
// Pure: no browser. The Android app's BlazeFaceTest checks the same fixture.
// Run: node tests/blazeface.cjs
const path = require('path')
const { execFileSync } = require('child_process')
const script = `
import { readFileSync } from 'node:fs'
const B = await import('./src/lib/blazeface.ts')
const S = await import('./src/lib/sensitivity.ts')
const T = await import('./src/lib/tracker.ts')
const L = await import('./src/lib/lowconf.ts')
const fixture = JSON.parse(readFileSync('./tests/blazeface-fixture.json', 'utf8'))
const fail = (m) => { console.log(JSON.stringify({ fail: m })); process.exit(0) }
const near = (a, b, e = 1e-6) => Math.abs(a - b) <= e

// ---- anchors: one for each cell of a 48 x 48 grid, row by row, at the cells' centers
if (B.ANCHORS !== 2304 || B.anchors.length !== 4608) fail('anchor count')
if (!near(B.anchors[0], 0.5 / 48) || !near(B.anchors[1], 0.5 / 48) || !near(B.anchors[2], 1.5 / 48) || !near(B.anchors[3], 0.5 / 48)) fail('first anchors')
if (!near(B.anchors[2 * 2303], 47.5 / 48) || !near(B.anchors[2 * 2303 + 1], 47.5 / 48)) fail('last anchor')
if (!near(B.anchors[2 * 49], 1.5 / 48) || !near(B.anchors[2 * 49 + 1], 1.5 / 48)) fail('anchor 49')
if (new Set(Array.from({ length: 2304 }, (_, i) => B.anchors[2 * i] + ',' + B.anchors[2 * i + 1])).size !== 2304) fail('anchors repeat')

// ---- sigmoid: MediaPipe's, clipped at +-100
if (B.sigmoid(0) !== 0.5 || !near(B.sigmoid(1), 0.7310586, 1e-6) || B.sigmoid(-1e9) > 1e-30 || B.sigmoid(1e9) !== 1) fail('sigmoid')

// ---- the reference's faces, frame by frame, at 0.5 and at the lower pool 0.3
const raw = (f) => {
  const reg = new Float32Array(B.ANCHORS * B.VALUES), logits = new Float32Array(B.ANCHORS).fill(-30)
  for (const [i, l, a, b, c, d] of f.anchors) { logits[i] = l; reg.set([a, b, c, d], i * B.VALUES) }
  return { reg, logits }
}
const sameDets = (name, want, got, w, h) => {
  if (want.length !== got.length) fail(name + ': ' + got.length + ' faces, want ' + want.length)
  want.forEach((e, i) => {
    const d = got[i]
    const box = [d.cx - d.w / 2, d.cy - d.h / 2, d.cx + d.w / 2, d.cy + d.h / 2]
    if (!near(e[0], d.score, 1e-4)) fail(name + ' #' + i + ' score ' + d.score + ' want ' + e[0])
    box.forEach((v, k) => { if (!near(e[1 + k], v, 1e-3)) fail(name + ' #' + i + ' box ' + k + ': ' + v + ' want ' + e[1 + k]) })
    if (Math.abs(e[1] * w - box[0] * w) > 1 || Math.abs(e[4] * h - box[3] * h) > 1) fail(name + ' #' + i + ' px')
    if (d.verify !== d.score) fail('the tracker takes the detector\\'s score as its own check')
  })
}
for (const f of fixture) {
  const { reg, logits } = raw(f)
  const r = B.faces(reg, logits, f.w, f.h, false)
  sameDets(f.name, f.at05, r.faces, f.w, f.h)
  if (r.extras.length) fail('extras without a sensitivity')
  const lb = B.letterbox(f.w, f.h)
  sameDets(f.name + ' pool', f.at03, B.weightedNms(B.decode(reg, logits, S.SELFIE_POOL)).map((c) => B.toDet(c, lb)), f.w, f.h)
  // With a sensitivity: the same default faces (0 is the default), and the pool's others as extras.
  const e = B.faces(reg, logits, f.w, f.h, true)
  sameDets(f.name + ' with extras', f.at05, e.faces, f.w, f.h)
  if (e.extras.length !== (f.name === 'classroom' ? 1 : 0)) fail(f.name + ': extras ' + e.extras.length)
  for (const x of e.extras) if (!(x.score < B.THRESHOLD)) fail('an extra over the default threshold')
}

// ---- weighted NMS: a group averaged by score, the top score kept; touching boxes are two faces
{
  const a = { score: 0.9, x0: 0.1, y0: 0.1, x1: 0.3, y1: 0.3 }, b = { score: 0.6, x0: 0.12, y0: 0.1, x1: 0.32, y1: 0.3 }, far = { score: 0.7, x0: 0.6, y0: 0.6, x1: 0.8, y1: 0.8 }
  const out = B.weightedNms([b, far, a])
  if (out.length !== 2 || out[0].score !== 0.9 || !near(out[0].x0, (0.1 * 0.9 + 0.12 * 0.6) / 1.5) || !near(out[0].x1, (0.3 * 0.9 + 0.32 * 0.6) / 1.5) || out[1].score !== 0.7) fail('weighted nms')
  if (B.weightedNms([a, { score: 0.8, x0: 0.3, y0: 0.1, x1: 0.5, y1: 0.3 }]).length !== 2 || B.weightedNms([]).length !== 0) fail('nms touching / empty')
}
// ---- the threshold keeps scores at or over it
{
  const reg = new Float32Array(B.ANCHORS * B.VALUES), logits = new Float32Array(B.ANCHORS).fill(-30)
  for (let i = 0; i < B.ANCHORS; i++) { reg[i * B.VALUES + 2] = 20; reg[i * B.VALUES + 3] = 20 }
  logits[100] = 0; logits[1000] = -0.01
  const got = B.decode(reg, logits, 0.5)
  if (got.length !== 1 || !near(got[0].x0, B.anchors[200] - 10 / 192) || !near(got[0].y1, B.anchors[201] + 10 / 192) || B.decode(reg, logits, 0.4).length !== 2) fail('the threshold')
  try { B.decode(new Float32Array(5), new Float32Array(5), 0.5); fail('a short output was taken') } catch (e) { if (String(e.message).startsWith('{')) throw e }
}
// ---- the letterbox
{
  const l = B.letterbox(1920, 1080)
  if (l.padX !== 0 || !near(l.padY, (192 - 108) / 2 / 192)) fail('letterbox 16:9')
  const d = B.toDet({ score: 0.9, x0: 0.4, y0: 0.4, x1: 0.6, y1: 0.6 }, l)
  if (!near(d.cx, 0.5) || !near(d.cy, 0.5) || !near(d.w, 0.2) || !near(d.h, 0.2 / (108 / 192), 1e-5)) fail('toDet')
  const p = B.letterbox(720, 1280)
  if (p.padY !== 0 || !(p.padX > 0.2) || !near(p.scale, 192 / 1280)) fail('letterbox 9:16')
  if (!(B.toDet({ score: 0.9, x0: 0, y0: 0.4, x1: 0.1, y1: 0.6 }, p).cx < 0)) fail('the bars are off the frame')
}

// ---- the sensitivity: 0.5 at 0 exactly, 0.3 at 1, strictly lower at each 5% step, never under the pool
{
  if (S.selfieThreshold(0) !== 0.5 || !near(S.selfieThreshold(1), 0.3) || S.selfieThreshold(NaN) !== 0.5 || S.selfieThreshold(-1) !== 0.5 || !near(S.selfieThreshold(7), 0.3)) fail('selfieThreshold ends')
  let last = S.selfieThreshold(0)
  for (let i = 1; i <= 20; i++) { const t = S.selfieThreshold(i / 20); if (!(t < last)) fail('not lower at step ' + i); last = t }
  if (S.selfieThreshold(1) < S.SELFIE_POOL - 1e-9) fail('under the pool')
  // At each level the tracker's evidence line (verified) is the detector's threshold: every extra counts.
  for (let i = 0; i <= 20; i++) if (!near(S.thresholds(i / 20).verified, S.selfieThreshold(i / 20))) fail('verified vs threshold at ' + i)
}
// ---- the mode's choice: extended and fast win; the others as before
{
  const m = S.modeOf
  if (m(false) !== 'normal' || m(false, false, false) !== 'normal' || m(true) !== 'extended' || m(false, true) !== 'fast') fail('the old modes')
  if (m(false, false, true) !== 'selfie' || m(false, true, true) !== 'fast' || m(true, false, true) !== 'extended' || m(true, true, true) !== 'extended') fail('selfie yields to extended and fast')
}
// ---- a talking head's detections make one track covering every frame, with no crop re-check; a selfie run is always flagged
{
  const n = 60, dets = new Map()
  for (let i = 0; i < n; i++) dets.set(i, [{ cx: 0.5 + 0.001 * i, cy: 0.45, w: 0.3, h: 0.4, score: 0.62, verify: 0.62 }])
  const tracks = T.track(n, dets, [], 30, 16 / 9, false)
  if (tracks.length !== 1 || tracks[0].first !== 0 || tracks[0].boxes.length / 4 < n) fail('a talking head: ' + tracks.length + ' tracks')
  // Weak scores (0.5 to 0.6) count as evidence, as MediaPipe's own threshold says.
  const weak = new Map()
  for (let i = 0; i < n; i++) weak.set(i, [{ cx: 0.5, cy: 0.45, w: 0.3, h: 0.4, score: 0.52, verify: 0.52 }])
  if (T.track(n, weak, [], 30, 16 / 9, false).length !== 1) fail('weak scores of one face')
  // A face found on a frame or two is no face (as the other modes).
  const blip = new Map([[5, [{ cx: 0.5, cy: 0.45, w: 0.3, h: 0.4, score: 0.7, verify: 0.7 }]]])
  if (T.track(n, blip, [], 30, 16 / 9, false).length !== 0) fail('one frame is a face')
  if (!L.isLowConfidence({ selfie: true }) || L.isLowConfidence({}) || !L.isLowConfidence({ fast: true })) fail('the low-confidence flag')
}
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('blazeface: ok')
