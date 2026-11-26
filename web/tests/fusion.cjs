// Face detection's pure parts: the box geometry, YuNet's output decoding, the fusion of RetinaFace
// with YuNet (as the app's FaceDetector.kt: noisy-or where both agree, YuNet's own faces from 0.6
// at 0.85 of their score, NMS at 0.4), the pace (1280 / 960 / 640 px, the full rate (15 a second
// or the app's cadence) down to 8 detections a second; YuNet's cost never shrinks the frame; never
// weaker than the web's detection before YuNet, on simulated devices), the gate (which frames get
// a detection), the measure of what detection really covered (D, G, the notice) on any frame
// rates and timestamps, never weaker in frames than develop or PR #37, and the low-confidence
// flag. Pure: no browser.
// Run: node tests/fusion.cjs
const path = require('path')
const { execFileSync } = require('child_process')
const script = `
const F = await import('./src/lib/fusion.ts')
const P = await import('./src/lib/pace.ts')
const L = await import('./src/lib/lowconf.ts')
const C = await import('./src/lib/coverage.ts')
const S = await import('./src/lib/sensitivity.ts')
const T = await import('./src/lib/tracker.ts')
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
  // At 960 px, under 200 ms: the frame stays, fewer detections (not under the floor: the old detection's rate at 640 px, and 8 a second).
  const mid = P2({ recentMs: 160, rfMs: 150, fineMs: 120, side: 960 })
  const midFloor = Math.min(15, Math.max(8, 600 / (120 * (640 / 960) ** 2)))
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
  // Fewer, down to 8 a second (the app's floor; it was 4).
  const c2 = C({ recentMs: 400, side: 640 })
  if (!c2.reduced || c2.side !== 640 || c2.rate !== 8) fail('CPU at 640, fewer: ' + JSON.stringify(c2))
  // The old detection would have run 600 / 64 = 9.4 a second here: not fewer, though YuNet makes the whole 400 ms.
  const c3 = C({ recentMs: 400, rfMs: 64, fineMs: 64, side: 640 })
  if (!near(c3.rate, 600 / 64)) fail('CPU: the floor: ' + JSON.stringify(c3))
  const crawl = C({ recentMs: 5000, side: 640 })
  if (crawl.rate !== P.MIN_RATE || P.MIN_RATE !== 8) fail('the fewest a second: ' + JSON.stringify(crawl))
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
  for (const maxRate of [15, 30, 20, 50 / 3]) for (const frameSide of [1280, 736, 480]) for (const frameRate of [undefined, 10]) {
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

// ---- which frames get a detection (DetectGate's schedule, without the old gate beside it): the
// rate a second, at most every frame, on any timestamps; on steady frames never fewer detections
// nor a longer gap than develop's gate (snap to the frame) at the same rate; after a hole in the
// frames at most one detection to catch up.
{
  const FPS = [8, 10, 12, 15, 20, 23.976, 24, 25, 29.97, 30, 50, 59.94, 60]
  let seed = 5
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
  const clip = (fps, mode, secs = 60) => {
    const ts = [], n = Math.round(secs * fps)
    if (mode === 'walk') { let t = 0; for (let k = 0; k < n; k++) { ts.push(Math.round(t * 1000) / 1000); t += (0.8 + 0.4 * rnd()) / fps } }
    else for (let k = 0; k < n; k++) ts.push(mode === 'exact' ? k / fps : mode === 'ms' ? Math.round((k / fps) * 1000) / 1000 : Math.round((k / fps) * 90000) / 90000)
    return ts
  }
  /** develop's gate: a frame at least 1 / rate after the frame of the last detection. */
  const snap = () => { let last = -Infinity; return (ts, rate, cut) => (cut || ts - last >= 1 / rate - 1e-3 ? ((last = ts), true) : false) }
  const run = (ts, fps, rateAt, cutAt = () => false, allAt = () => false) => {
    const g = new P.DetectGate(), got = []
    ts.forEach((t, k) => { if (g.take(t, rateAt(k), cutAt(k), allAt(k))) got.push(k) })
    return got
  }
  const maxGap = (ts, got) => got.slice(1).reduce((m, k, j) => Math.max(m, ts[k] - ts[got[j]]), 0)
  // 1. covered() is the gate: exact, ms-rounded (WebM) and 1/90000 (MP4) timestamps, every frame rate, rates 4..15 and the frame rate.
  for (const fps of FPS) for (const mode of ['exact', 'ms', '90k']) for (const rate of [...Array.from({ length: 45 }, (_, i) => 4 + i * 0.25), fps]) {
    const ts = clip(fps, mode), got = run(ts, fps, () => rate), dur = ts.length / fps
    if (Math.abs(got.length - P.covered(rate, fps) * dur) > 1.5 + 0.01 * got.length) fail('the gate is not covered(): ' + JSON.stringify({ fps, mode, rate, n: got.length, want: P.covered(rate, fps) * dur }))
    if (maxGap(ts, got) > 1 / Math.min(rate, fps) + 1 / fps + 1.5e-3) fail('a gap over a period and a frame: ' + JSON.stringify({ fps, mode, rate, gap: maxGap(ts, got) }))
  }
  // 2. The review's cases: 11.1 a second on a 12 fps clip is 11.1, not 6; 15 a second on 20, 23.976, 25 fps is 15; on 30 fps every second frame.
  const per = (fps, rate, mode = 'ms') => run(clip(fps, mode, 20), fps, () => rate).length / 20
  if (!near(per(12, 11.1), 11.1, 0.1) || !near(per(20, 15), 15, 0.1) || !near(per(23.976, 15), 15, 0.1) || !near(per(25, 15), 15, 0.1) || !near(per(29.97, 13), 13, 0.1)) fail('aliasing: ' + [per(12, 11.1), per(20, 15), per(23.976, 15), per(25, 15), per(29.97, 13)])
  {
    const got = run(clip(30, 'ms', 10), 30, () => 15)
    if (got.some((k, j) => k !== 2 * j)) fail('15 a second on 30 fps is not every second frame: ' + got.slice(0, 10))
    if (run(clip(12, 'ms', 10), 12, () => 15).length !== 120) fail('a rate over the frame rate is not every frame')
  }
  // 3. Steady frames: never behind develop's gate, never a longer gap: same frames, the same changing rate (4..15, as pace changes it), the same cuts.
  for (const fps of FPS) for (const mode of ['exact', 'ms', '90k']) for (let trial = 0; trial < 10; trial++) {
    const ts = clip(fps, mode), rates = [], cuts = new Set()
    let r = 15, until = 0
    ts.forEach((t, k) => { if (t >= until) { r = Math.min(15, 4 + rnd() * 11.5); until = t + 0.3 + rnd() * 1.7 } rates.push(r); if (rnd() < 1 / (3 * fps)) cuts.add(k) })
    const g = new P.DetectGate(), d = snap()
    let a = 0, b = 0, la = null, lb = null, ga = 0, gb = 0
    ts.forEach((t, k) => {
      if (g.take(t, rates[k], cuts.has(k))) { a++; if (la !== null && !cuts.has(k)) ga = Math.max(ga, t - la); la = t }
      if (d(t, rates[k], cuts.has(k))) { b++; if (lb !== null && !cuts.has(k)) gb = Math.max(gb, t - lb); lb = t }
      if (a < b) fail('fewer detections than the gate of develop by frame ' + k + ': ' + JSON.stringify({ fps, mode, trial }))
    })
    if (ga > gb + 1.5e-3) fail('a longer gap than the gate of develop: ' + JSON.stringify({ fps, mode, trial, ga, gb }))
  }
  // 4. A hole in the frames (a stall, a dropped stretch): the first frame after it is taken, then at most one more to catch up (the schedule lags a period at most), then the rate.
  for (const fps of [24, 30, 60]) for (const rate of [4, 7.5, 12, 15]) {
    const ts = clip(fps, 'ms', 20).filter((t) => t < 5 || t >= 6.2)
    const got = run(ts, fps, () => rate), after = got.findIndex((k) => ts[k] >= 6.2)
    if (ts[got[after]] !== ts.find((t) => t >= 6.2)) fail('the first frame after a hole is not taken')
    if (ts[got[after + 2]] - ts[got[after + 1]] < 1 / rate - 1 / fps - 1.5e-3) fail('catch-up after a hole: ' + JSON.stringify({ fps, rate }))
    for (let j = 0, i = 0; i < got.length; i++) { while (ts[got[i]] - ts[got[j]] >= 1) j++; if (i - j + 1 > Math.ceil(rate) + 1) fail('a burst: ' + JSON.stringify({ fps, rate, at: ts[got[i]] })) }
  }
  // 5. VFR: intervals +-20% (ms-rounded), and a clip whose frame rate changes (nominal 30, then 15, 60, 10): the rate, at most every frame.
  for (const fps of FPS) for (const rate of [4, 6.5, 9, 11.1, 13, 15]) {
    const ts = clip(fps, 'walk')
    let ideal = 0
    for (let k = 1; k < ts.length; k++) ideal += Math.min(rate, 1 / (ts[k] - ts[k - 1])) * (ts[k] - ts[k - 1])
    const got = run(ts, fps, () => rate)
    if (got.length < 0.99 * ideal) fail('VFR: ' + JSON.stringify({ fps, rate, n: got.length, ideal }))
    for (let j = 1; j < got.length; j++) if (ts[got[j]] - ts[got[j - 1]] > 1 / rate + (ts[got[j]] - ts[got[j] - 1]) + 1.5e-3) fail('VFR gap: ' + JSON.stringify({ fps, rate }))
  }
  for (const rate of [4, 5.5, 8, 11.1, 15]) {
    const ts = [], ideal = [30, 15, 60, 10].reduce((s, f) => s + Math.min(rate, f) * 15, 0)
    let t = 0
    for (const f of [30, 15, 60, 10]) for (let k = 0; k < 15 * f; k++) { ts.push(Math.round(t * 1000) / 1000); t += 1 / f }
    const n = run(ts, 30, () => rate).length
    if (n < 0.99 * ideal || n > 1.01 * ideal + 4) fail('a frame rate that changes: ' + JSON.stringify({ rate, n, ideal }))
  }
  // 6. A cut is taken and starts the schedule again; force (wide detection at its full rate) takes every frame.
  {
    const ts = clip(30, 'ms', 4), got = run(ts, 30, () => 4, (k) => k === 31)
    if (!got.includes(31) || got.find((k) => k > 31) < 31 + 7) fail('a cut: ' + got)
    if (run(clip(29.97, 'walk', 10), 29.97, () => 29.97, () => false, () => true).length !== Math.round(299.7)) fail('all: every frame')
  }
  // 7. A rate that goes up takes effect at once, with at most one detection to catch up (the schedule lags a period at most); one that goes down, too.
  {
    const ts = clip(60, 'ms', 10), got = run(ts, 60, (k) => (ts[k] < 5 ? 4 : 15))
    const first = got.find((k) => ts[k] >= 5)
    if (ts[first] - 5 > 1 / 15 + 1 / 60 + 1e-3) fail('a rate up waits for the old schedule')
    const after = got.filter((k) => ts[k] >= 5)
    if (after.slice(1).filter((k, j) => ts[k] - ts[after[j]] < 1 / 15 - 1 / 60 - 1.5e-3).length > 1) fail('a burst after a rate up')
  }
  if (P.covered(15, 23.976) !== 15 || P.covered(15, 12) !== 12 || P.covered(11.1, 12) !== 11.1 || P.covered(9) !== 9) fail('covered()')
}

// ---- the gate keeps every frame develop's gate takes at the same rate (the old gate runs beside the
// schedule): frame by frame, on hard timestamps (the review's generators: steady, ms-rounded, +-40%
// jitter, frame rates that switch, holes of up to 3 s, frames in pairs, duplicate times, dropped
// frames, ramps; the nominal rate read right, at 1/4 to 2x, or as 1, 2.5 or 120; cuts): no frame it
// takes is left out, so no 2 s from any frame has fewer detections and no gap within a shot is
// longer (frames and seconds). With the rates the pace gives (8 a second at least, the full rate at
// most) against develop's (4 to 15): never fewer, a longer gap or a sparser 2 s, or noticed (the
// measure, or the pace's flag).
{
  let seed = 31
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
  const pick = (a) => a[Math.floor(rnd() * a.length)]
  const gen = () => {
    const fps = pick([5, 8, 10, 12, 12.5, 14, 15, 16, 20, 23.976, 24, 25, 29.97, 30, 31, 48, 50, 59.94, 60, 90, 120, 240])
    const kind = pick(['steady', 'ms', 'jitter', 'vfr', 'holes', 'pairs', 'drop', 'ramp', 'dup'])
    const n = 60 + Math.floor(rnd() * 900), t = []
    let ts = rnd() * 2, f = fps
    for (let i = 0; i < n; i++) {
      let dt = 1 / f
      if (kind === 'jitter') dt *= 0.6 + 0.8 * rnd()
      if (kind === 'vfr' && rnd() < 0.05) f = pick([fps, fps / 2, fps * 2, 7, 15, 30, 60])
      if (kind === 'holes' && rnd() < 0.02) dt += rnd() * 3
      if (kind === 'pairs') dt = i % 2 ? 0.001 : 2 / fps - 0.001
      if (kind === 'drop' && rnd() < 0.3) dt *= 2
      if (kind === 'ramp') dt = 1 / (fps * (0.5 + i / n))
      if (kind === 'dup' && rnd() < 0.1) dt = 0
      ts += dt
      t.push(kind === 'ms' ? Math.round(ts * 1000) / 1000 : ts)
    }
    const nominal = pick([fps, fps, fps, fps / 2, fps * 2, Math.max(1, fps / 4), 1, 2.5, 120])
    const cuts = new Set()
    for (let i = 1; i < n; i++) if (rnd() < 0.004) cuts.add(i)
    gen.last = kind + ' ' + fps
    return { fps, kind, t, nominal, cuts }
  }
  const snap = (t, rateAt, cuts) => { let last = -Infinity; const got = new Set(); t.forEach((x, i) => { if (cuts.has(i) || x - last >= 1 / rateAt(i) - 1e-3) { last = x; got.add(i) } }); return got }
  /** The longest gap within a shot in frames and in seconds (to the shot's ends too); the detections in 2 s from each frame (as many windows for both). */
  const measures = (t, det, cuts) => {
    let gapF = 0, gapS = 0, a = 0
    const bounds = [...[...cuts].sort((x, y) => x - y), t.length]
    for (const b of bounds) {
      let l = -1
      for (let i = a; i < b; i++) if (det.has(i)) { gapF = Math.max(gapF, l < 0 ? i - a + 1 : i - l); gapS = Math.max(gapS, t[i] - t[l < 0 ? a : l]); l = i }
      gapF = Math.max(gapF, l < 0 ? b - a : b - l)
      gapS = Math.max(gapS, t[b - 1] - t[l < 0 ? a : l])
      a = b
    }
    const pre = [0]
    for (let i = 0; i < t.length; i++) pre.push(pre[i] + (det.has(i) ? 1 : 0))
    const win = []
    for (let i = 0, j = 0; i < t.length && t[t.length - 1] - t[i] >= 2; i++) { while (j < t.length && t[j] < t[i] + 2) j++; if (j < i) j = i; win.push(pre[j] - pre[i]) }
    return { gapF, gapS, win }
  }
  let runs = 0, extra = 0, base = 0
  for (let r = 0; r < 1500; r++) {
    const { t, nominal, cuts } = gen()
    for (const sensitive of [false, true]) {
      const cap = sensitive ? nominal : 15, maxRate = sensitive ? nominal : P.fullRate(nominal)
      const steps = Array.from({ length: Math.ceil(t.length / 40) }, () => pick([4, 6, 8, 10, 12, 13, 14, 15, 16, 20, 30]))
      for (const sched of ['full', 'steps']) {
        // The worker's: the pace's rate (the floor of 8, the full rate at most), the gate, the meter.
        const rateAt = (i) => (sched === 'full' ? maxRate : Math.min(maxRate, Math.max(Math.min(8, maxRate), steps[Math.floor(i / 40)])))
        const g = new P.DetectGate(cap), m = new C.CoverageMeter({ needAt: (f) => P.needRate(f, sensitive, maxRate) }), got = new Set()
        t.forEach((x, i) => { const take = g.take(x, rateAt(i), cuts.has(i), sensitive && rateAt(i) >= maxRate); m.frame(x, cuts.has(i), take, rateAt(i)); if (take) got.add(i) })
        const noticed = m.result(true).short || (sched === 'steps' && t.some((_, i) => P.covered(rateAt(i), nominal) < P.FULL_SHARE * P.covered(maxRate, nominal)))
        const mine = measures(t, got, cuts)
        // develop's gate at the same rate (capped as develop's): every frame of it is taken.
        const same = snap(t, (i) => Math.min(rateAt(i), cap), cuts)
        for (const i of same) if (!got.has(i)) fail('a frame develop takes at the same rate is left out: ' + JSON.stringify({ r, sensitive, sched, i }))
        const s = measures(t, same, cuts)
        if (mine.gapF > s.gapF || mine.gapS > s.gapS + 1e-12 || mine.win.some((c, i) => c < s.win[i])) fail('weaker than develop at the same rate: ' + JSON.stringify({ r, sensitive, sched, mine: [mine.gapF, mine.gapS], dev: [s.gapF, s.gapS] }))
        // develop with its own rates (4 to 15, or the nominal rate when sensitive; the pace's are no lower): never fewer detections, a longer gap or fewer in the
        // sparsest 2 s, or noticed. (Window by window the two can differ where the rates differ: the old gate's frames depend on its rates before.)
        const devSet = snap(t, (i) => (sched === 'full' ? cap : Math.min(cap, Math.max(4, steps[Math.floor(i / 40)]))), cuts), dev = measures(t, devSet, cuts)
        const fewest = (w) => (w.length ? Math.min(...w) : Infinity)
        if ((got.size < devSet.size || mine.gapF > dev.gapF || mine.gapS > dev.gapS + 1e-12 || fewest(mine.win) < fewest(dev.win)) && !noticed) fail('weaker than develop and silent: ' + JSON.stringify({ r, sensitive, sched, nominal, kind: gen.last }))
        if (sched === 'full' && !sensitive) {
          // What the old gate beside the schedule adds.
          const own = new P.DetectGate()
          extra += got.size
          base += t.filter((x, i) => own.take(x, rateAt(i), cuts.has(i))).length
        }
        runs++
      }
    }
  }
  if (runs < 5000) fail('too few runs: ' + runs)
  if (process.env.GATE_EXTRA) console.error('the old gate beside the schedule adds ' + ((extra / base - 1) * 100).toFixed(1) + '% detections (full rate, normal, the generators)')
}

// ---- 'reduced' is the frames really covered, against the full rate (15 a second or the app's
// cadence, fullRate): no false 'reduced' at the common frame rates on a fast or mid device, and
// none missed: a device paced to fewer than 80% of the frames wanted is reduced.
{
  let seed = 9
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
  const gateRate = (rate, fps) => { const g = new P.DetectGate(); let n = 0; for (let k = 0; k < 20 * fps; k++) if (g.take(Math.round((k / fps) * 1000) / 1000, rate)) n++; return n / 20 }
  for (const fps of [20, 23.976, 24, 25, 29.97, 30, 31, 50, 59.94, 60]) for (const gpu of [true, false]) for (const ms of [20, 66, 75, 80]) {
    // 1000 / ms a second wanted: 50, 15, 13.3, 12.5 (a mid device keeps 80% of 15, not of 20 nor of 16.7)
    const maxRate = P.fullRate(fps)
    let side = gpu ? 1280 : 960, rate = maxRate
    for (let k = 0; k < 12; k++) {
      const p = P.pace({ recentMs: ms, rfMs: ms * 0.6, fineMs: ms * 0.5, workers: 1, maxRate, side, startSide: gpu ? 1280 : 960, rate, gpu, frameRate: fps })
      // Reduced exactly when the rate (the device's, or the floor) covers under 80% of the full rate's frames; never when the device keeps 80% of it.
      const keeps = Math.min(maxRate, 1000 / ms) >= P.FULL_SHARE * maxRate
      if (p.reduced !== (P.covered(p.rate, fps) < P.FULL_SHARE * P.covered(maxRate, fps)) || (keeps && p.reduced) || p.side !== (gpu ? 1280 : 960)) fail('reduced is not the share of the full rate: ' + JSON.stringify({ fps, gpu, ms, k, maxRate, ...p }))
      side = p.side; rate = p.rate
    }
    if (!P.pace({ recentMs: ms, rfMs: ms * 0.6, fineMs: ms * 0.5, workers: 1, maxRate, side, startSide: side, rate, gpu, frameRate: fps }).reduced && gateRate(rate, fps) < 0.8 * maxRate - 0.05) fail('not reduced but under 80% of the frames: ' + JSON.stringify({ fps, gpu, ms, rate, got: gateRate(rate, fps) }))
  }
  for (let k = 0; k < 4000; k++) {
    const fps = [8, 10, 12, 15, 20, 23.976, 24, 25, 29.97, 30, 31, 35, 50, 59.94, 60][Math.floor(rnd() * 15)], sensitive = rnd() < 0.3, gpu = rnd() < 0.5
    const maxRate = sensitive ? fps : P.fullRate(fps), start = gpu ? 1280 : 960
    const p = P.pace({ recentMs: 10 + rnd() * 400, rfMs: 5 + rnd() * 300, fineMs: 5 + rnd() * 250, workers: 1 + Math.floor(rnd() * 3), maxRate, side: start, startSide: start, rate: 4 + rnd() * 26, gpu, frameRate: fps })
    const got = gateRate(p.rate, fps)
    if (!p.reduced && got < P.covered(maxRate, fps) * P.FULL_SHARE * 0.99) fail('silent: ' + JSON.stringify({ fps, sensitive, ...p, got }))
    if (p.reduced && p.side === start && got >= P.covered(maxRate, fps) * P.FULL_SHARE * 1.01) fail('reduced but covers 80%: ' + JSON.stringify({ fps, sensitive, ...p, got }))
    if (p.rate < Math.min(P.MIN_RATE, maxRate) - 1e-9) fail('under the floor of 8 a second: ' + JSON.stringify({ fps, sensitive, ...p }))
  }
}

// ---- the full rate: 15 a second or the app's cadence (every n-th frame, n = fps / 15 rounded), whichever is more, never under 15 (the nominal frame rate
// can read low: the gate takes every frame of a clip that really has fewer); the floor is the app's 8 a second
{
  for (const [f, r] of [[2, 15], [6, 15], [10, 15], [12, 15], [14, 15], [15, 15], [20, 20], [22, 22], [23.976, 15], [24, 15], [25, 15], [29.97, 15], [30, 15], [31, 15.5], [35, 17.5], [48, 16], [50, 50 / 3], [59.94, 15], [60, 15], [120, 15]])
    if (!near(P.fullRate(f), r, 1e-9)) fail('fullRate(' + f + ') = ' + P.fullRate(f) + ', not ' + r)
  for (const [f, r] of [[20, 20], [23.976, 11.988], [30, 15], [31, 15.5], [50, 50 / 3], [10, 10]]) if (!near(P.appRate(f), r, 1e-9)) fail('appRate(' + f + ')')
  if (P.MIN_RATE !== 8) fail('the floor is not 8 a second')
  // What still counts as full, measured where the frames come at f a second: 80% of the full rate (at most of the frames), at most 97% of the app's cadence (23.976 fps: every second frame); wide: 80% of the frames.
  for (const [f, s, n] of [[30, false, 12], [23.976, false, 0.97 * 11.988], [20, false, 16], [50, false, 0.8 * 50 / 3], [10, false, 8], [30, true, 24], [24, true, 19.2]])
    if (!near(P.needRate(f, s), n, 1e-9)) fail('needRate(' + f + ', ' + s + ') = ' + P.needRate(f, s))
  // The full rate of a misread nominal 5 fps (15), where the frames come at 30: 12 needed, not 4.
  if (!near(P.needRate(30, false, P.fullRate(5)), 12, 1e-9) || !near(P.needRate(20, false, 15), 12, 1e-9)) fail('needRate at the frames, not the nominal rate')
}

// ---- what detection really covered (lib/coverage.ts), on the worker's frames: B's scenarios
{
  let seed = 11
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
  const FPS = [8, 10, 12, 15, 20, 22, 23.976, 24, 25, 29.97, 30, 31, 33, 35, 48, 50, 59.94, 60]
  /** Frame times (s) of a clip: exact, rounded to the ms (WebM), 1/90000 (MP4), VFR (+-20% walk, jitter on a grid), holes in the frames, a frame rate that changes. */
  const clip = (fps, mode, secs = 30) => {
    const ts = [], n = Math.round(secs * fps)
    if (mode === 'walk') { let t = 0; for (let k = 0; k < n; k++) { ts.push(Math.round(t * 1000) / 1000); t += (0.8 + 0.4 * rnd()) / fps } }
    else if (mode === 'grid') for (let k = 0; k < n; k++) ts.push(Math.max(0, Math.round(((k + (rnd() - 0.5) * 0.4) / fps) * 1000) / 1000))
    else if (mode === 'stall') { let k = 0, next = 2 + rnd() * 4; while (k < n) { const t = k / fps; if (t >= next) { k += Math.max(1, Math.round((0.05 + rnd() * 1.15) * fps)); next = t + 2 + rnd() * 4; continue } ts.push(Math.round(t * 1000) / 1000); k++ } }
    else if (mode === 'switch') { let t = 0; for (const f of [fps, fps / 2, fps * 2, fps / 3]) { const end = t + secs / 4; while (t < end - 1e-9) { ts.push(Math.round(t * 1000) / 1000); t += 1 / f } } }
    else for (let k = 0; k < n; k++) ts.push(mode === 'exact' ? k / fps : mode === 'ms' ? Math.round((k / fps) * 1000) / 1000 : Math.round((k / fps) * 90000) / 90000)
    for (let i = 1; i < ts.length; i++) if (ts[i] <= ts[i - 1]) ts[i] = ts[i - 1] + 1e-3
    return ts
  }
  /** The worker's gate: the rate a second, a cut's frame always, every frame in wide detection at its full rate. */
  const detect = (ts, fps, sensitive, rateAt = () => Infinity, cuts = new Set()) => {
    const maxRate = sensitive ? fps : P.fullRate(fps), g = new P.DetectGate(sensitive ? fps : 15), got = [], rates = []
    ts.forEach((t, k) => { const r = Math.min(maxRate, rateAt(k)); rates.push(r); if (g.take(t, r, cuts.has(k), sensitive && r >= maxRate)) got.push(k) })
    got.rates = rates
    return got
  }
  // The meter as the worker feeds it: the rate aimed at on each frame (what detect() used, else the full rate).
  const measure = (ts, got, fps, sensitive, cuts = []) => { const maxRate = sensitive ? fps : P.fullRate(fps); return C.measureCoverage(ts, got, cuts, { needAt: (f) => P.needRate(f, sensitive, maxRate) }, (i) => got.rates?.[i] ?? maxRate) }
  const dur = (ts) => ts[ts.length - 1] - ts[0] + 1e-9
  const ok = (m, what) => { if (m.short || m.cutsMissed) fail('a false notice: ' + what + ' ' + JSON.stringify(m)) }
  // P1. A device that keeps up, steady frame rates, any timestamps: no notice; D at least 97% of min(full, fps) and of the app's cadence, at most 5% over the full rate; gaps no longer than the app's (n frames).
  for (const fps of FPS) for (const mode of ['exact', 'ms', '90k']) {
    const ts = clip(fps, mode), got = detect(ts, fps, false), m = measure(ts, got, fps, false), full = P.fullRate(fps)
    ok(m, 'P1 ' + fps + ' ' + mode)
    if (m.rate < 0.97 * Math.min(full, fps) || m.rate < 0.97 * P.appRate(fps) || m.rate > (full > 15 ? 1.55 : 1.12) * Math.min(full, fps) + 1 / dur(ts)) fail('P1 D: ' + JSON.stringify({ fps, mode, D: m.rate, full }))
    if (m.maxGapFrames > Math.max(1, Math.round(fps / 15)) + (fps / full % 1 ? 1 : 0) || m.windows < 10) fail('P1 G: ' + JSON.stringify({ fps, mode, m }))
  }
  // P2. Wide detection at its full rate: every frame, on any timestamps (also VFR stretches over the nominal rate).
  for (const fps of FPS) for (const mode of ['exact', 'ms', 'walk', 'switch']) {
    const ts = clip(fps, mode), got = detect(ts, fps, true), m = measure(ts, got, fps, true)
    ok(m, 'P2 ' + fps + ' ' + mode)
    if (got.length !== ts.length || m.maxGapFrames !== 1) fail('P2 not every frame: ' + JSON.stringify({ fps, mode, n: got.length, of: ts.length }))
  }
  // P3. VFR: a walk of +-20% and jitter on a grid; P4: a frame rate that changes (nominal, half, double, a third); P7: holes in the frames. No notice, the frames covered.
  for (const fps of FPS) for (const mode of ['walk', 'grid', 'switch', 'stall']) for (const sensitive of [false, true]) {
    const ts = clip(fps, mode), got = detect(ts, fps, sensitive), m = measure(ts, got, fps, sensitive)
    ok(m, 'P3/4/7 ' + fps + ' ' + mode + ' ' + sensitive)
    let ideal = 0
    for (let k = 1; k < ts.length; k++) { const d = ts[k] - ts[k - 1]; if (mode !== 'stall' || d < 2.5 / fps) ideal += Math.min(sensitive ? fps : P.fullRate(fps), 1 / d) * d }
    if (got.length < 0.97 * ideal) fail('P3/4/7 coverage: ' + JSON.stringify({ fps, mode, sensitive, n: got.length, ideal }))
  }
  // P5. Cuts every 0.3 to 3 s: each cut's frame detected, no notice, gaps within a shot as without cuts.
  for (const fps of FPS) for (const mode of ['ms', '90k', 'walk']) {
    const ts = clip(fps, mode), cuts = new Set()
    for (let t = 0.3 + rnd() * 2.7; t < dur(ts); t += 0.3 + rnd() * 2.7) cuts.add(ts.findIndex((x) => x >= t))
    cuts.delete(-1); cuts.delete(0)
    const got = detect(ts, fps, false, undefined, cuts), m = measure(ts, got, fps, false, cuts)
    ok(m, 'P5 ' + fps + ' ' + mode)
    if (m.cuts !== cuts.size || [...cuts].some((c) => !got.includes(c))) fail('P5 a cut not detected')
  }
  // P6. Short clips (0.3 to 1.5 s): no notice, the first frame detected.
  for (const fps of FPS) for (const secs of [0.3, 0.6, 1, 1.5]) {
    const ts = clip(fps, 'ms', secs), got = detect(ts, fps, false), m = measure(ts, got, fps, false)
    ok(m, 'P6 ' + fps + ' ' + secs)
    if (got[0] !== 0) fail('P6 the first frame')
  }
  // P8. A device paced under the need is noticed, one at it isn't (the meter allows a frame interval's need, under one detection, a window: the schedule's last frame
  // can come up to a frame after it was due): steady rates 10%, 25%, 40% under N (noticed whenever what was detected is 3% under N; at 40% always), and at N
  // (+0.01: the pace's line, 12.01 a second on 25, 29.97 and 30 fps isn't reduced).
  for (const fps of FPS) for (const sensitive of [false, true]) for (const mode of ['exact', 'ms', '90k']) {
    const ts = clip(fps, mode), n = P.needRate(fps, sensitive), full = Math.min(fps, sensitive ? fps : P.fullRate(fps))
    // (The old gate beside the schedule can add detections: what is judged is what was detected, D.)
    for (const share of [0.9, 0.75, 0.6]) {
      const under = measure(ts, detect(ts, fps, sensitive, () => share * n), fps, sensitive)
      if (!under.short && under.rate < 0.97 * n && n * share < full) fail('P8 under the need, no notice: ' + JSON.stringify({ fps, sensitive, mode, n, share, under }))
      if (share === 0.6 && !under.short && n * share < full) fail('P8 60% of the need, no notice: ' + JSON.stringify({ fps, sensitive, mode, n, under }))
    }
    if (n + 0.01 < full) ok(measure(ts, detect(ts, fps, sensitive, () => n + 0.01), fps, sensitive), 'P8 at the need ' + fps + ' ' + sensitive + ' ' + mode)
  }
  for (const fps of [25, 29.97, 30]) {
    const p = P.pace({ recentMs: 1000 / 12.01, rfMs: 70, fineMs: 60, workers: 1, maxRate: P.fullRate(fps), side: 960, startSide: 960, rate: 15, gpu: false, frameRate: fps })
    const ts = clip(fps, 'ms', 60)
    if (p.reduced || !near(p.rate, 12.01, 0.001) || measure(ts, detect(ts, fps, false, () => p.rate), fps, false).short) fail('N2: 12.01 a second on ' + fps + ' fps: ' + JSON.stringify(p))
  }
  // B1 (review). The nominal frame rate read under 15 (2, 5, 10, 14 over the first 120 frames: a dark start, a screen recording), then 60 s at 30 fps: never weaker
  // than develop (15 a second, the next frame a period after the last) in count, gap or any 2 s; a device paced to the floor there is noticed (the need is the frames', not the nominal's).
  const develop = (ts, rate) => { let last = -Infinity; const got = []; ts.forEach((t, k) => { if (t - last >= 1 / rate - 1e-3) { last = t; got.push(k) } }); return got }
  const gapOf = (ts, got) => got.slice(1).reduce((m, k, j) => Math.max(m, ts[k] - ts[got[j]]), 0)
  const in2s = (ts, got, t0) => got.filter((k) => ts[k] >= t0 && ts[k] < t0 + 2).length
  for (const nominal of [2, 5, 10, 14]) for (const mode of ['ms', '90k']) {
    const ts = []
    for (let k = 0; k < 120; k++) ts.push(k / nominal)
    const t1 = ts[119] + 1 / nominal
    for (let k = 0; k < 60 * 30; k++) ts.push(t1 + k / 30)
    for (let k = 0; k < ts.length; k++) ts[k] = mode === 'ms' ? Math.round(ts[k] * 1000) / 1000 : Math.round(ts[k] * 90000) / 90000
    const got = detect(ts, nominal, false), dev = develop(ts, 15), m = measure(ts, got, nominal, false)
    if (got.length < dev.length || gapOf(ts, got) > gapOf(ts, dev) + 1e-9) fail('B1 a misread nominal ' + nominal + ': weaker than develop ' + JSON.stringify({ got: got.length, dev: dev.length }))
    for (let t0 = 0; t0 + 2 <= ts[ts.length - 1]; t0 += 0.5) if (in2s(ts, got, t0) < in2s(ts, dev, t0)) fail('B1 fewer in 2 s than develop at ' + t0)
    ok(m, 'B1 nominal ' + nominal)
    if (!measure(ts, detect(ts, nominal, false, () => 8), nominal, false).short) fail('B1 the floor on a misread nominal ' + nominal + ' is silent')
  }
  // Under 15 fps with +-40% frame-time jitter: no fewer, no longer gaps than develop at 15 a second (every frame where no interval is under 1 / 15 s).
  for (const fps of [5, 8, 10, 12, 14]) {
    const ts = []
    let t = 0
    for (let k = 0; k < 60 * fps; k++) { ts.push(Math.round(t * 1000) / 1000); t += (0.6 + 0.8 * rnd()) / fps }
    const got = detect(ts, fps, false), dev = develop(ts, 15)
    if ((0.6 / fps >= 1 / 15 && got.length !== ts.length) || got.length < dev.length || gapOf(ts, got) > gapOf(ts, dev) + 1e-9) fail('jitter under 15 fps: ' + JSON.stringify({ fps, got: got.length, dev: dev.length, frames: ts.length }))
    ok(measure(ts, got, fps, false), 'jitter ' + fps)
  }
  // P9. A dip of 3 s to 6 a second in a run at the full rate: noticed, where it is.
  for (const fps of [24, 25, 30, 50, 60]) {
    const ts = clip(fps, 'ms'), m = measure(ts, detect(ts, fps, false, (k) => (ts[k] >= 10 && ts[k] < 13 ? 6 : Infinity)), fps, false)
    if (!m.short || m.shortAt < 8 || m.shortAt > 13) fail('P9 a dip: ' + JSON.stringify(m))
  }
  // P10 / P11. At any rate from the floor (8) up: D at least 97% of min(rate, fps), at most 5% over; the gap within a shot at most round(0.25 x fps) + 1 frames and 0.7 s (the tracker's limits).
  for (const fps of FPS) for (const mode of ['exact', 'ms', '90k']) for (const r of [8, 9, 10.5, 12, 13.3, 15, 16.7, 20, 30]) {
    const ts = clip(fps, mode), m = measure(ts, detect(ts, fps, false, () => r), fps, false), want = Math.min(r, P.fullRate(fps), fps)
    if (m.rate < 0.97 * want || m.rate > 1.05 * (Math.min(want, fps) + Math.min(want, 15, fps)) + 1 / dur(ts)) fail('P10 D: ' + JSON.stringify({ fps, mode, r, D: m.rate }))
    if (m.maxGapFrames > Math.round(0.25 * fps) + 1 || m.maxGapS > 0.7) fail('P11 the gap: ' + JSON.stringify({ fps, mode, r, m }))
  }
  // P12. The floor: a crawling device detects 8 a second (develop and PR #37: 4), and it is noticed where 8 is under the need.
  for (const fps of FPS) for (const gpu of [true, false]) {
    const maxRate = P.fullRate(fps), start = gpu ? 1280 : 960
    const p = P.pace({ recentMs: 5000, rfMs: 4000, fineMs: 3500, workers: 1, maxRate, side: 640, startSide: start, rate: maxRate, gpu, frameRate: fps })
    if (!near(p.rate, Math.min(8, maxRate)) || !p.reduced) fail('P12 the floor: ' + JSON.stringify({ fps, gpu, ...p }))
    const ts = clip(fps, 'ms'), m = measure(ts, detect(ts, fps, false, () => p.rate), fps, false)
    if (m.rate < 0.97 * Math.min(8, fps) || (P.needRate(fps, false) > 8.5 && !m.short)) fail('P12 measured: ' + JSON.stringify({ fps, m }))
  }
  // P14. No over-detection: any rate, D at most 5% over max(full, rate) (+ one detection over the clip).
  for (let k = 0; k < 300; k++) {
    const fps = FPS[Math.floor(rnd() * FPS.length)], r = 4 + rnd() * 30, ts = clip(fps, ['ms', 'walk', 'grid'][k % 3], 10)
    const m = measure(ts, detect(ts, fps, false, () => r), fps, false)
    const re = Math.min(r, P.fullRate(fps))
    if (m.rate > 1.05 * (Math.min(re, fps) + Math.min(re, 15, fps)) + 1 / dur(ts)) fail('P14 over: ' + JSON.stringify({ fps, r, D: m.rate }))
  }
  // P15. The notice is the pace's flag or the measure: where they differ, one is stricter. A steady rate the pace calls full is never far under the need when measured (one the pace calls reduced can measure more: the old gate beside the schedule adds frames).
  for (let k = 0; k < 2000; k++) {
    const fps = FPS[Math.floor(rnd() * FPS.length)], sensitive = rnd() < 0.3, gpu = rnd() < 0.5, start = gpu ? 1280 : 960
    const maxRate = sensitive ? fps : P.fullRate(fps)
    const p = P.pace({ recentMs: 10 + rnd() * 400, rfMs: 5 + rnd() * 300, fineMs: 5 + rnd() * 250, workers: 1 + Math.floor(rnd() * 3), maxRate, side: start, startSide: start, rate: maxRate, gpu, frameRate: fps })
    const ts = clip(fps, 'ms', 10), m = measure(ts, detect(ts, fps, sensitive, () => p.rate), fps, sensitive)
    if (!p.reduced && m.rate < P.needRate(fps, sensitive) - 0.05) fail('P15 the pace calls it full, D under the need: ' + JSON.stringify({ fps, sensitive, ...p, D: m.rate }))
  }
  // The meter itself: windows of 2 s within a shot, the gap from a shot's start, a missed cut counted, a hole in the frames needs nothing.
  {
    const ts = clip(30, 'exact', 10)
    const m = measure(ts, ts.map((_, k) => k).filter((k) => k % 2 === 0 || k === 151), 30, false, [151])
    if (m.short || m.cuts !== 1 || m.cutsMissed || m.maxGapFrames !== 2 || !near(m.rate, 15, 0.2) || !near(m.minWindow, 15, 0.6)) fail('the meter: ' + JSON.stringify(m))
    const miss = measure(ts, ts.map((_, k) => k).filter((k) => k % 2 === 0 && k !== 150), 30, false, [151])
    if (miss.cutsMissed !== 1 || miss.maxGapFrames !== 3) fail('a missed cut: ' + JSON.stringify(miss))
    const gappy = measure(ts, ts.map((_, k) => k).filter((k) => k % 2 === 0 && (k < 100 || k > 130)), 30, false)
    if (!gappy.short || gappy.maxGapFrames !== 34 || !near(gappy.maxGapS, 34 / 30)) fail('a hole in the detections: ' + JSON.stringify(gappy))
    const holes = ts.filter((t) => t < 3 || t >= 4.2)
    if (measure(holes, holes.map((_, k) => k).filter((k) => k % 2 === 0), 30, false).short) fail('a hole in the frames needs detections')
    const longHole = ts.filter((t) => t < 3 || t >= 6)
    if (measure(longHole, longHole.map((_, k) => k).filter((k) => k % 2 === 0), 30, false).short) fail('a hole of 3 s in the frames needs detections')
    // N1 (review): a gap over 2 s within a shot (no window holds two detections) is judged by itself; also to a shot's end, and a shot with none.
    const lone = measure(ts, ts.map((_, k) => k).filter((k) => k % 2 === 0 && (k < 90 || k > 170)), 30, false)
    if (!lone.short || lone.shortAt > 3 || lone.minWindow > 0.5) fail('a gap of 2.7 s: ' + JSON.stringify(lone))
    if (!measure(ts, ts.map((_, k) => k).filter((k) => k % 2 === 0 && k < 200), 30, false).short) fail('3.3 s to the end with no detection')
    if (!measure(ts, [0], 30, false, [1]).short) fail('a shot of 10 s with no detection')
    // Nit 2 (review): 60 to 120 fps with holes of up to 3 s (2-5% of the frames): between the detections around a hole the need is at most one, so the full rate
    // isn't noticed; a rate of 5 still is (the frames between the holes).
    for (const fps of [60, 120]) for (const ph of [0.02, 0.05]) {
      let falseN = 0, missed = 0
      for (let r = 0; r < 40; r++) {
        const t = []
        let x = 0
        for (let i = 0, n = 300 + Math.floor(rnd() * 900); i < n; i++) { x += 1 / fps + (rnd() < ph ? rnd() * 3 : 0); t.push(x) }
        for (const rate of [P.fullRate(fps), 5]) {
          const g = new P.DetectGate(15), mm = new C.CoverageMeter({ needAt: (f) => P.needRate(f, false, P.fullRate(fps)) })
          t.forEach((y) => mm.frame(y, false, g.take(y, rate), rate))
          const sh = mm.result(true).short
          if (rate === 5 ? !sh : sh) rate === 5 ? missed++ : falseN++
        }
      }
      if (falseN || missed > 2) fail('holes at ' + fps + ' fps: ' + JSON.stringify({ ph, falseN, missed }))
    }
    // Bursts of 6 frames at 120 fps (50 ms, under a period at 15 a second) between holes of 0.5 s, one detection a burst: all a schedule at 15 can take; not short.
    // One detection every 0.55 s on steady frames (no holes): short.
    {
      const bt = [], bd = []
      for (let b = 0; b < 12; b++) for (let k = 0; k < 6; k++) { if (k === 0) bd.push(bt.length); bt.push(b * 0.55 + k / 120) }
      const fed = (tt, dd) => { const mm = new C.CoverageMeter({ needAt: (f) => P.needRate(f, false, 15) }), ds = new Set(dd); tt.forEach((y, i) => mm.frame(y, false, ds.has(i), 15)); return mm.result(true) }
      if (fed(bt, bd).short) fail('bursts between holes: one detection a burst is short')
      const ft = Array.from({ length: 12 * 66 }, (_, i) => i / 120)
      if (!fed(ft, ft.map((_, i) => i).filter((i) => i % 66 === 0)).short) fail('one detection every 0.55 s on steady frames is not short')
    }
    // Fed as it goes: the same as at once.
    const mt = new C.CoverageMeter({ needAt: (f) => P.needRate(f, false, 15) })
    ts.forEach((t, k) => mt.frame(t, false, k % 3 === 0))
    const a = mt.result(true), b = measure(ts, ts.map((_, k) => k).filter((k) => k % 3 === 0), 30, false)
    if (JSON.stringify(a) !== JSON.stringify(b) || !a.short || !near(a.rate, 10, 0.1)) fail('the meter fed as it goes: ' + JSON.stringify({ a, b }))
    // The first short window's own D and need (a second), for the log line: 10 against 12 (less a frame interval's need).
    if (a.shortAt !== 0 || !near(a.shortRate, 10, 0.2) || !near(a.shortNeed, 12, 0.5)) fail('the short window: ' + JSON.stringify(a))
  }
}

// ---- never weaker, in frames, than develop (RetinaFace alone: 960 / 640 px, 600 ms a second of
// video, 15 / 10 at most, 4 at least, the next frame a period after the last detected one) nor
// than PR #37 (this pace at 15 a second at most, 4 at least, the same gate as develop): simulated
// devices paced window by window on the clips' frames, each rule with its own detections. Each
// pace step is no smaller and covers no fewer frames than the old rules' at the same step; the detections are
// no fewer over the clip, nor in any 2 s (but for one at a window's edge: the two schedules' phase), and no gap
// within a shot is longer.
{
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))
  // PR #37's pace (lib/pace.ts as it was): the rate never under 4, 15 a second at most in normal detection.
  const pace37 = (i) => {
    const eff = (s) => P.runSide(s, i.frameSide)
    const target = Math.min(i.maxRate, P.SIZE_RATE, i.frameRate ?? Infinity)
    const wanted = (P.BUDGET_MS * i.workers) / Math.max(1, i.recentMs)
    const now = eff(i.side), low = eff(P.OLD.lowSide)
    const floor = clamp((P.OLD.budgetMs * i.workers) / Math.max(1, i.fineMs * Math.min(1, (low / now) ** 2)), 4, Math.min(i.maxRate, i.gpu ? P.OLD.gpuRate : P.OLD.cpuRate))
    const out = (rate, side = i.side) => ({ rate: Math.min(i.maxRate, Math.max(rate, floor)), side })
    const gpuLimit = (side) => (side > P.MID_SIDE ? P.GPU_SLOW_MS : P.OLD.gpuSlowMs)
    if (wanted >= target * P.FULL_SHARE) {
      if (i.side < i.startSide) {
        const up = Math.min(i.startSide, i.side < P.MID_SIDE ? P.MID_SIDE : i.startSide)
        const rfUp = i.rfMs * (eff(up) / eff(i.side)) ** 2
        const wantedUp = (P.BUDGET_MS * i.workers) / Math.max(1, i.recentMs - i.rfMs + rfUp)
        if ((!i.gpu || rfUp <= gpuLimit(up)) && wantedUp >= target * P.FULL_SHARE * 1.25) return out(Math.min(i.maxRate, wantedUp), up)
      }
      return out(Math.min(i.maxRate, wanted))
    }
    const down = i.side > P.MID_SIDE ? P.MID_SIDE : P.LOW_SIDE
    if (i.side > P.LOW_SIDE && eff(down) < eff(i.side) && (!i.gpu || i.rfMs > gpuLimit(i.side))) return out(i.rate, down)
    return out(clamp(wanted, 4, i.maxRate))
  }
  const snap = () => { let last = -Infinity; return (ts, rate, cut) => (cut || ts - last >= 1 / rate - 1e-3 ? ((last = ts), true) : false) }
  let seed = 21
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
  let cases = 0
  for (const gpu of [true, false]) for (const big of [8, 40, 80, 150, 300, 800]) for (const yunet of [5, 60, 200]) for (const workers of gpu ? [1] : [1, 2, 4])
  for (const fps of [10, 20, 23.976, 25, 30, 31, 50, 60]) for (const sensitive of [false, true]) {
    const start = gpu ? 1280 : 960, frameSide = 1280
    const eff = (s) => P.runSide(s, frameSide)
    const fine = (s) => big * (eff(s) / eff(start)) ** 2 + 4
    const rfOf = (s) => fine(s) + (eff(s) > 800 ? 0.1 * big : 0)
    const timing = (s) => ({ ms: rfOf(s) + yunet, rfMs: rfOf(s), fineMs: fine(s) })
    const ts = [], cuts = new Set()
    for (let k = 0; k < 20 * fps; k++) { ts.push(Math.round((k / fps) * 1000) / 1000); if (k && rnd() < 1 / (4 * fps)) cuts.add(k) }
    // A rule: its pace (or develop's), its maxRate, its gate. Paced after the warm-up, every 4 detections.
    const run = (rule) => {
      const maxRate = sensitive ? fps : rule === 'new' ? P.fullRate(fps) : 15
      let side = rule === 'dev' ? (gpu ? 960 : 640) : start, rate = maxRate
      const g = rule === 'new' ? new P.DetectGate(sensitive ? fps : 15) : null, s = snap(), pacer = new P.Pacer(workers + 2), got = [], steps = [], rates = []
      ts.forEach((t, k) => {
        const cut = cuts.has(k)
        rates.push(rate)
        if (!(g ? g.take(t, rate, cut, sensitive && rate >= maxRate) : s(t, rate, cut))) return
        got.push(k)
        if (rule === 'dev') {
          // develop: its rate from its own time (RetinaFace alone, no YuNet), 640 px on the GPU over 200 ms.
          const ms = rfOf(side)
          if (got.length > workers + 2 && got.length % 4 === 0) {
            rate = clamp((600 * workers) / ms, 4, Math.min(maxRate, gpu ? 15 : 10))
            if (gpu && ms > 200 && side > 640) side = 640
            steps.push({ side, rate })
          }
          return
        }
        const w = pacer.add(timing(side))
        if (!w) return
        const p = (rule === 'new' ? P.pace : pace37)({ ...w, workers, maxRate, side, startSide: start, rate, gpu, frameSide, frameRate: fps })
        rate = p.rate; side = p.side
        steps.push({ side, rate })
      })
      return { got, steps, rates }
    }
    const now = run('new'), r37 = run('pr37'), dev = run('dev')
    // develop's gate at the rate the new one runs (capped as develop's: 15, or the frame rate when sensitive): every frame it takes is taken.
    const ds = snap(), same = { got: [] }
    ts.forEach((t, k) => { if (ds(t, Math.min(now.rates[k], sensitive ? fps : 15), cuts.has(k))) same.got.push(k) })
    const taken = new Set(now.got)
    if (same.got.some((k) => !taken.has(k))) fail('a frame develop takes at this rate is left out')
    for (const [name, old] of [['PR #37', r37], ['develop', dev], ['develop at the same rate', same]]) {
      const what = JSON.stringify({ gpu, big, yunet, workers, fps, sensitive, name })
      for (let k = 0; k < Math.min(now.steps.length, old.steps?.length ?? 0); k++)
        if (eff(now.steps[k].side) < eff(old.steps[k].side) || P.covered(now.steps[k].rate, fps) < P.covered(old.steps[k].rate, fps) - 1e-9) fail('a pace step weaker than ' + what + ' ' + JSON.stringify({ k, now: now.steps[k], old: old.steps[k] }))
      if (now.got.length < old.got.length) fail('fewer detections than ' + what)
      // Every 2 s from every frame, against the old gate at the same rate (each rule's own pace changes its rate after its own detections, at other moments:
      // one that detects more paces sooner; against those, the pace steps, the count and the gap).
      if (old === same) for (let i = 0; i < ts.length && ts[i] + 2 <= ts[ts.length - 1]; i++) {
        const n = (got) => got.filter((k) => ts[k] >= ts[i] && ts[k] < ts[i] + 2).length
        if (n(now.got) < n(old.got)) fail('fewer detections in 2 s than ' + what + ' at ' + ts[i])
      }
      const gap = (got) => got.slice(1).reduce((m, k, j) => (cuts.has(k) ? m : Math.max(m, ts[k] - ts[got[j]])), 0)
      if (gap(now.got) > gap(old.got) + 1e-9) fail('a longer gap than ' + what + ' ' + gap(now.got) + ' > ' + gap(old.got))
      cases++
    }
  }
  if (cases < 1000) fail('too few cases: ' + cases)

  // Round 3 (review): the frame rate measured as the frames come (FrameRate) drives wide detection (its full rate, and every frame only while the rate covers
  // the frames: follow()) and the pace's 'reduced'; the frame's size is judged against the nominal rate as before. A screen recording that reads 5 to 24 fps
  // and runs at 60, in wide detection, on slow and mid devices: the pace lowers the rate (every frame for ever was 3600 detections a minute at 400 ms each),
  // never fewer detections nor a longer gap than develop (PR #37's pace and gate on the same device), and the measure says so when it is short.
  {
    const fr = new P.FrameRate(15)
    for (let k = 0; k < 40; k++) fr.add(Math.round((k / 15) * 1000) / 1000)
    if (fr.fps !== 15) fail('FrameRate: 15 fps in milliseconds is ' + fr.fps)
    const f2 = new P.FrameRate(8)
    for (let k = 0; k < 4; k++) f2.add(k / 60)
    if (f2.fps !== 8) fail('FrameRate: the nominal rate until 5 intervals')
    for (let k = 4; k < 40; k++) f2.add(k / 60)
    f2.add(10)
    if (!near(f2.fps, 60, 1e-6)) fail('FrameRate: 60 fps read as 8 (and a hole): ' + f2.fps)
    const a = P.follow(60, 8, 8), b = P.follow(60, 60, 20), c = P.follow(30, 60, 60), d = P.follow(60, 8, 5, true)
    if (a.rate !== 60 || !a.all || b.rate !== 20 || b.all || c.rate !== 30 || !c.all || d.rate !== 5 || d.all) fail('follow(): ' + JSON.stringify({ a, b, c, d }))
    // The frame's size is judged against the nominal rate (as develop): a CPU at 10.5 a second on a clip read as 24 fps that runs at 12 takes the smaller frame, as develop did.
    const q = (o) => P.pace({ recentMs: 1000 / 10.5, rfMs: 80, fineMs: 70, workers: 1, maxRate: 15, side: 960, startSide: 960, rate: 15, gpu: false, ...o })
    if (q({ frameRate: 12, nominal: 24 }).side !== q({ frameRate: 24 }).side || q({ frameRate: 24 }).side !== 640) fail('the size judged against the measured rate')
  }
  for (const [nominal, fps, ms, workers] of [[5, 60, 400, 1], [8, 60, 400, 1], [8, 60, 400, 4], [10, 60, 90, 1], [24, 60, 40, 1], [12, 30, 150, 2]]) {
    const ts = Array.from({ length: 60 * fps }, (_, k) => Math.round((k / fps) * 1000) / 1000)
    const tm = (side) => ({ ms: ms * (side / 960) ** 2, rfMs: 0.7 * ms * (side / 960) ** 2, fineMs: 0.6 * ms * (side / 960) ** 2 })
    // The worker's way.
    let maxRate = nominal, rate = nominal, all = true, side = 960, reduced = false
    const g = new P.DetectGate(nominal), fr = new P.FrameRate(nominal), pacer = new P.Pacer(workers + 2), mm = new C.CoverageMeter({ needAt: (f) => P.needRate(f, true, maxRate) }), got = []
    ts.forEach((t, k) => {
      fr.add(t)
      ;({ maxRate, rate, all } = P.follow(fr.fps, maxRate, rate))
      const take = g.take(t, rate, false, all)
      mm.frame(t, false, take, rate)
      if (!take) return
      got.push(k)
      const w = pacer.add(tm(side))
      if (!w) return
      const p = P.pace({ ...w, workers, maxRate, side, startSide: 960, rate, gpu: false, frameSide: 1280, frameRate: fr.fps, nominal })
      rate = p.rate; side = p.side; reduced ||= p.reduced
    })
    reduced ||= mm.result(true).short
    // develop (PR #37): maxRate and the frame rate the nominal one, its gate.
    let r37 = nominal, s37 = 960
    const sn = snap(), p37 = new P.Pacer(workers + 2), dev = []
    ts.forEach((t, k) => {
      if (!sn(t, r37, false)) return
      dev.push(k)
      const w = p37.add(tm(s37))
      if (!w) return
      const p = pace37({ ...w, workers, maxRate: nominal, side: s37, startSide: 960, rate: r37, gpu: false, frameSide: 1280, frameRate: nominal })
      r37 = p.rate; s37 = p.side
    })
    const what = JSON.stringify({ nominal, fps, ms, workers, n: got.length, dev: dev.length, rate })
    const gapOf = (x) => x.slice(1).reduce((m2, k, j) => Math.max(m2, ts[k] - ts[x[j]]), 0)
    // Bounded: the pace's rate with the old gate beside it (at most the nominal rate), a little warm-up; not every frame.
    if (got.length > 60 * 2 * Math.max(rate, P.MIN_RATE) + 120 || (1000 * workers) / ms < fps / 2 && got.length > 0.6 * ts.length) fail('wide detection on a misread frame rate is not paced: ' + what)
    if (got.length < dev.length || gapOf(got) > gapOf(dev) + 1e-9) fail('wide detection on a misread frame rate is weaker than develop: ' + what)
    if (rate < 0.8 * fps && !reduced) fail('wide detection under 80% of the frames, silent: ' + what)
  }
  // The nominal rate read high (120 for 30 fps in wide detection, 50 for 25 in normal): on a device that keeps up, every frame (wide) or the full rate, no 'reduced'.
  for (const [nominal, fps, sensitive] of [[120, 30, true], [60, 24, true], [50, 25, false], [120, 30, false]]) {
    const ts = Array.from({ length: 30 * fps }, (_, k) => Math.round((k / fps) * 1000) / 1000)
    let maxRate = sensitive ? nominal : P.fullRate(nominal), rate = maxRate, all = sensitive, side = 1280, reduced = false
    const g = new P.DetectGate(sensitive ? nominal : 15), fr = new P.FrameRate(nominal), pacer = new P.Pacer(3), mm = new C.CoverageMeter({ needAt: (f) => P.needRate(f, sensitive, maxRate) }), got = []
    ts.forEach((t, k) => {
      fr.add(t)
      if (sensitive) ({ maxRate, rate, all } = P.follow(fr.fps, maxRate, rate))
      const take = g.take(t, rate, false, all)
      mm.frame(t, false, take, rate)
      if (!take) return
      got.push(k)
      const w = pacer.add({ ms: 1000 / (sensitive ? 36 : 20), rfMs: 15, fineMs: 12 })
      if (!w) return
      const p = P.pace({ ...w, workers: 1, maxRate, side, startSide: 1280, rate, gpu: true, frameSide: 1280, frameRate: fr.fps, nominal })
      rate = p.rate; side = p.side; reduced ||= p.reduced
    })
    reduced ||= mm.result(true).short
    if (reduced || side !== 1280 || (sensitive && got.length < 0.95 * ts.length)) fail('a nominal rate read high: ' + JSON.stringify({ nominal, fps, sensitive, reduced, side, n: got.length, of: ts.length, rate }))
  }
}

// ---- the flag: low-confidence faces, or detection that ran reduced, or fast detection
if (!L.isLowConfidence({ reduced: true }) || !L.isLowConfidence({ lowConfidence: 2 }) || L.isLowConfidence({}) || L.isLowConfidence({ lowConfidence: 0, reduced: false })) fail('isLowConfidence')
if (!L.isLowConfidence({ fast: true }) || L.isLowConfidence({ fast: false })) fail('a fast run is flagged for a look')

// ---- fast detection: 5 a second, never fewer; judged against 5, so a device that keeps up is never 'reduced'
{
  if (S.FAST_RATE !== 5 || S.modeOf(false, true) !== 'fast' || S.modeOf(true, true) !== 'extended' || S.modeOf(false) !== 'normal') fail('modes')
  for (const fps of [8, 10, 12, 15, 20, 23.976, 24, 25, 29.97, 30, 48, 50, 59.94, 60, 120]) {
    for (const stamp of ['exact', 'ms']) {
      const ts = Array.from({ length: Math.round(60 * fps) }, (_, k) => (stamp === 'ms' ? Math.round((k / fps) * 1000) / 1000 : k / fps))
      for (const [label, ms, workers, gpu] of [['fast GPU', 20, 1, true], ['slow CPU', 900, 2, false], ['very slow CPU', 3000, 1, false]]) {
        let rate = S.FAST_RATE, side = gpu ? 1280 : 640, reduced = false
        const g = new P.DetectGate(), pacer = new P.Pacer(3), mm = new C.CoverageMeter({ needAt: (f) => P.needRate(f, false, S.FAST_RATE, true) }), got = []
        ts.forEach((t, k) => {
          const take = g.take(t, rate)
          mm.frame(t, false, take, rate)
          if (!take) return
          got.push(k)
          const w = pacer.add({ ms, rfMs: ms * 0.7, fineMs: ms * 0.6 })
          if (!w) return
          const p = P.pace({ ...w, workers, maxRate: S.FAST_RATE, side, startSide: gpu ? 1280 : 640, rate, gpu, frameSide: gpu ? 1280 : 960, frameRate: fps, nominal: fps, sizeRate: S.FAST_RATE })
          rate = p.rate; side = p.side; reduced ||= p.reduced
        })
        const cov = mm.result(true)
        const what = JSON.stringify({ fps, stamp, label, rate, side, n: got.length, cov: cov.rate, need: cov.need })
        if (rate !== S.FAST_RATE) fail('fast detection left 5 a second: ' + what)
        // About 5 a second (all frames of a slower clip), never fewer.
        if (cov.rate < Math.min(fps, 5) * 0.97 || cov.rate > Math.min(fps, 5) * 1.05) fail('fast detection is not 5 a second: ' + what)
        if (cov.short) fail('fast detection measured short against its own rate: ' + what)
        // The longest gap stays within what the tracker bridges (0.7 s) and a frame over 0.2 s.
        if (cov.maxGapS > 0.2 + 1.5 / fps) fail('fast detection gap: ' + what)
        // The frame never shrinks on the CPU (already 640), nor on a fast GPU.
        if (!gpu && side !== 640) fail('fast detection shrank the frame on the CPU: ' + what)
        if (label === 'fast GPU' && (side !== 1280 || reduced)) fail('fast detection on a fast GPU reduced: ' + what)
        if (!gpu && reduced) fail('fast detection on a CPU at 5 a second reads reduced: ' + what)
      }
    }
  }
  // The need of fast detection: 80% of 5 (4 a second), at most of the frames; normal and wide unchanged.
  if (!near(P.needRate(30, false, 5, true), 4) || !near(P.needRate(3, false, 5, true), 2.4)) fail('fast need')
  if (!near(P.needRate(30, false, P.fullRate(30)), Math.min(0.8 * 15, 0.97 * 15)) || !near(P.needRate(30, true), 24)) fail('normal and wide need changed')
  // sizeRate leaves the pace as it was when not given (normal detection unchanged).
  for (const i of [{ recentMs: 300, rfMs: 200, fineMs: 150, workers: 1, maxRate: 15, side: 960, startSide: 960, rate: 15, gpu: false, frameSide: 960, frameRate: 30, nominal: 30 }, { recentMs: 40, rfMs: 30, fineMs: 25, workers: 1, maxRate: 15, side: 1280, startSide: 1280, rate: 15, gpu: true, frameSide: 1280, frameRate: 30, nominal: 30 }]) {
    if (JSON.stringify(P.pace(i)) !== JSON.stringify(P.pace({ ...i, sizeRate: undefined }))) fail('sizeRate changed normal pacing')
  }
}

// ---- sensitivity on a device that just keeps up: its extra work (taken out of the timings) never makes the default detection lighter
{
  for (const [label, gpu, workers, ms, rf, fine, fps] of [['CPU just keeping up', false, 2, 130, 90, 80, 30], ['CPU slow', false, 2, 400, 300, 260, 30], ['GPU just keeping up', true, 1, 64, 40, 35, 30], ['GPU slow', true, 1, 140, 100, 90, 24], ['one CPU', false, 1, 70, 50, 45, 25]]) {
    const run = (extra) => {
      let rate = P.fullRate(fps), side = gpu ? 1280 : 960, reduced = false, n = 0
      const g = new P.DetectGate(15), pacer = new P.Pacer(3), steps = []
      for (let k = 0; k < 60 * fps; k++) {
        const t = k / fps
        if (!g.take(t, rate)) continue
        n++
        // The extra crops and decoding: in the detection's time, and reported as extraMs.
        const w = pacer.add({ ms: ms + extra, rfMs: rf, fineMs: fine, extraMs: extra })
        if (!w) continue
        const p = P.pace({ ...w, workers, maxRate: P.fullRate(fps), side, startSide: gpu ? 1280 : 960, rate, gpu, frameSide: gpu ? 1280 : 960, frameRate: fps, nominal: fps })
        rate = p.rate; side = p.side; reduced ||= p.reduced
        steps.push(rate + '@' + side)
      }
      return { n, rate, side, reduced, steps: steps.join(' ') }
    }
    const s0 = run(0), s1 = run(90)
    if (s1.n < s0.n || s1.rate < s0.rate || s1.side < s0.side || s1.reduced !== s0.reduced || s1.steps !== s0.steps) fail('a sensitivity made the default detection lighter (' + label + '): ' + JSON.stringify({ s0, s1 }))
  }
}

// ---- sensitivity: 0 is the default exactly; higher only ever lowers a threshold, never under the minimum
{
  const D = S.DEFAULT_THRESHOLDS
  if (S.thresholds(0) !== D || S.thresholds(-1) !== D || S.thresholds(NaN) !== D || S.thresholds('x') !== D) fail('sensitivity 0 is not the default')
  if (D.cut !== 0.4 || D.birth !== 0.45 || D.strong !== 0.6 || D.verified !== 0.5 || D.shortVerified !== 0.9) fail('the default thresholds of the tracker')
  if (T.DET_THRESHOLD !== 0.4) fail('DET_THRESHOLD of the tracker')
  if (JSON.stringify(S.thresholds(1)) !== JSON.stringify({ ...S.MOST }) || JSON.stringify(S.thresholds(5)) !== JSON.stringify({ ...S.MOST })) fail('sensitivity 1')
  let prev = D
  for (let i = 1; i <= 100; i++) {
    const t = S.thresholds(i / 100)
    for (const k of Object.keys(D)) if (!(t[k] <= prev[k] && t[k] >= S.MOST[k])) fail('sensitivity not monotone at ' + i / 100 + ' ' + k)
    prev = t
  }
  if (!(S.POOL_RF <= S.MOST.cut && S.POOL_YUNET <= S.MOST.cut && S.MOST.strong < F.CLEAR)) fail('the pool')
  if (S.roundUp(0.31) !== 0.35 || S.roundUp(0.35) !== 0.35 || S.roundUp(0.001) !== 0.05 || S.roundUp(0) !== 0 || S.roundUp(4) !== 1) fail('roundUp')
  if (S.quantize(0.02) !== 0 || S.quantize(0.03) !== 0.05 || S.quantize(7) !== 1 || S.levels(0).length !== 0 || JSON.stringify(S.levels(0.15)) !== JSON.stringify([0.05, 0.1, 0.15]) || S.levels(1).length !== 20) fail('steps')
}

// ---- sensitivity: the default detections come out exactly as before; the extra ones are nested
{
  let seed = 3
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648)
  const rdets = (n, lo) => Array.from({ length: n }, () => { const w = 0.03 + rnd() * 0.15; return det(rnd(), rnd(), w, w * (1 + rnd() * 0.4), lo + rnd() * (1 - lo)) })
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
  for (let r = 0; r < 400; r++) {
    // The decoding's NMS keeps the higher scores first: the faces from a threshold up are the same whatever the lower ones (so YuNet runs once, from the pool's threshold).
    const raw = rdets(Math.floor(rnd() * 30), S.POOL_RF)
    if (!same(F.nmsIou(raw.filter((d) => d.score >= 0.4), 0.3), F.nmsIou(raw, 0.3).filter((d) => d.score >= 0.4))) fail('nms prefix')
    // YuNet's own decoding: random outputs, at two thresholds.
    const side = 64, lv = [8, 16, 32].map((st) => (side / st) ** 2)
    const o = { cls: lv.map((n) => Float32Array.from({ length: n }, () => rnd())), obj: lv.map((n) => Float32Array.from({ length: n }, () => rnd())), bbox: lv.map((n) => Float32Array.from({ length: 4 * n }, () => rnd() * 2 - 1)) }
    if (!same(F.decodeYunet(o, 64, 48, 0.4, side), F.decodeYunet(o, 64, 48, S.POOL_YUNET, side).filter((d) => d.score >= 0.4))) fail('YuNet from the pool threshold')
    // Extras: from the cut up, never a face already found, nested as the cut goes down.
    const kept = F.fuse(rdets(Math.floor(rnd() * 6), 0.35), rdets(Math.floor(rnd() * 6), 0.4))
    const pool = F.fuse(rdets(Math.floor(rnd() * 12), S.POOL_RF), rdets(Math.floor(rnd() * 12), S.POOL_YUNET), S.POOL_YUNET_ONLY)
    let before = []
    for (const lv2 of S.levels(1)) {
      const ex = S.extras(kept, pool, S.thresholds(lv2).cut)
      if (!before.every((e) => ex.includes(e))) fail('extras not nested')
      if (ex.some((e) => e.score < S.thresholds(lv2).cut || kept.some((k) => F.iou(k, e) > S.SAME_FACE_IOU))) fail('extras')
      before = ex
    }
  }
}

// ---- sensitivity: tracks. The default tracks stay whole; a higher sensitivity never covers less
{
  let seed = 11
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648)
  const scene = () => {
    const n = 150, faces = [], dets = new Map(), pool = new Map()
    for (let f = 0; f < n; f += 2) dets.set(f, [])
    const nf = 1 + Math.floor(rnd() * 4)
    for (let q = 0; q < nf; q++) {
      const a = Math.floor(rnd() * n / 2), b = a + 10 + Math.floor(rnd() * (n - a - 10))
      const x0 = 0.1 + rnd() * 0.8, y0 = 0.1 + rnd() * 0.8, vx = (rnd() - 0.5) * 0.004, w = 0.02 + rnd() * 0.1, mean = 0.15 + rnd() * 0.8
      const face = new Map()
      for (let f = a; f <= b; f++) {
        const box = det(x0 + vx * (f - a), y0, w, w, 1)
        face.set(f, box)
        if (f % 2) continue
        const sc = Math.min(1, Math.max(0, mean + (rnd() - 0.5) * 0.4)), v = Math.min(1, Math.max(0, sc + (rnd() - 0.5) * 0.3))
        const d = det(box.cx + (rnd() - 0.5) * 0.004, box.cy, w, w, sc, v)
        if (sc >= 0.4) dets.get(f).push(d)
        else if (sc >= S.MOST.cut) pool.set(f, [...(pool.get(f) ?? []), d])
      }
      faces.push(face)
    }
    for (let c = Math.floor(rnd() * 20); c > 0; c--) { const f = 2 * Math.floor(rnd() * n / 2); pool.set(f, [...(pool.get(f) ?? []), det(rnd(), rnd(), 0.05, 0.05, S.MOST.cut + rnd() * 0.15, rnd() * 0.5)]) }
    return { n, faces, dets, pool }
  }
  const tracksAt = (sc, s, sensitive) => {
    const base = T.track(sc.n, sc.dets, [], 30, 1, sensitive)
    return S.widen(base, s, (level) => {
      const thr = S.thresholds(level), all = new Map()
      for (const [f, d] of sc.dets) all.set(f, [...d, ...S.extras(d, sc.pool.get(f) ?? [], thr.cut)])
      return T.track(sc.n, all, [], 30, 1, sensitive, thr)
    })
  }
  const boxOf = (t, f) => { const k = f - t.first; return k >= 0 && k * 4 < t.boxes.length ? det(t.boxes[k * 4], t.boxes[k * 4 + 1], t.boxes[k * 4 + 2], t.boxes[k * 4 + 3], 1) : null }
  const covered = (sc, tr) => sc.faces.reduce((m, face) => m + [...face].filter(([f, b]) => tr.some((t) => { const x = boxOf(t, f); return x && F.iou(x, b) > 0.3 })).length, 0)
  let gained = 0
  for (let r = 0; r < 150; r++) {
    const sc = scene()
    for (const sensitive of [false, true]) {
      const base = T.track(sc.n, sc.dets, [], 30, 1, sensitive)
      if (JSON.stringify(base) !== JSON.stringify(T.track(sc.n, sc.dets, [], 30, 1, sensitive, S.thresholds(0)))) fail('the tracker at sensitivity 0')
      if (JSON.stringify(tracksAt(sc, 0, sensitive)) !== JSON.stringify(base) || S.widen(base, 0, () => fail('a tracker run at sensitivity 0')) !== base) fail('sensitivity 0 is not the default tracks')
      let prev = covered(sc, base)
      for (let i = 1; i <= 10; i++) {
        const tr = tracksAt(sc, i / 10, sensitive)
        for (const b of base) {
          if (!tr.some((t) => { for (let f = b.first; f < b.first + b.boxes.length / 4; f++) { const x = boxOf(t, f), y = boxOf(b, f); if (!x || x.cx !== y.cx || x.cy !== y.cy || x.w !== y.w || x.h !== y.h) return false } return true })) fail('a default track was changed at ' + i / 10)
        }
        const c = covered(sc, tr)
        if (c < prev) fail('a higher sensitivity covered less (' + c + ' after ' + prev + ') at ' + i / 10)
        if (i === 10 && c > covered(sc, base)) gained++
        prev = c
      }
    }
  }
  if (gained < 20) fail('sensitivity found nothing more: ' + gained)
}
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('fusion: ok')
