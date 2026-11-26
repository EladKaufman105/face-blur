// Face detection's pure parts: the box geometry, YuNet's output decoding, the fusion of RetinaFace
// with YuNet (as the app's FaceDetector.kt: noisy-or where both agree, YuNet's own faces from 0.6
// at 0.85 of their score, NMS at 0.4), the pace (1280 / 960 / 640 px, 15 to 4 detections a
// second; YuNet's cost never shrinks the frame; never weaker than the web's detection before
// YuNet, on simulated devices) and the low-confidence flag. Pure: no browser.
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

// ---- the pace: keeps up as it is; too slow: on the CPU the smaller frame first, then fewer detections; on the GPU the frame only when RetinaFace's own passes are slow
{
  // rfMs / fineMs default to the whole time (YuNet free), unless a case says otherwise.
  const P2 = (o) => P.pace({ workers: 1, maxRate: 15, side: 1280, startSide: 1280, rate: 15, gpu: true, rfMs: o.recentMs, fineMs: o.recentMs, ...o })
  const fast = P2({ recentMs: 40 })
  if (fast.reduced || fast.side !== 1280 || fast.rate !== 15) fail('a fast GPU is reduced: ' + JSON.stringify(fast))
  // 13 a second wanted of 15: within 80%, not reduced (and here the floor, the old detection's rate, is 15 anyway).
  const ok = P2({ recentMs: 1000 / 13 })
  if (ok.reduced || ok.side !== 1280 || ok.rate < 13 - 1e-6) fail('a little under: ' + JSON.stringify(ok))
  const okCpu = P2({ recentMs: 1000 / 13, gpu: false, side: 960, startSide: 960, fineMs: 200 })
  if (okCpu.reduced || okCpu.side !== 960 || !near(okCpu.rate, 13)) fail('a little under, CPU: ' + JSON.stringify(okCpu))
  // A GPU whose RetinaFace takes over 80 ms at 1280 px: 960 px, the rate as it was.
  const slow1280 = P2({ recentMs: 100, rfMs: 90, fineMs: 80 })
  if (!slow1280.reduced || slow1280.side !== 960 || slow1280.rate !== 15) fail('GPU over 80 ms at 1280: 960 px: ' + JSON.stringify(slow1280))
  const step = P2({ recentMs: 420, rfMs: 400, fineMs: 380, rate: 11 })
  if (step.side !== 960 || step.rate !== 11) fail('a size step changed the rate: ' + JSON.stringify(step))
  // At 960 px, under 200 ms: the frame stays, fewer detections (not under the floor: the old detection's rate at 640 px).
  const mid = P2({ recentMs: 160, rfMs: 150, fineMs: 120, side: 960 })
  const midFloor = Math.min(15, 600 / (120 * (640 / 960) ** 2))
  if (!mid.reduced || mid.side !== 960 || !near(mid.rate, Math.max(1000 / 160, midFloor))) fail('GPU at 960 px, 150 ms: fewer detections: ' + JSON.stringify(mid))
  // Over 200 ms at 960 px (the old detection's own rule): 640 px.
  const slowGpu = P2({ recentMs: 260, rfMs: 220, fineMs: 200, side: 960 })
  if (slowGpu.side !== 640) fail('a GPU over 200 ms at 960 px: 640 px: ' + JSON.stringify(slowGpu))
  // THE REVIEW'S CASE: a fast GPU, a slow CPU: YuNet (a fixed 150 ms) makes the detection slow, RetinaFace is quick. The frame never leaves its starting size, and the rate stays the old detection's (15).
  {
    let side = 1280, rate = 15
    for (let k = 0; k < 40; k++) {
      const rf = 25 * (side / 1280) ** 2
      const p = P2({ recentMs: rf + 150, rfMs: rf, fineMs: rf * 0.9, side, rate })
      if (p.side < 1280 || p.rate < 15) fail('YuNet cost shrank the GPU detection: ' + JSON.stringify({ k, ...p }))
      side = p.side; rate = p.rate
    }
    // The same at 960 px (a GPU without much to spare) and on the CPU: YuNet alone never shrinks the frame.
    const g960 = P2({ recentMs: 70 + 150, rfMs: 70, fineMs: 60 })
    if (g960.side !== 1280) fail('GPU at 70 ms + YuNet 150: ' + JSON.stringify(g960))
  }
  // The frame steps back up when timings recover (RetinaFace's time grows with the area, YuNet's doesn't).
  const up = P2({ recentMs: 25, side: 640, rate: 8 })
  if (up.side !== 960 || up.rate < 15 * 0.8) fail('back up from 640: ' + JSON.stringify(up))
  const up2 = P2({ recentMs: 25, side: 960 })
  if (up2.side !== 1280) fail('back up from 960: ' + JSON.stringify(up2))
  const stay = P2({ recentMs: 60, side: 960 })
  if (stay.side !== 960) fail('not back up when 1280 would be slow: ' + JSON.stringify(stay))
  // Not back up over the bigger size's limit (no back and forth): 50 ms at 960 would be 89 ms at 1280 (over 80).
  if (P2({ recentMs: 55, rfMs: 50, fineMs: 45, side: 960 }).side !== 960) fail('back and forth on the GPU')
  // The CPU: 960 -> 640 first, then fewer detections, never under the old detection's rate (10 a second at most on the CPU).
  const C = (o) => P2({ gpu: false, side: 960, startSide: 960, ...o })
  const c1 = C({ recentMs: 200 })
  if (!c1.reduced || c1.side !== 640 || c1.rate !== 15) fail('CPU 960 -> 640, rate unchanged: ' + JSON.stringify(c1))
  const c2 = C({ recentMs: 400, side: 640 })
  if (!c2.reduced || c2.side !== 640 || c2.rate !== 4) fail('CPU at 640, fewer: ' + JSON.stringify(c2))
  // The old detection would have run 600 / 100 = 6 a second here: not fewer, though YuNet makes the whole 400 ms.
  const c3 = C({ recentMs: 400, rfMs: 100, fineMs: 100, side: 640 })
  if (!near(c3.rate, 6)) fail('CPU: the floor: ' + JSON.stringify(c3))
  const crawl = C({ recentMs: 5000, side: 640 })
  if (crawl.rate !== P.MIN_RATE) fail('the fewest a second: ' + JSON.stringify(crawl))
  if (C({ recentMs: 250, workers: 4 }).reduced !== false || C({ recentMs: 250, workers: 2 }).reduced !== true) fail('CPU detectors in parallel')
  // Wide detection (every frame of a 30 fps video): the rate is judged against 30 but the frame against 15, so a device that keeps up with 15 keeps its size.
  const w = P2({ maxRate: 30, rate: 30, recentMs: 60 })
  if (!w.reduced || w.side !== 1280 || w.rate < 15) fail('sensitive keeps its size: ' + JSON.stringify(w))
  if (C({ maxRate: 30, rate: 30, recentMs: 60 }).side !== 960) fail('sensitive on the CPU keeps its size')
  // A video smaller than a size step: the step changes nothing, so it isn't taken and isn't "reduced" (a 720 px video on the GPU runs at 720 px at 1280 or 960).
  const small = P2({ recentMs: 100, rfMs: 90, fineMs: 80, frameSide: 720 })
  if (small.side !== 1280) fail('a size step that changes nothing: ' + JSON.stringify(small))
  if (P2({ recentMs: 40, side: 960, frameSide: 720 }).reduced) fail('960 px on a 720 px video is reduced')
  const s640 = P2({ recentMs: 75, side: 640, frameSide: 720 })
  if (s640.side !== 640 || !s640.reduced) fail('640 px on a 720 px video is not reduced: ' + JSON.stringify(s640))
  if (P.runSide(960, 1000) !== 1000 || P.runSide(960, 1100) !== 960 || P.runSide(960) !== 960) fail('runSide')
}

