// Micro-benchmarks of the pure parts both platforms have (docs/shared-code.md, 4.2), on the input
// TrackerBench.kt times too (shared/vectors/tracker/bench.json, ci/make_bench_vectors.py):
//   track_s0   the tracker at sensitivity 0 (ms per second of video)
//   track_s50  the tracks at sensitivity 0.5: the default ones widened step by step (ms per second of video)
//   fuse       RetinaFace + YuNet fusion of every frame's detections (ms per second of video)
//   cutmap     10^6 CutMap.out over 200 cuts (ms)
//   srt        a 2,000-cue SRT written and read back (ms)
// Prints one JSON line: {"ms": {...medians...}, "out": {...what was computed...}}. "out" lets an A/B run
// (python3 ci/perf_check.py bench --base <other tree>) check that both sides computed the same thing.
// Not a test: it fails only if it can't run. Pure: no browser.
// Run: node tests/bench.cjs [iterations, default 15]
const path = require('path')
const { execFileSync } = require('child_process')
const iters = Math.max(3, Number(process.argv[2]) || 15)
const script = `
import { readFileSync } from 'node:fs'
import * as mod from 'node:module'
// subtitles.ts re-exports from './substyle' without the extension (Vite resolves it; Node needs a hook).
if (mod.registerHooks) mod.registerHooks({ resolve(s, c, next) { try { return next(s, c) } catch (e) { if (s.startsWith('.') && !/\\.[a-z]+$/.test(s)) return next(s + '.ts', c); throw e } } })
const T = await import('./src/lib/tracker.ts')
const S = await import('./src/lib/sensitivity.ts')
const F = await import('./src/lib/fusion.ts')
const CM = await import('./src/lib/cutmap.ts')
let Sub = null
try { Sub = await import('./src/lib/subtitles.ts') } catch { /* an old Node without registerHooks: no SRT part */ }

const v = JSON.parse(readFileSync('../shared/vectors/tracker/bench.json', 'utf8'))
const dets = new Map()
for (const [k, list] of Object.entries(v.dets)) dets.set(Number(k), list.map(([cx, cy, w, h, score, verify]) => ({ cx, cy, w, h, score, verify })))
const seconds = v.n / v.fps
// YuNet's view of the same faces: every other one, a little off, a little less sure.
const yn = new Map([...dets].map(([k, l]) => [k, l.filter((_, i) => i % 2 === 0).map((d) => ({ ...d, cx: d.cx + 0.002, cy: d.cy - 0.001, w: d.w * 1.05, h: d.h * 1.05, score: d.score * 0.9, verify: 0 }))]))
const cutRanges = Array.from({ length: 200 }, (_, i) => [i * 0.5 + 0.1, i * 0.5 + 0.3])
const cues = Array.from({ length: 2000 }, (_, i) => ({ id: i + 1, start: i * 1.5 + 0.0005, end: i * 1.5 + 1.2345, text: 'שלום עולם hello ' + i }))

const sumTracks = (ts) => { let s = 0, f = 0; for (const t of ts) { f += t.boxes.length / 4; for (const b of t.boxes) s += b } return { tracks: ts.length, frames: f, sum: Math.round(s * 1e3) / 1e3 } }
const parts = {
  track_s0: () => sumTracks(T.track(v.n, dets, v.cuts, v.fps, v.aspect, false)),
  track_s50: () => {
    const base = T.track(v.n, dets, v.cuts, v.fps, v.aspect, false)
    return sumTracks(S.widen(base, 0.5, (lv) => T.track(v.n, dets, v.cuts, v.fps, v.aspect, false, S.thresholds(lv))))
  },
  fuse: () => { let n = 0, s = 0; for (const [k, rf] of dets) { const f = F.fuse(rf, yn.get(k)); n += f.length; for (const d of f) s += d.score } return { dets: n, sum: Math.round(s * 1e3) / 1e3 } },
  cutmap: () => { const cm = new CM.CutMap(cutRanges); let s = 0; for (let i = 0; i < 1e6; i++) s += cm.out((i % 100000) * 0.001); return { sum: Math.round(s) } },
  srt: () => { if (!Sub) return null; const back = Sub.parseSrt(Sub.toSrt(cues)); return { cues: back.length, last: back[back.length - 1].end } },
}
const perVideoSecond = { track_s0: true, track_s50: true, fuse: true }
const ms = {}, out = {}
for (const [name, fn] of Object.entries(parts)) {
  if (name === 'srt' && !Sub) continue
  for (let i = 0; i < 3; i++) out[name] = fn()
  const t = []
  for (let i = 0; i < ${iters}; i++) { const a = performance.now(); fn(); t.push(performance.now() - a) }
  t.sort((a, b) => a - b)
  const med = t[Math.floor(t.length / 2)]
  ms[name] = Math.round((perVideoSecond[name] ? med / seconds : med) * 1e4) / 1e4
}
console.log(JSON.stringify({ ms, out, iters: ${iters}, units: 'track_s0, track_s50, fuse: ms per second of video; cutmap, srt: ms' }))`
const r = execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..'), maxBuffer: 1 << 26 }).toString().trim().split('\n').pop()
console.log(r)
