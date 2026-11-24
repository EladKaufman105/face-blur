// Cleaning up the sound, as the Android app does it: background noise lowered (a streaming
// Wiener filter over short-time spectra), the voice made clearer (EQ and an even level), and the
// loudness brought to what social networks expect (ITU-R BS.1770), under a limiter.

export type Cleanup = {
  /** 0 off, 1 light, 2 medium, 3 strong. */
  denoise: number
  enhance: boolean
  /** Brought to the networks' loudness. */
  loud: boolean
}

export const NO_CLEANUP: Cleanup = { denoise: 0, enhance: false, loud: false }

export const cleanupOn = (c: Cleanup) => c.denoise > 0 || c.enhance || c.loud

/**
 * [x] (mono at [rate]) cleaned up: a new array of the same length, in step with the video. The
 * loudness is measured on the cleaned sound (the file made is mono, and is what the networks
 * measure), so it lands on the target whatever the noise reduction and the voice did.
 */
export function cleanup(x: Float32Array, rate: number, c: Cleanup, beat?: () => void): { data: Float32Array; lufs: number | null; gainDb: number } {
  const y = new Float32Array(x.length)
  const den = c.denoise > 0 ? new Denoiser(rate, c.denoise) : null
  const eq = c.enhance ? new VoiceEnhancer(rate) : null
  const comp = c.enhance ? new Compressor(rate) : null
  // The denoiser's output is late by one window: read that much further on.
  const lag = den ? den.latency : 0
  for (let i = 0; i < x.length + lag; i++) {
    if (beat && (i & 0xffff) === 0) beat()
    let v = i < x.length ? x[i] : 0
    if (den) v = den.process(v)
    if (i < lag) continue
    if (eq) v = eq.process(v)
    if (comp) v *= comp.gainFor(Math.abs(v))
    y[i - lag] = v
  }
  beat?.()
  const lufs = c.loud ? measureLoudness(y, rate, 1) : null
  const gainDb = lufs !== null ? loudnessGain(lufs) : 0
  const gain = Math.pow(10, gainDb / 20)
  const lim = new Limiter(rate)
  const limit = gain > 1 || comp !== null
  beat?.()
  for (let i = 0; i < y.length; i++) {
    const v = y[i] * gain
    y[i] = Math.max(-1, Math.min(1, soft(v * (limit ? lim.gainFor(Math.abs(v)) : 1))))
  }
  return { data: y, lufs, gainDb }
}

// ------------------------------------------------------------------ noise

/**
 * Background noise reduction, streaming: the noise in each band estimated from its quietest
 * moments of the last ~1.5 s (an air conditioner, wind, traffic, hum), and a gain per band that
 * keeps speech and lowers what's only noise ("decision-directed" Wiener gain, which keeps the
 * leftover noise smooth rather than chirpy). Output is delayed by [latency] samples.
 */
export class Denoiser {
  private floor: number
  private bias: number
  private n: number
  private hop: number
  private win: Float32Array
  private bins: number
  private inBuf: Float32Array
  private inFill = 0
  private outBuf: Float32Array
  private ready: Float32Array
  private readyPos: number
  private re: Float32Array
  private im: Float32Array
  private smooth: Float32Array
  private minCur: Float32Array
  private minPrev: Float32Array
  private noise: Float32Array
  private gainPrev: Float32Array
  private snrPrev: Float32Array
  private gain: Float32Array
  private frames = 0
  private span: number

  constructor(rate: number, level = 2) {
    this.floor = level === 1 ? 0.32 : level === 3 ? 0.07 : 0.16
    this.bias = level === 1 ? 1.6 : level === 3 ? 3.2 : 2.2
    this.n = rate >= 32000 ? 1024 : 512
    this.hop = this.n / 2
    // sqrt-Hann in and out: at 50% overlap the product sums to 1.
    this.win = new Float32Array(this.n)
    for (let i = 0; i < this.n; i++) this.win[i] = Math.sqrt(0.5 - 0.5 * Math.cos((2 * Math.PI * i) / this.n))
    this.bins = this.n / 2 + 1
    this.inBuf = new Float32Array(this.n)
    this.outBuf = new Float32Array(this.n + this.hop)
    this.ready = new Float32Array(this.hop)
    this.readyPos = this.hop
    this.re = new Float32Array(this.n)
    this.im = new Float32Array(this.n)
    this.smooth = new Float32Array(this.bins)
    this.minCur = new Float32Array(this.bins).fill(Number.MAX_VALUE)
    this.minPrev = new Float32Array(this.bins).fill(Number.MAX_VALUE)
    this.noise = new Float32Array(this.bins)
    this.gainPrev = new Float32Array(this.bins).fill(1)
    this.snrPrev = new Float32Array(this.bins).fill(1)
    this.gain = new Float32Array(this.bins)
    this.span = Math.max(8, Math.floor((0.75 * rate) / this.hop))
  }

  get latency() { return this.n }