// ---- the pacer: windows of 4 after the warm-up; the GPU's check against the CPU is taken out of a
// detection's time but the detection counts. On a video with no faces the check runs on every
// detection (it ends only after 3 frames with faces): a slow GPU must still be paced.
{
  const pc = new P.Pacer(3)
  const got = []
  for (let k = 0; k < 3 + 8; k++) {
    const w = pc.add({ ms: 400 + 300, rfMs: 380, fineMs: 330, checkMs: 300 })
    if (w) got.push(w)
  }
  if (got.length !== 2 || !near(got[0].recentMs, 400) || got[0].rfMs !== 380 || got[0].fineMs !== 330) fail('the pacer: ' + JSON.stringify(got))
  // A slow GPU on a faceless clip: 1280 -> 960 -> 640 px as the windows come, the rate down.
  let side = 1280, rate = 15
  const pz = new P.Pacer(3)
  for (let k = 0; k < 40; k++) {
    const f = 400 * (side / 1280) ** 2, rf = f + 30
    const w = pz.add({ ms: rf + 60 + 250, rfMs: rf, fineMs: f, checkMs: 250 })
    if (!w) continue
    const p = P.pace({ ...w, workers: 1, maxRate: 15, side, startSide: 1280, rate, gpu: true })
    side = p.side; rate = p.rate
  }
  if (side !== 640 || rate >= 15) fail('a faceless clip on a slow GPU is not paced: ' + JSON.stringify({ side, rate }))
  // A clip of 10 frames a second detected on every frame isn't reduced, though 10 is under 80% of 15.
  const fps10 = P.pace({ recentMs: 90, rfMs: 80, fineMs: 70, workers: 1, maxRate: 15, side: 960, startSide: 960, rate: 15, gpu: false, frameRate: 10 })
  if (fps10.reduced || fps10.side !== 960) fail('a 10 fps clip at every frame is reduced: ' + JSON.stringify(fps10))
  if (!P.pace({ recentMs: 140, rfMs: 130, fineMs: 120, workers: 1, maxRate: 15, side: 640, startSide: 960, rate: 15, gpu: false, frameRate: 10 }).reduced) fail('fewer than every frame of a 10 fps clip, at 640 px, is not reduced')
}

