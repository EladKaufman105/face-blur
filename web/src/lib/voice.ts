// Voice changes for anonymity and bleeps, as the Android app does them: a pitch change that
// keeps the timing (WSOLA time-stretch, then resampling), ring modulation for the robot and
// disguise voices, and words replaced by a 1 kHz beep (or silence) with short fades.

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
const BEEP_LEVEL = 0.32

/** [x] stretched to [factor] times its length without changing its pitch (WSOLA). */
function stretch(x: Float32Array, rate: number, factor: number): Float32Array {
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
export function pitchShift(x: Float32Array, rate: number, pitch: number): Float32Array {
  if (Math.abs(pitch - 1) < 1e-3) return x
  const s = stretch(x, rate, pitch)
  const y = new Float32Array(x.length)
  for (let i = 0; i < y.length; i++) {
    const p = i * pitch
    const k = Math.floor(p)
    const a = k < s.length ? s[k] : 0
    const b = k + 1 < s.length ? s[k + 1] : 0
    y[i] = a + (b - a) * (p - k)
  }
  return y
}

/** The voice change and the bleeps on mono audio [x] (a new array). */
export function processVoice(x: Float32Array, rate: number, fx: VoiceFx, bleeps: Bleep[], tone: boolean): Float32Array {
  const v = VOICES.find((d) => d.id === fx) ?? VOICES[0]
  if (fx === 'mute') return new Float32Array(x.length)
  const y = v.pitch !== 1 ? pitchShift(x, rate, v.pitch) : Float32Array.from(x)
  if (v.ringMix > 0) {
    const w = (2 * Math.PI * v.ringHz) / rate
    for (let i = 0; i < y.length; i++) y[i] *= 1 - v.ringMix + v.ringMix * Math.sin(w * i)
  }
  const wb = (2 * Math.PI * BEEP_HZ) / rate
  for (const b of bleeps) {
    const i0 = Math.max(0, Math.floor((b.start - FADE) * rate))
    const i1 = Math.min(y.length, Math.ceil((b.end + FADE) * rate))
    for (let i = i0; i < i1; i++) {
      const t = i / rate
      const d = t < b.start ? (b.start - t) / FADE : t > b.end ? (t - b.end) / FADE : 0
      const g = Math.min(1, Math.max(0, d))
      y[i] = y[i] * g + (tone ? (1 - g) * BEEP_LEVEL * Math.sin(wb * i) : 0)
    }
  }
  return y
}
