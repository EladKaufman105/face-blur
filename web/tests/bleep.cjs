// The bleep's loudness follows the speech it replaces: a quiet word gets a quiet beep, a loud one a louder beep
// that is never over the cap, and nothing clips. The formula's vectors are shared with the app's BleepLevelTest
// (shared/vectors/bleep/level.json). Pure: no browser.
// Run: node tests/bleep.cjs
const path = require('path')
const { execFileSync } = require('child_process')
const script = `
import { readFileSync } from 'node:fs'
const V = await import('./src/lib/voice.ts')
const C = await import('./src/gen/bleep.ts')
const fail = (m) => { console.log(JSON.stringify({ fail: m })); process.exit(0) }
const near = (a, b, e = 1e-6) => Math.abs(a - b) <= e

// ---- the shared vectors
const vec = JSON.parse(readFileSync('../shared/vectors/bleep/level.json', 'utf8'))
for (const c of vec.cases) if (!near(V.bleepLevel(c.rms), c.level, 1e-6)) fail('level(' + c.rms + ') = ' + V.bleepLevel(c.rms) + ', want ' + c.level)
if (!(C.BLEEP_MAX_LEVEL <= 0.5 && C.BLEEP_MIN_LEVEL > 0 && V.bleepLevel(NaN) === C.BLEEP_MIN_LEVEL)) fail('the cap or the floor')

// ---- synthetic speech: a voiced-like tone (150 Hz and two harmonics) of a given RMS over 3 s at 16 kHz, a word at 1.0 - 1.5 s
const rate = 16000
const speech = (rms, n = rate * 3) => {
  const x = new Float32Array(n)
  for (let i = 0; i < n; i++) { const t = i / rate; x[i] = Math.sin(2 * Math.PI * 150 * t) + 0.5 * Math.sin(2 * Math.PI * 300 * t) + 0.25 * Math.sin(2 * Math.PI * 450 * t) }
  let e = 0; for (const v of x) e += v * v
  const k = rms / Math.sqrt(e / n)
  for (let i = 0; i < n; i++) x[i] *= k
  return x
}
const rmsOf = (x, a, b) => { let e = 0; for (let i = a; i < b; i++) e += x[i] * x[i]; return Math.sqrt(e / (b - a)) }
const peakOf = (x, a, b) => { let p = 0; for (let i = a; i < b; i++) p = Math.max(p, Math.abs(x[i])); return p }
const bleep = [{ start: 1.0, end: 1.5 }]
// the beep alone: inside the bleep, clear of the fades
const beepPeak = (x) => peakOf(V.processVoice(x, rate, 'none', bleep, true), Math.round(1.05 * rate), Math.round(1.45 * rate))

const quiet = beepPeak(speech(0.03)), mid = beepPeak(speech(0.1)), loud = beepPeak(speech(0.25)), huge = beepPeak(speech(0.6))
console.error('beep peaks: quiet', quiet.toFixed(4), 'mid', mid.toFixed(4), 'loud', loud.toFixed(4), 'huge', huge.toFixed(4))
// quiet speech -> a quiet beep, well under the old fixed 0.32
if (!(quiet < 0.06 && quiet > 0.02)) fail('quiet speech gave a beep of ' + quiet)
// louder speech -> a louder beep, in proportion while under the cap (the sine's peak = speech RMS x gain)
if (!(quiet < mid && mid < loud)) fail('the beep does not follow the speech: ' + [quiet, mid, loud])
if (!near(mid, V.bleepLevel(speechRmsOf(0.1)), 2e-3)) fail('mid beep ' + mid)
function speechRmsOf(r) { return V.speechRms(speech(r), rate, bleep[0]) }
// never over the cap, whatever the speech
if (loud > C.BLEEP_MAX_LEVEL + 1e-4 || huge > C.BLEEP_MAX_LEVEL + 1e-4) fail('over the cap: ' + [loud, huge])
// near silence: the floor, still heard
const hush = beepPeak(speech(0.0002))
if (!near(hush, C.BLEEP_MIN_LEVEL, 2e-3)) fail('near silence gave ' + hush)

// ---- the beep is 3 dB-ish under the speech (its RMS between 0.5 and 0.9 of the speech's) while under the cap
{
  const x = speech(0.1), y = V.processVoice(x, rate, 'none', bleep, true)
  const r = rmsOf(y, Math.round(1.05 * rate), Math.round(1.45 * rate)) / rmsOf(x, Math.round(1.05 * rate), Math.round(1.45 * rate))
  if (!(r > 0.5 && r < 0.9)) fail('the beep is not about 3 dB under the speech: ratio ' + r)
}

// ---- no clipping: full-scale speech, a voice change, a ring modulation, many bleeps
{
  const x = new Float32Array(rate * 3)
  for (let i = 0; i < x.length; i++) x[i] = Math.sign(Math.sin(2 * Math.PI * 200 * i / rate))
  for (const fx of ['none', 'deep', 'high', 'robot', 'disguise']) {
    const y = V.processVoice(x, rate, fx, [{ start: 0.5, end: 0.9 }, { start: 1.0, end: 1.2 }, { start: 2.0, end: 2.6 }], true)
    if (peakOf(y, 0, y.length) > 1.0001) fail('clipped with ' + fx + ': ' + peakOf(y, 0, y.length))
  }
}
// ---- silence instead of a beep: nothing inside the bleep; the speech outside is untouched
{
  const x = speech(0.2), y = V.processVoice(x, rate, 'none', bleep, false)
  if (peakOf(y, Math.round(1.02 * rate), Math.round(1.48 * rate)) > 1e-6) fail('not silent')
  if (y[rate / 2] !== x[rate / 2] || y[Math.round(2.5 * rate)] !== x[Math.round(2.5 * rate)]) fail('speech outside changed')
}
// ---- each bleep has its own level: a quiet word and a loud word in one clip
{
  const x = speech(0.03)
  for (let i = Math.round(2 * rate); i < Math.round(2.5 * rate); i++) x[i] *= 8
  const y = V.processVoice(x, rate, 'none', [{ start: 0.5, end: 1.0 }, { start: 2.0, end: 2.5 }], true)
  const a = peakOf(y, Math.round(0.55 * rate), Math.round(0.95 * rate)), b = peakOf(y, Math.round(2.05 * rate), Math.round(2.45 * rate))
  if (!(b > a * 3)) fail('one level for both: ' + [a, b])
}
console.log(JSON.stringify({ ok: true }))
`
const out = execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') })
const r = JSON.parse(out.toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('bleep: ok')
