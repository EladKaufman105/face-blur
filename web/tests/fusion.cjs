// Face detection's pure parts: the box geometry, YuNet's output decoding, the fusion of RetinaFace
// with YuNet (as the app's FaceDetector.kt: noisy-or where both agree, YuNet's own faces from 0.6
// at 0.85 of their score, NMS at 0.4), the pace (1280 / 960 / 640 px, 15 to 4 detections a
// second) and the low-confidence flag. Pure: no browser.
// Run: node tests/fusion.cjs
const path = require('path')
const { execFileSync } = require('child_process')
const script = `
const F = await import('./src/lib/fusion.ts')
const P = await import('./src/lib/pace.ts')
const L = await import('./src/lib/lowconf.ts')
const fail = (m) => { console.log(JSON.stringify({ fail: m })); process.exit(0) }
const near = (a, b, e = 1e-6) => Math.abs(a - b) <= e
const det = (cx, cy, w, h, score, verify = 0) => ({ cx, cy, w, h, score, verify })

// ---- the thresholds are the app's, and the reduced ones are lower
if (F.RF_THRESHOLD !== 0.35 || F.YUNET_THRESHOLD !== 0.4 || F.YUNET_NMS !== 0.3 || F.YUNET_SIDE !== 640 || F.CLEAR !== 0.8) fail('the app\\'s constants')
if (!(F.RF_THRESHOLD_REDUCED < F.RF_THRESHOLD && F.YUNET_THRESHOLD_REDUCED < F.YUNET_THRESHOLD)) fail('reduced thresholds are not lower')

// ---- iou and nms
if (!near(F.iou(det(.5, .5, .2, .2, 1), det(.5, .5, .2, .2, 1)), 1) || F.iou(det(.2, .2, .1, .1, 1), det(.8, .8, .1, .1, 1)) !== 0) fail('iou')
if (!near(F.iou(det(.5, .5, .2, .2, 1), det(.6, .5, .2, .2, 1)), 1 / 3)) fail('iou half overlap')
{
  const kept = F.nmsIou([det(.5, .5, .2, .2, .6), det(.51, .5, .2, .2, .9), det(.9, .9, .1, .1, .5)], 0.4)
  if (kept.length !== 2 || kept[0].score !== .9) fail('nms keeps the best of two overlapping boxes')
}

// ---- fusion: where both agree, the score is the noisy-or (YuNet's weighed 0.8); the box is RetinaFace's
{
  const rf = [det(.3, .3, .1, .1, .5)]
  const f = F.fuse(rf, [det(.3, .3, .1, .1, .7)])
  if (f.length !== 1 || !near(f[0].score, 1 - (1 - .5) * (1 - .7 * .8)) || f[0].cx !== .3) fail('noisy-or: ' + JSON.stringify(f))
  if (rf[0].score !== .5) fail('fuse changed its input')
  // Two YuNet faces on one RetinaFace face: both raise it.
  const g = F.fuse(rf, [det(.3, .3, .1, .1, .5), det(.3, .31, .1, .1, .5)])
  if (g.length !== 1 || !near(g[0].score, 1 - (1 - .5) * (1 - .4) * (1 - .4))) fail('two YuNet faces: ' + JSON.stringify(g))
}
// A YuNet face RetinaFace misses: added from 0.6, at 0.85 of its score; below 0.6 not.
{
  const f = F.fuse([det(.3, .3, .1, .1, .9)], [det(.7, .7, .1, .1, .8), det(.5, .9, .1, .1, .59)])
  if (f.length !== 2) fail('YuNet-only faces: ' + JSON.stringify(f))
  const y = f.find((d) => d.cx === .7)
  if (!y || !near(y.score, .8 * .85) || y.verify !== 0) fail('YuNet-only score: ' + JSON.stringify(y))
  if (F.fuse([], [det(.7, .7, .1, .1, .6)]).length !== 1 || F.fuse([], [det(.7, .7, .1, .1, .5999)]).length !== 0) fail('0.6 boundary')
}
// A face that is in none of them is no face; none at all: none.
if (F.fuse([], []).length !== 0) fail('empty')
// Never fewer faces, never a lower score than RetinaFace alone (random).
{
  let seed = 7
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
  const box = () => det(.1 + .8 * rnd(), .1 + .8 * rnd(), .05 + .2 * rnd(), .05 + .2 * rnd(), rnd())
  for (let r = 0; r < 3000; r++) {
    const rf = F.nmsIou(Array.from({ length: Math.floor(rnd() * 6) }, box), 0.4)
    const yn = Array.from({ length: Math.floor(rnd() * 6) }, box)
    const f = F.fuse(rf, yn)
    if (f.length < rf.length) fail('fewer faces than RetinaFace alone, round ' + r)
    for (const d of rf) {
      const same = f.find((x) => x.cx === d.cx && x.cy === d.cy && x.w === d.w)
      if (!same || same.score < d.score - 1e-9) fail('a RetinaFace face lost or lowered, round ' + r)
    }
  }
}

// ---- YuNet's decoding: the model's three levels (strides 8, 16, 32) of a 640 x 640 input
{
  const levels = () => ({ cls: [8, 16, 32].map((s) => new Float32Array((640 / s) ** 2)), obj: [8, 16, 32].map((s) => new Float32Array((640 / s) ** 2)), bbox: [8, 16, 32].map((s) => new Float32Array((640 / s) ** 2 * 4)) })
  const o = levels()
  // Stride 8: row 10, column 20: a face at ((20 + .5) * 8, (10 + .5) * 8) = (164, 84), 16 x 24 px (exp(ln 2) * 8, exp(ln 3) * 8). Score sqrt(.9 * .9) = .9.
  const i = 10 * 80 + 20
  o.cls[0][i] = .9; o.obj[0][i] = .9
  o.bbox[0].set([.5, .5, Math.log(2), Math.log(3)], i * 4)
  // The same face at stride 16 (a duplicate, weaker): NMS at 0.3 drops it.
  const j = 5 * 40 + 10
  o.cls[1][j] = .6; o.obj[1][j] = .6
  o.bbox[1].set([.5, .5, Math.log(1), Math.log(1.5)], j * 4)
  // Below the threshold: .39 (sqrt(.39 * .39)).
  const k = 3 * 20 + 3
  o.cls[2][k] = .39; o.obj[2][k] = .39
  o.bbox[2].set([.5, .5, 0, 0], k * 4)
  const d = F.decodeYunet(o, 640, 360, 0.4)
  if (d.length !== 1) fail('decode count ' + JSON.stringify(d))
  if (!near(d[0].score, .9, 1e-6) || !near(d[0].cx, 164 / 640) || !near(d[0].cy, 84 / 360) || !near(d[0].w, 16 / 640) || !near(d[0].h, 24 / 360)) fail('decode box ' + JSON.stringify(d[0]))
  // A lower threshold (the reduced mode) finds the weak one too.
  if (F.decodeYunet(o, 640, 360, F.YUNET_THRESHOLD_REDUCED).length < 2) fail('the lower threshold finds more')
  // A box wholly in the padding (below the picture, 360 px high) is none; one partly in it is cut to the picture.
  const p = levels()
  const m = 50 * 80 + 20
  p.cls[0][m] = 1; p.obj[0][m] = 1
  p.bbox[0].set([.5, .5, Math.log(2), Math.log(2)], m * 4)
  if (F.decodeYunet(p, 640, 360, 0.4).length !== 0) fail('a face in the padding')
  const e = levels()
  const n = 44 * 80 + 20 // y = 44.5 * 8 = 356, 16 px high: 348..364, the picture ends at 360
  e.cls[0][n] = 1; e.obj[0][n] = 1
  e.bbox[0].set([.5, .5, Math.log(2), Math.log(2)], n * 4)
  const cut = F.decodeYunet(e, 640, 360, 0.4)
  if (cut.length !== 1 || !near(cut[0].h, 12 / 360) || !near(cut[0].cy, 354 / 360) || !near(cut[0].w, 16 / 640)) fail('a box cut to the picture: ' + JSON.stringify(cut))
  // The scores are clamped to 0..1 (a model's output may leave it a little).
  const q = levels()
  q.cls[0][i] = 1.4; q.obj[0][i] = 1.2
  q.bbox[0].set([.5, .5, 0, 0], i * 4)
  if (!near(F.decodeYunet(q, 640, 640, 0.4)[0].score, 1)) fail('clamped score')
}

// ---- the pace: keeps up as it is; too slow: on the CPU the smaller frame first, then fewer detections; on the GPU fewer detections first
{
  const base = { workers: 1, maxRate: 15, side: 1280, startSide: 1280, rate: 15, gpu: true }
  const P2 = (o) => P.pace({ ...base, ...o })
  const fast = P2({ recentMs: 40 })
  if (fast.reduced || fast.side !== 1280 || fast.rate !== 15) fail('a fast GPU is reduced: ' + JSON.stringify(fast))
  // 13 a second wanted of 15: within 80%, not reduced.
  const ok = P2({ recentMs: 1000 / 13 })
  if (ok.reduced || ok.side !== 1280 || !near(ok.rate, 13, 1e-6)) fail('a little under: ' + JSON.stringify(ok))
  // A GPU at 10 a second (100 ms): the rate drops, the frame stays 1280 (and 960 floor is the develop's).
  const mid = P2({ recentMs: 100 })
  if (!mid.reduced || mid.side !== 1280 || !near(mid.rate, 10)) fail('GPU at 100 ms: rate first: ' + JSON.stringify(mid))
  // Under the floor (8 a second, 125 ms): the frame shrinks to 960 first, the rate as it was; 960 is the floor for a GPU under 80 ms... which this is not (130 ms): it may go to 640.
  const floor = P2({ recentMs: 130 })
  if (floor.side !== 960 || floor.rate !== 15) fail('GPU under the floor: 1280 -> 960, rate unchanged: ' + JSON.stringify(floor))
  const slowGpu = P2({ recentMs: 130, side: 960 })
  if (slowGpu.side !== 640) fail('a GPU slower than 80 ms may go below 960: ' + JSON.stringify(slowGpu))
  // A GPU under 80 ms never goes below 960 (wanted 8 to 12 at 80-125 ms is the rate's job; here under 80 ms wanted > 12).
  const quick = P2({ recentMs: 79, side: 960 })
  if (quick.side < 960) fail('a quick GPU below 960: ' + JSON.stringify(quick))
  // The rate's own floor at 960 on a GPU that is not slow cannot be reached under 80 ms; with several detectors the wanted rate doubles.
  if (P2({ recentMs: 130, workers: 2 }).side !== 1280) fail('detectors in parallel on the GPU')
  const step = P2({ recentMs: 130, rate: 11 })
  if (step.rate !== 11) fail('a size step lowered the rate: ' + JSON.stringify(step))
  // The frame steps back up when timings recover (time grows with the area): at 640 px 25 ms a detection would be 56 ms at 960.
  const up = P2({ recentMs: 25, side: 640, rate: 8 })
  if (up.side !== 960 || up.rate < 15 * 0.8) fail('back up from 640: ' + JSON.stringify(up))
  const up2 = P2({ recentMs: 25, side: 960 })
  if (up2.side !== 1280) fail('back up from 960: ' + JSON.stringify(up2))
  const stay = P2({ recentMs: 60, side: 960 })
  if (stay.side !== 960) fail('not back up when 1280 would be slow: ' + JSON.stringify(stay))
  // The CPU: 960 -> 640 first, then fewer detections.
  const c = { ...base, gpu: false, side: 960, startSide: 960 }
  const c1 = P.pace({ ...c, recentMs: 200 })
  if (!c1.reduced || c1.side !== 640 || c1.rate !== 15) fail('CPU 960 -> 640, rate unchanged: ' + JSON.stringify(c1))
  const c2 = P.pace({ ...c, recentMs: 400, side: 640 })
  if (!c2.reduced || c2.side !== 640 || c2.rate !== 4) fail('CPU at 640, fewer: ' + JSON.stringify(c2))
  const crawl = P.pace({ ...c, recentMs: 5000, side: 640 })
  if (crawl.rate !== P.MIN_RATE) fail('the fewest a second: ' + JSON.stringify(crawl))
  if (P.pace({ ...c, recentMs: 250, workers: 4 }).reduced !== false || P.pace({ ...c, recentMs: 250, workers: 2 }).reduced !== true) fail('CPU detectors in parallel')
  // Wide detection (every frame of a 30 fps video): the rate is judged against 30 but the frame against 15, so a device that keeps up with 15 keeps its size.
  const w = P.pace({ ...base, maxRate: 30, rate: 30, recentMs: 60 })
  if (!w.reduced || w.side !== 1280 || w.rate < 15) fail('sensitive keeps its size: ' + JSON.stringify(w))
  const wc = P.pace({ ...c, maxRate: 30, rate: 30, recentMs: 60 })
  if (wc.side !== 960) fail('sensitive on the CPU keeps its size: ' + JSON.stringify(wc))
}

// ---- the flag: low-confidence faces, or detection that ran reduced
if (!L.isLowConfidence({ reduced: true }) || !L.isLowConfidence({ lowConfidence: 2 }) || L.isLowConfidence({}) || L.isLowConfidence({ lowConfidence: 0, reduced: false })) fail('isLowConfidence')
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('fusion: ok')
