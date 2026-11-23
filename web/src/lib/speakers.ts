// Who speaks when, as the Android app finds it: Kaldi-style filterbank features of each sentence,
// a voice embedding (WeSpeaker ResNet34, CC BY 4.0) of 3 s windows over it, then sentences with
// alike voices grouped into speakers (average linkage).

/** At most this many speakers. */
export const MAX_SPEAKERS = 6

/** Text colors of speakers 2, 3, ... (speaker 1 has the subtitles' own color). */
const SPEAKER_COLORS = ['#ffd60a', '#3df2ff', '#ff6fb5', '#34e07a', '#ffb020', '#b18cff']

/** The text color of [speaker] (0 or 1: [base]); never the same as speaker 1's. */
export function speakerColor(base: string, speaker: number | undefined): string {
  if (!speaker || speaker <= 1) return base
  const others = SPEAKER_COLORS.filter((c) => c.toLowerCase() !== base.toLowerCase())
  return others[(speaker - 2) % others.length]
}

// ------------------------------------------------------------------ features

export const BINS = 80
export const FBANK_RATE = 16000
const LEN = 400
const HOP = 160
const N = 512
const EPS = 1.1920929e-7

const win = Float32Array.from({ length: LEN }, (_, i) => 0.54 - 0.46 * Math.cos((2 * Math.PI * i) / (LEN - 1)))
const cosT = Float32Array.from({ length: N / 2 }, (_, i) => Math.cos((-2 * Math.PI * i) / N))
const sinT = Float32Array.from({ length: N / 2 }, (_, i) => Math.sin((-2 * Math.PI * i) / N))
const rev = Int32Array.from({ length: N }, (_, i) => {
  let r = 0
  for (let b = 0, x = i; b < 9; b++, x >>= 1) r = (r << 1) | (x & 1)
  return r
})
const melStart = new Int32Array(BINS)
const melW: Float32Array[] = []
{
  const mel = (f: number) => 1127 * Math.log(1 + f / 700)
  const lo = mel(20), hi = mel(FBANK_RATE / 2)
  const delta = (hi - lo) / (BINS + 1)
  const binHz = FBANK_RATE / N
  for (let m = 0; m < BINS; m++) {
    const left = lo + m * delta, center = left + delta, right = center + delta
    let first = -1
    const w: number[] = []
    for (let k = 0; k < N / 2; k++) {
      const mk = mel(binHz * k)
      if (mk > left && mk < right) {
        if (first < 0) first = k
        w.push(mk <= center ? (mk - left) / (center - left) : (right - mk) / (right - center))
      } else if (first >= 0) break
    }
    melStart[m] = Math.max(first, 0)
    melW.push(Float32Array.from(w))
  }
}

/**
 * Log mel filterbank features of samples [from, to) of [pcm] (mono, 16 kHz, -1..1), as WeSpeaker
 * computes them: 25 ms frames every 10 ms, DC removed, pre-emphasis 0.97, Hamming, 512-point FFT,
 * 80 bins from 20 Hz to 8 kHz. Frames x 80, row by row.
 */