// ---- never weaker than the web's detection before YuNet, on any device: simulated devices
// (RetinaFace's fine pass = a fixed part + a part that grows with the area; a coarse pass over
// 800 px; the re-check; YuNet beside it or after it; 1 to 4 detectors), each paced window by
// window by the new rule and by the old one (develop: RetinaFace alone, 960 px on the GPU, 640 on
// the CPU, 640 on the GPU past 200 ms; 600 ms a second of video, 15 / 10 at most, 4 at least).
// At every window the new frame is no smaller and the new rate no lower.
{
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))
  let cases = 0
  for (const gpu of [true, false]) for (const big of [3, 15, 40, 80, 150, 300, 800]) for (const fixed of [0, 10]) for (const coarse of [0, 5, 40])
  for (const check of [0, 10, 60]) for (const yunet of [5, 40, 150, 400]) for (const beside of [true, false]) for (const workers of gpu ? [1] : [1, 2, 4])
  for (const maxRate of [15, 30]) for (const frameSide of [1280, 736, 480]) for (const frameRate of [undefined, 10]) {
    const start = gpu ? 1280 : 960
    const eff = (s) => P.runSide(s, frameSide)
    // [big]: the fine pass's area part at the starting size.
    const fine = (s) => big * (eff(s) / eff(start)) ** 2 + fixed
    const rfOf = (s) => fine(s) + (eff(s) > 800 ? coarse : 0)
    const total = (s) => (beside ? Math.max(rfOf(s), yunet) : rfOf(s) + yunet) + check
    let side = start, rate = maxRate
    let oldSide = gpu ? 960 : 640, oldRate = Math.min(maxRate, gpu ? 15 : 10)
    for (let k = 0; k < 12; k++) {
      const p = P.pace({ recentMs: total(side), rfMs: rfOf(side), fineMs: fine(side), workers, maxRate, side, startSide: start, rate, gpu, frameSide, frameRate })
      side = p.side; rate = p.rate
      const oldMs = rfOf(oldSide) + check
      oldRate = clamp(600 * workers / oldMs, 4, Math.min(maxRate, gpu ? 15 : 10))
      if (gpu && oldMs > 200 && oldSide > 640) oldSide = 640
      if (eff(side) < eff(oldSide) || rate < oldRate - 1e-9) fail('weaker than before YuNet: ' + JSON.stringify({ gpu, big, fixed, coarse, check, yunet, beside, workers, maxRate, frameSide, frameRate, k, side, rate, oldSide, oldRate }))
      cases++
    }
  }
  if (cases < 10000) fail('too few cases: ' + cases)
}

// ---- the flag: low-confidence faces, or detection that ran reduced
if (!L.isLowConfidence({ reduced: true }) || !L.isLowConfidence({ lowConfidence: 2 }) || L.isLowConfidence({}) || L.isLowConfidence({ lowConfidence: 0, reduced: false })) fail('isLowConfidence')
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('fusion: ok')
