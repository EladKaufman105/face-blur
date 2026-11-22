// Sound effects: loading, hearing them while editing (in step with the video), and the samples
// the export mixes in.
export { SFX_GROUPS, SFX_NAMES } from './sfx-list'

/** A sound placed on the video. */
export interface SfxClip {
  id: number
  sound: string
  /** Start, seconds of video time. */
  at: number
  /** 0..2 */
  gain: number
}

/** A sound's samples for the export worker: mono, at [rate]. */
export interface SfxSamples {
  at: number
  gain: number
  rate: number
  data: Float32Array
}

let ctx: AudioContext | null = null
const buffers = new Map<string, Promise<AudioBuffer>>()

function audio(): AudioContext {
  ctx ??= new AudioContext()
  if (ctx.state === 'suspended') ctx.resume().catch(() => {})
  return ctx
}

export function loadSfx(sound: string): Promise<AudioBuffer> {
  let p = buffers.get(sound)
  if (!p) {
    const url = new URL(`sfx/${sound}.m4a`, document.baseURI)
    p = fetch(url).then((r) => r.arrayBuffer()).then((b) => audio().decodeAudioData(b))
    p.catch(() => buffers.delete(sound))
    buffers.set(sound, p)
  }
  return p
}

let previewNode: AudioBufferSourceNode | null = null

/** Plays a sound once, to hear it before adding it. */
export async function previewSfx(sound: string) {
  const b = await loadSfx(sound)
  previewNode?.stop()
  const c = audio()
  const n = c.createBufferSource()
  n.buffer = b
  n.connect(c.destination)
  n.start()
  previewNode = n
}

/** The editor's sounds, started and stopped with the video. */
export class SfxScheduler {
  private nodes: AudioBufferSourceNode[] = []

  stop() {
    for (const n of this.nodes) {
      try { n.stop() } catch {}
    }
    this.nodes = []
  }

  /** From video time [from] on, as the video plays; [base]: the processed voice, if any. */
  async start(clips: SfxClip[], from: number, base: AudioBuffer | null = null) {
    this.stop()
    const c = audio()
    const t0 = c.currentTime
    if (base && from < base.duration) {
      const n = c.createBufferSource()
      n.buffer = base
      n.connect(c.destination)
      n.start(c.currentTime, from)
      this.nodes.push(n)
    }
    for (const clip of clips) {
      const b = await loadSfx(clip.sound).catch(() => null)
      if (!b || clip.at + b.duration <= from) continue
      const n = c.createBufferSource()
      n.buffer = b
      const g = c.createGain()
      g.gain.value = clip.gain
      n.connect(g).connect(c.destination)
      const wait = clip.at - from
      // Time spent loading counts: the video kept playing meanwhile.
      const late = c.currentTime - t0
      if (wait - late >= 0) n.start(c.currentTime + wait - late)
      else n.start(c.currentTime, late - wait)
      this.nodes.push(n)
    }
  }
}

/** Mono samples of every clip, for the export. */
export async function sfxSamples(clips: SfxClip[]): Promise<SfxSamples[]> {
  const out: SfxSamples[] = []
  for (const clip of clips) {
    const b = await loadSfx(clip.sound).catch(() => null)
    if (!b) continue
    const data = new Float32Array(b.length)
    for (let c = 0; c < b.numberOfChannels; c++) {
      const ch = b.getChannelData(c)
      for (let i = 0; i < ch.length; i++) data[i] += ch[i] / b.numberOfChannels
    }
    out.push({ at: clip.at, gain: clip.gain, rate: b.sampleRate, data })
  }
  return out
}

/** Adds the sounds into planar audio [planes] (one Float32Array per channel) starting at [t0]. */
export function mixInto(planes: Float32Array[], rate: number, t0: number, sounds: SfxSamples[]) {
  const n = planes[0]?.length ?? 0
  const t1 = t0 + n / rate
  let touched = false
  for (const s of sounds) {
    const dur = s.data.length / s.rate
    if (s.at >= t1 || s.at + dur <= t0) continue
    touched = true
    const start = Math.max(0, Math.floor((s.at - t0) * rate))
    for (let i = start; i < n; i++) {
      const p = ((t0 + i / rate) - s.at) * s.rate
      if (p < 0) continue
      const k = Math.floor(p)
      if (k + 1 >= s.data.length) break
      const v = (s.data[k] + (s.data[k + 1] - s.data[k]) * (p - k)) * s.gain
      for (const ch of planes) ch[i] += v
    }
  }
  if (!touched) return
  // A soft limit instead of hard clipping where sounds pile up.
  for (const ch of planes) {
    for (let i = 0; i < n; i++) {
      const x = ch[i], a = Math.abs(x)
      if (a > 0.8) ch[i] = Math.sign(x) * (0.8 + 0.2 * Math.tanh((a - 0.8) / 0.2))
    }
  }
}

/** Mono samples as something the editor can play. */
export function makeBuffer(data: Float32Array, rate: number): AudioBuffer {
  const b = audio().createBuffer(1, Math.max(1, data.length), rate)
  b.copyToChannel(data as Float32Array<ArrayBuffer>, 0)
  return b
}
