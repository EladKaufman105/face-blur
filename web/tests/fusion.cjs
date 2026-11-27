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

// ---- the re-check: only faces the tracker could take (0.4 up) that aren't clear, the least sure first; weak junk never takes a slot
{
  const junk = Array.from({ length: 20 }, (_, k) => det(.05 * k, .5, .05, .05, .26 + k * .005))
  const real = [det(.5, .1, .1, .1, .45), det(.6, .1, .1, .1, .6), det(.7, .1, .1, .1, .79), det(.8, .1, .1, .1, .9)]
  const r = F.recheckList([...junk, ...real])
  if (r.length !== 3 || r.map((d) => d.score).join() !== '0.45,0.6,0.79') fail('re-check list: ' + JSON.stringify(r.map((d) => d.score)))
  if (F.recheckList(Array.from({ length: 30 }, (_, k) => det(.03 * k, .5, .02, .02, .5))).length !== 12) fail('the slots')
}

// ---- the pace, by whole frames: a detection every N frames (the app's round(fps / 15), every frame when sensitive)
{
  if (P.baseStride(30, false) !== 2 || P.baseStride(25, false) !== 2 || P.baseStride(24, false) !== 2 || P.baseStride(60, false) !== 4 || P.baseStride(12, false) !== 1 || P.baseStride(30, true) !== 1) fail('base strides')
  if (P.strideFor(30, 13) !== 3 || P.strideFor(30, 15) !== 2 || P.strideFor(30, 14.7) !== 3 || P.strideFor(24, 12) !== 2 || P.strideFor(60, 15) !== 4 || P.strideFor(60, 29) !== 3) fail('stride for a rate')
  const base = (o) => ({ workers: 1, fps: 30, sensitive: false, side: 1280, startSide: 1280, stride: 2, gpu: true, ...o })
  const P2 = (o) => P.pace(base(o))
  // Keeps up: not reduced, the app's stride.
  const fast = P2({ recentMs: 40 })
  if (fast.reduced || fast.side !== 1280 || fast.stride !== 2) fail('a fast GPU is reduced: ' + JSON.stringify(fast))
  // 13 a second wanted of 15: within 80%, not reduced (the stride is the app's).
  const ok = P2({ recentMs: 1000 / 13 })
  if (ok.reduced || ok.stride !== 2) fail('a little under: ' + JSON.stringify(ok))
  // The whole-frame trap: 11 a second at 30 fps is a detection every 3 frames (10/s), not 15: reduced.
  const trap = P2({ recentMs: 1000 / 11 })
  if (!trap.reduced || trap.stride !== 3 || trap.side !== 1280) fail('GPU at 11/s: stride 3, reduced: ' + JSON.stringify(trap))
  // 12 a second (the 80% edge) keeps up at 30 fps; 11.9 does not.
  if (P2({ recentMs: 1000 / 12 }).reduced || !P2({ recentMs: 1000 / 11.9 }).reduced) fail('the 80% edge at 30 fps')
  // A GPU at 100 ms (10/s): stride 3 (10/s), the frame stays 1280.
  const mid = P2({ recentMs: 100 })
  if (!mid.reduced || mid.side !== 1280 || mid.stride !== 3) fail('GPU at 100 ms: stride first: ' + JSON.stringify(mid))
  // Under the floor (8/s, 130 ms): the frame shrinks to 960, the stride as it was; at 960 and still slow, to 640.
  const floor = P2({ recentMs: 130 })
  if (floor.side !== 960 || floor.stride !== 2) fail('GPU under the floor: 1280 -> 960, stride unchanged: ' + JSON.stringify(floor))
  const slowGpu = P2({ recentMs: 130, side: 960 })
  if (slowGpu.side !== 640) fail('960 -> 640 on a very slow GPU: ' + JSON.stringify(slowGpu))
  // A quick GPU (79 ms) never goes below 960.
  if (P2({ recentMs: 79, side: 960 }).side < 960) fail('a quick GPU below 960')
  // More detectors side by side get more time each.
  if (P2({ recentMs: 130, workers: 2 }).side !== 1280) fail('detectors in parallel on the GPU')
  const step = P2({ recentMs: 130, stride: 3 })
  if (step.stride !== 3) fail('a size step changed the stride: ' + JSON.stringify(step))
  // The frame steps back up when timings recover (time grows with the area).
  const up = P2({ recentMs: 25, side: 640, stride: 3 })
  if (up.side !== 960 || up.stride !== 2) fail('back up from 640: ' + JSON.stringify(up))
  if (P2({ recentMs: 25, side: 960 }).side !== 1280) fail('back up from 960')
  if (P2({ recentMs: 60, side: 960 }).side !== 960) fail('not back up when 1280 would be slow')
  // The fewest detections: a stride never longer than fps / 4.
  const crawl = P2({ recentMs: 5000, side: 640 })
  if (crawl.stride !== 7 || !crawl.reduced) fail('the fewest: ' + JSON.stringify(crawl))
  // The CPU: 960 -> 640 first, then fewer detections.
  const c = { gpu: false, side: 960, startSide: 960 }
  const c1 = P.pace(base({ ...c, recentMs: 200 }))
  if (!c1.reduced || c1.side !== 640 || c1.stride !== 2) fail('CPU 960 -> 640, stride unchanged: ' + JSON.stringify(c1))
  const c2 = P.pace(base({ ...c, recentMs: 400, side: 640 }))
  if (!c2.reduced || c2.side !== 640 || c2.stride !== 7) fail('CPU at 640, fewer (2.5/s at 30 fps = every 12th, capped at fps/4 = 7): ' + JSON.stringify(c2))
  if (P.pace(base({ ...c, recentMs: 250, workers: 4 })).reduced !== false || P.pace(base({ ...c, recentMs: 250, workers: 2 })).reduced !== true) fail('CPU detectors in parallel')
  // 24, 25, 60 fps: the app's strides are 2, 2, 4 (12, 12.5, 15 a second). Just under 80% of that is reduced, just over is not.
  for (const [fps, baseS] of [[24, 2], [25, 2], [60, 4]]) {
    const t = fps / baseS
    const o = P.pace(base({ fps, stride: baseS, recentMs: 1000 / (t * 0.81) }))
    const r = P.pace(base({ fps, stride: baseS, recentMs: 1000 / (t * 0.79) }))
    if (o.reduced || o.stride !== baseS || !r.reduced || r.stride <= baseS) fail(fps + ' fps: ' + JSON.stringify([o, r]))
  }
  // A video under 15 fps is detected every frame, and keeping up with its own rate is not reduced (12 fps: 12 a second).
  const slowVideo = P.pace(base({ fps: 12, stride: 1, recentMs: 1000 / 10 }))
  if (slowVideo.reduced || slowVideo.stride !== 1) fail('12 fps video at 10/s: ' + JSON.stringify(slowVideo))
  if (!P.pace(base({ fps: 12, stride: 1, recentMs: 1000 / 9 })).reduced) fail('12 fps video at 9/s')
  // A small video (long side 640) isn't "reduced" for not being upscaled: its start side is 640.
  const small = P.pace(base({ side: 640, startSide: 640, recentMs: 30 }))
  if (small.reduced || small.side !== 640) fail('a small video: ' + JSON.stringify(small))
  // Wide detection (every frame of a 30 fps video): judged against 30 for the stride, against 15 for the frame, so a device at 20 a second keeps its frame and detects every 2nd frame.
  const w = P.pace(base({ sensitive: true, stride: 1, recentMs: 50 }))
  if (!w.reduced || w.side !== 1280 || w.stride !== 2) fail('sensitive keeps its size: ' + JSON.stringify(w))
  const wc = P.pace(base({ ...c, sensitive: true, stride: 1, recentMs: 50 }))
  if (wc.side !== 960) fail('sensitive on the CPU keeps its size: ' + JSON.stringify(wc))
}

// ---- the flag: low-confidence faces, or detection that ran reduced
if (!L.isLowConfidence({ reduced: true }) || !L.isLowConfidence({ lowConfidence: 2 }) || L.isLowConfidence({}) || L.isLowConfidence({ lowConfidence: 0, reduced: false })) fail('isLowConfidence')
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('fusion: ok')