export function fbank(pcm: Float32Array, from: number, to: number): Float32Array {
  const n = to - from < LEN ? 0 : 1 + Math.floor((to - from - LEN) / HOP)
  const out = new Float32Array(n * BINS)
  const re = new Float32Array(N), im = new Float32Array(N)
  const frame = new Float32Array(LEN)
  const power = new Float32Array(N / 2 + 1)
  for (let f = 0; f < n; f++) {
    const at = from + f * HOP
    let mean = 0
    for (let i = 0; i < LEN; i++) { frame[i] = pcm[at + i] * 32768; mean += frame[i] }
    mean /= LEN
    for (let i = 0; i < LEN; i++) frame[i] -= mean
    for (let i = LEN - 1; i >= 1; i--) frame[i] -= 0.97 * frame[i - 1]
    frame[0] -= 0.97 * frame[0]
    for (let i = 0; i < N; i++) {
      const j = rev[i]
      re[i] = j < LEN ? frame[j] * win[j] : 0
      im[i] = 0
    }
    for (let size = 2; size <= N; size *= 2) {
      const half = size / 2, step = N / size
      for (let i = 0; i < N; i += size) {
        for (let j = i, k = 0; j < i + half; j++, k += step) {
          const l = j + half
          const tr = re[l] * cosT[k] - im[l] * sinT[k]
          const ti = re[l] * sinT[k] + im[l] * cosT[k]
          re[l] = re[j] - tr; im[l] = im[j] - ti
          re[j] += tr; im[j] += ti
        }
      }
    }
    for (let k = 0; k <= N / 2; k++) power[k] = re[k] * re[k] + im[k] * im[k]
    for (let b = 0; b < BINS; b++) {
      const w = melW[b], s = melStart[b]
      let e = 0
      for (let i = 0; i < w.length; i++) e += w[i] * power[s + i]
      out[f * BINS + b] = Math.log(Math.max(e, EPS))
    }
  }
  return out
}

// ------------------------------------------------------------------ grouping

const normalize = (v: Float32Array) => {
  let s = 0
  for (const x of v) s += x * x
  const n = Math.sqrt(s)
  return n > 0 ? v.map((x) => x / n) : v
}
const dot = (a: Float32Array, b: Float32Array) => { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * b[i]; return s }
export { normalize as normalizeEmbedding }

/**
 * The speaker of each segment (1.., in order of first appearance). Null embeddings (too short to
 * tell) take the speaker of the nearest segment. [count]: the number of speakers if known.
 */
export function assignSpeakers(embs: (Float32Array | null)[], count: number | null, max = MAX_SPEAKERS, threshold = 0.4): number[] {
  const idx = embs.map((e, i) => (e ? i : -1)).filter((i) => i >= 0)
  const out = new Array<number>(embs.length).fill(0)
  if (idx.length === 0) return out.fill(1)
  const n = idx.length
  const e = idx.map((i) => normalize(embs[i]!))
  const sim = e.map((a, i) => e.map((b, j) => (i === j ? 0 : dot(a, b))))
  const size = new Array(n).fill(1)
  const alive = new Array(n).fill(true)
  const parent = Array.from({ length: n }, (_, i) => i)
  let clusters = n
  const target = count ? Math.min(max, Math.max(1, count)) : null
  while (clusters > 1) {
    let best = -2, ba = -1, bb = -1
    for (let a = 0; a < n; a++) {
      if (!alive[a]) continue
      for (let b = a + 1; b < n; b++) if (alive[b] && sim[a][b] > best) { best = sim[a][b]; ba = a; bb = b }
    }
    const enough = target !== null ? clusters <= target : best < threshold && clusters <= max
    if (enough || ba < 0) break
    for (let k = 0; k < n; k++) {
      if (!alive[k] || k === ba || k === bb) continue
      const s = (size[ba] * sim[ba][k] + size[bb] * sim[bb][k]) / (size[ba] + size[bb])
      sim[ba][k] = s; sim[k][ba] = s
    }
    size[ba] += size[bb]
    alive[bb] = false
    for (let i = 0; i < n; i++) if (parent[i] === bb) parent[i] = ba
    parent[bb] = ba
    clusters--
  }
  const number = new Map<number, number>()
  idx.forEach((i, k) => {
    let r = k
    while (parent[r] !== r) r = parent[r]
    if (!number.has(r)) number.set(r, number.size + 1)
    out[i] = number.get(r)!
  })
  for (let i = 0; i < out.length; i++) {
    if (out[i] !== 0) continue
    let before = -1, after = -1
    for (let j = i - 1; j >= 0; j--) if (embs[j]) { before = j; break }
    for (let j = i + 1; j < out.length; j++) if (embs[j]) { after = j; break }
    out[i] = before < 0 ? out[after] : after < 0 ? out[before] : i - before <= after - i ? out[before] : out[after]
  }
  return out
}
