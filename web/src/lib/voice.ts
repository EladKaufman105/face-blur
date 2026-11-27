// Voice changes for anonymity and bleeps, as the Android app does them: a pitch change that
// keeps the timing (WSOLA time-stretch, then resampling), ring modulation for the robot and
// disguise voices, and words replaced by a 1 kHz beep (or silence) with short fades. The beep is
// as loud as the speech it replaces (bleepLevel), never louder than BLEEP_MAX_LEVEL.
import { BLEEP_MAX_LEVEL, BLEEP_MIN_LEVEL, BLEEP_SPEECH_GAIN, BLEEP_WINDOW_S } from '../gen/bleep.ts'

export type VoiceFx = 'none' | 'deep' | 'high' | 'robot' | 'disguise' | 'mute'

export const VOICES: { id: VoiceFx; label: string; pitch: number; ringHz: number; ringMix: number }[] = [
  { id: 'none', label: 'רגיל', pitch: 1, ringHz: 0, ringMix: 0 },
  { id: 'deep', label: 'נמוך', pitch: 0.72, ringHz: 0, ringMix: 0 },
  { id: 'high', label: 'גבוה', pitch: 1.45, ringHz: 0, ringMix: 0 },
  { id: 'robot', label: 'רובוט', pitch: 0.94, ringHz: 55, ringMix: 1 },
  // Lower and buzzing: the hardest to recognize.
  { id: 'disguise', label: 'מוסווה', pitch: 0.8, ringHz: 32, ringMix: 0.75 },
  { id: 'mute', label: 'ללא קול', pitch: 1, ringHz: 0, ringMix: 0 },
]

export interface Bleep { start: number; end: number }

const FADE = 0.008
const BEEP_HZ = 1000

/** The beep's peak for speech of RMS [rms]: BLEEP_SPEECH_GAIN times it (a sine 3 dB under the speech), kept in [BLEEP_MIN_LEVEL, BLEEP_MAX_LEVEL]. The app's BleepLevel.level is the same. */
export function bleepLevel(rms: number): number {
  const v = rms * BLEEP_SPEECH_GAIN
  return Number.isFinite(v) ? Math.min(BLEEP_MAX_LEVEL, Math.max(BLEEP_MIN_LEVEL, v)) : BLEEP_MIN_LEVEL
}

/** The RMS of [x] over [b] and BLEEP_WINDOW_S either side (0 for no samples). */
export function speechRms(x: Float32Array, rate: number, b: Bleep): number {
  const i0 = Math.max(0, Math.floor((b.start - BLEEP_WINDOW_S) * rate))
  const i1 = Math.min(x.length, Math.ceil((b.end + BLEEP_WINDOW_S) * rate))
  if (i1 <= i0) return 0
  let e = 0
  for (let i = i0; i < i1; i++) e += x[i] * x[i]
  return Math.sqrt(e / (i1 - i0))
}

/** [x] stretched to [factor] times its length without changing its pitch (WSOLA). */
function stretch(x: Float32Array, rate: number, factor: number, beat?: () => void): Float32Array {
  const N = Math.round(rate * 0.04) & ~1
  const Hs = N / 2
  const Ha = Hs / factor
  const tol = Math.round(rate * 0.01)
  const outLen = Math.ceil(x.length * factor)
  const out = new Float32Array(outLen + N)
  const win = new Float32Array(N)
  for (let i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / N)
  const at = (i: number) => (i >= 0 && i < x.length ? x[i] : 0)
  let prev = 0
  for (let k = 0; k * Hs < outLen; k++) {
    if (beat && (k & 255) === 0) beat()
    const nominal = Math.round(k * Ha)
    let best = nominal
    if (k > 0) {
      // The segment that best continues the previous one, searched coarsely then finely.
      const target = prev + Hs
      let bestC = -Infinity
      const corr = (s: number, step: number) => {
        let c = 0
        for (let i = 0; i < N; i += step) c += at(s + i) * at(target + i)
        return c
      }
      for (let d = -tol; d <= tol; d += 4) {
        const c = corr(nominal + d, 4)
        if (c > bestC) { bestC = c; best = nominal + d }
      }
      const coarse = best
      bestC = -Infinity
      for (let d = -3; d <= 3; d++) {
        const c = corr(coarse + d, 2)
        if (c > bestC) { bestC = c; best = coarse + d }
      }
    }
    prev = best
    const o = k * Hs
    for (let i = 0; i < N; i++) out[o + i] += at(best + i) * win[i]
  }
  return out.subarray(0, outLen)
}

/** Higher or lower by [pitch], same length. */
export function pitchShift(x: Float32Array, rate: number, pitch: number, beat?: () => void): Float32Array {
  if (Math.abs(pitch - 1) < 1e-3) return x
  const s = stretch(x, rate, pitch, beat)
  const y = new Float32Array(x.length)
  for (let i = 0; i < y.length; i++) {
    if (beat && (i & 0x3ffff) === 0) beat()
    const p = i * pitch
    const k = Math.floor(p)
    const a = k < s.length ? s[k] : 0
    const b = k + 1 < s.length ? s[k + 1] : 0
    y[i] = a + (b - a) * (p - k)
  }
  return y
}

/** The voice change and the bleeps on mono audio [x] (a new array). */
export function processVoice(x: Float32Array, rate: number, fx: VoiceFx, bleeps: Bleep[], tone: boolean, beat?: () => void): Float32Array {
  const v = VOICES.find((d) => d.id === fx) ?? VOICES[0]
  if (fx === 'mute') return new Float32Array(x.length)
  const y = v.pitch !== 1 ? pitchShift(x, rate, v.pitch, beat) : Float32Array.from(x)
  if (v.ringMix > 0) {
    const w = (2 * Math.PI * v.ringHz) / rate
    for (let i = 0; i < y.length; i++) y[i] *= 1 - v.ringMix + v.ringMix * Math.sin(w * i)
  }
  const wb = (2 * Math.PI * BEEP_HZ) / rate
  // How loud each beep is: from the speech it replaces (the sound as it came, measured before any bleep is written).
  const levels = tone ? bleeps.map((b) => bleepLevel(speechRms(x, rate, b))) : []
  for (let k = 0; k < bleeps.length; k++) {
    const b = bleeps[k]
    const i0 = Math.max(0, Math.floor((b.start - FADE) * rate))
    const i1 = Math.min(y.length, Math.ceil((b.end + FADE) * rate))
    for (let i = i0; i < i1; i++) {
      const t = i / rate
      const d = t < b.start ? (b.start - t) / FADE : t > b.end ? (t - b.end) / FADE : 0
      const g = Math.min(1, Math.max(0, d))
      y[i] = y[i] * g + (tone ? (1 - g) * levels[k] * Math.sin(wb * i) : 0)
    }
  }
  return y
}