  /** One sample in, one (delayed, cleaned) sample out. */
  process(x: number): number {
    if (this.readyPos >= this.hop) this.readyPos = 0
    const y = this.ready[this.readyPos]
    this.inBuf[this.inFill++] = x
    this.readyPos++
    if (this.inFill === this.n) {
      this.frame()
      this.inBuf.copyWithin(0, this.hop, this.n)
      this.inFill = this.n - this.hop
    }
    return y
  }

  private frame() {
    const { n, re, im, bins } = this
    for (let i = 0; i < n; i++) { re[i] = this.inBuf[i] * this.win[i]; im[i] = 0 }
    fft(re, im, false)
    this.frames++
    const first = this.frames === 1
    for (let k = 0; k < bins; k++) {
      const p = re[k] * re[k] + im[k] * im[k]
      this.smooth[k] = first ? p : 0.7 * this.smooth[k] + 0.3 * p
      if (this.smooth[k] < this.minCur[k]) this.minCur[k] = this.smooth[k]
      const m = Math.min(this.minCur[k], this.minPrev[k])
      this.noise[k] = this.frames < 6 ? Math.max(this.noise[k], this.smooth[k]) : m * this.bias
    }
    if (this.frames % this.span === 0) {
      this.minPrev.set(this.minCur)
      this.minCur.fill(Number.MAX_VALUE)
    }
    for (let k = 0; k < bins; k++) {
      const p = re[k] * re[k] + im[k] * im[k]
      const nk = Math.max(this.noise[k], 1e-12)
      const post = p / nk
      const prior = ALPHA * this.gainPrev[k] * this.gainPrev[k] * this.snrPrev[k] + (1 - ALPHA) * Math.max(post - 1, 0)
      this.gain[k] = Math.max(prior / (1 + prior), this.floor)
      this.snrPrev[k] = post
    }
    // A little smoothing across bands against musical noise.
    for (let k = 0; k < bins; k++) {
      const a = this.gain[Math.max(0, k - 1)], b = this.gain[Math.min(bins - 1, k + 1)]
      const g = 0.25 * a + 0.5 * this.gain[k] + 0.25 * b
      this.gainPrev[k] = this.gain[k]
      re[k] *= g; im[k] *= g
      if (k >= 1 && k < n - k) { re[n - k] *= g; im[n - k] *= g }
    }
    fft(re, im, true)
    for (let i = 0; i < n; i++) this.outBuf[i] += re[i] * this.win[i]
    this.ready.set(this.outBuf.subarray(0, this.hop))
    this.outBuf.copyWithin(0, this.hop, n + this.hop)
    this.outBuf.fill(0, n, n + this.hop)
    this.readyPos = 0
  }
}

const ALPHA = 0.96

/** In-place radix-2 FFT; [inverse] also divides by the size. */
export function fft(re: Float32Array, im: Float32Array, inverse: boolean) {
  const n = re.length
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1
    while (j & bit) { j ^= bit; bit >>= 1 }
    j ^= bit
    if (i < j) {
      let t = re[i]; re[i] = re[j]; re[j] = t
      t = im[i]; im[i] = im[j]; im[j] = t
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = ((2 * Math.PI) / len) * (inverse ? 1 : -1)
    const wr = Math.cos(ang), wi = Math.sin(ang)
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0
      for (let k = 0; k < len / 2; k++) {
        const a = i + k, b = a + len / 2
        const xr = re[b] * cr - im[b] * ci
        const xi = re[b] * ci + im[b] * cr
        re[b] = re[a] - xr; im[b] = im[a] - xi
        re[a] += xr; im[a] += xi
        const ncr = cr * wr - ci * wi
        ci = cr * wi + ci * wr
        cr = ncr
      }
    }
  }
  if (inverse) for (let i = 0; i < n; i++) { re[i] /= n; im[i] /= n }
}

// ------------------------------------------------------------------ voice

/** A biquad filter (RBJ's cookbook), direct form I. */
class Biquad {
  private x1 = 0; private x2 = 0; private y1 = 0; private y2 = 0
  constructor(private b0: number, private b1: number, private b2: number, private a1: number, private a2: number) {}
  process(x: number): number {
    const y = this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2
    this.x2 = this.x1; this.x1 = x; this.y2 = this.y1; this.y1 = y
    return y
  }
  static make(b0: number, b1: number, b2: number, a0: number, a1: number, a2: number) {
    return new Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
  }
  static highPass(rate: number, f: number, q: number) {
    const w = (2 * Math.PI * f) / rate, c = Math.cos(w), al = Math.sin(w) / (2 * q)
    return Biquad.make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
  }
  static peak(rate: number, f: number, q: number, db: number) {
    const a = Math.pow(10, db / 40), w = (2 * Math.PI * f) / rate, c = Math.cos(w), al = Math.sin(w) / (2 * q)
    return Biquad.make(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
  }
  static highShelf(rate: number, f: number, db: number) {
    const a = Math.pow(10, db / 40)
    const w = (2 * Math.PI * Math.min(f, rate * 0.45)) / rate
    const c = Math.cos(w), al = (Math.sin(w) / 2) * Math.SQRT2, sa = 2 * Math.sqrt(a) * al
    return Biquad.make(
      a * (a + 1 + (a - 1) * c + sa), -2 * a * (a - 1 + (a + 1) * c), a * (a + 1 + (a - 1) * c - sa),
      a + 1 - (a - 1) * c + sa, 2 * (a - 1 - (a + 1) * c), a + 1 - (a - 1) * c - sa,
    )
  }
}

/**
 * "Enhance voice": rumble below the voice cut, the boxy low-mids eased, the presence that makes
 * words clear lifted, a little air on top.
 */
class VoiceEnhancer {
  private filters: Biquad[]
  constructor(rate: number) {
    this.filters = [Biquad.highPass(rate, 90, 0.707), Biquad.peak(rate, 300, 1, -2.5), Biquad.peak(rate, 3200, 0.9, 3.5), Biquad.highShelf(rate, 9000, 2)]
  }
  process(x: number): number {
    let y = x
    for (const f of this.filters) y = f.process(y)
    return y
  }
}

/** Evens out the voice's level: 3:1 above -22 dBFS, with makeup gain. */
class Compressor {
  private attack: number
  private release: number
  private env = 0
  constructor(rate: number) {
    this.attack = 1 - Math.exp(-1 / (0.005 * rate))
    this.release = 1 - Math.exp(-1 / (0.15 * rate))
  }
  gainFor(peak: number): number {
    this.env += (peak - this.env) * (peak > this.env ? this.attack : this.release)
    const db = 20 * Math.log10(Math.max(this.env, 1e-6))
    const over = db + 22
    const reduce = over > 0 ? over * (1 - 1 / 3) : 0
    return Math.pow(10, (4 - reduce) / 20)
  }
}

/** Peak limiter: fast attack, smooth release, a ceiling just under full scale. */
class Limiter {
  private attack: number
  private release: number
  private g = 1
  constructor(rate: number, private ceiling = 0.94) {
    this.attack = 1 - Math.exp(-1 / (0.0015 * rate))
    this.release = 1 - Math.exp(-1 / (0.12 * rate))
  }
  gainFor(peak: number): number {
    const want = peak > this.ceiling ? this.ceiling / peak : 1
    this.g += (want - this.g) * (want < this.g ? this.attack : this.release)
    return this.g
  }
}

/** A soft ceiling for what the limiter's attack lets through: never past 1. */
function soft(x: number): number {
  const a = Math.abs(x)
  if (a <= 0.9) return x
  const over = a - 0.9
  const y = 0.9 + 0.1 * (over / Math.sqrt(over * over + 0.01))
  return x < 0 ? -y : y
}

// ------------------------------------------------------------------ loudness

/** What TikTok, Instagram and YouTube aim for (LUFS). */
export const TARGET_LUFS = -14

/**
 * Integrated loudness (LUFS) of [x] (mono at [rate]): K-weighting, 400 ms blocks, gated. A
 * stereo source mixed down to mono measures ~3 dB lower: pass [channels] to make up for it.
 */
export function measureLoudness(x: Float32Array, rate: number, channels = 1): number | null {
  if (x.length < rate / 2) return null
  const k = kWeight(x, rate)
  const block = Math.floor(0.4 * rate), step = Math.floor(0.1 * rate)
  const powers: number[] = []
  for (let i = 0; i + block <= k.length; i += step) {
    let s = 0
    for (let j = i; j < i + block; j++) s += k[j] * k[j]
    powers.push(s / block)
  }
  if (!powers.length) return null
  const boost = channels >= 2 ? 2 : 1
  const lufs = (p: number) => -0.691 + 10 * Math.log10(Math.max(p * boost, 1e-12))
  const abs = powers.filter((p) => lufs(p) > -70)
  if (!abs.length) return null
  const mean = (v: number[]) => v.reduce((a, b) => a + b, 0) / v.length
  const rel = lufs(mean(abs)) - 10
  const kept = abs.filter((p) => lufs(p) > rel)
  return kept.length ? lufs(mean(kept)) : null
}

/** Gain (dB) from [lufs] to the target, kept reasonable. */
export const loudnessGain = (lufs: number) => Math.min(20, Math.max(-12, TARGET_LUFS - lufs))

function kWeight(x: Float32Array, rate: number): Float32Array {
  // A high shelf (+4 dB above ~1.7 kHz) and a high-pass at ~38 Hz.
  let k = Math.tan((Math.PI * 1681.974450955533) / rate)
  const q = 0.7071752369554196
  const vh = Math.pow(10, 3.999843853973347 / 20), vb = Math.pow(vh, 0.4996667741545416)
  let a0 = 1 + k / q + k * k
  const shelf = new Biquad((vh + (vb * k) / q + k * k) / a0, (2 * (k * k - vh)) / a0, (vh - (vb * k) / q + k * k) / a0, (2 * (k * k - 1)) / a0, (1 - k / q + k * k) / a0)
  k = Math.tan((Math.PI * 38.13547087602444) / rate)
  const q2 = 0.5003270373238773
  a0 = 1 + k / q2 + k * k
  const hp = new Biquad(1, -2, 1, (2 * (k * k - 1)) / a0, (1 - k / q2 + k * k) / a0)
  const y = new Float32Array(x.length)
  for (let i = 0; i < x.length; i++) y[i] = hp.process(shelf.process(x[i]))
  return y
}
