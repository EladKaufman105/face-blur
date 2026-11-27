// The export's numbers for the progress screen's stats panel (a tap on the percentage, as the analysis
// screen's): the encoding speed, the frames done, the time a frame's blur takes, which encoder runs, the
// sound's steps. The Android app's ExportStats has the same idea: the worker reads its counters (totals
// since the export began) now and then, ExportHistory turns them into the current per-second values and
// keeps the last 60 samples (about a second apart). Plain data only: it crosses from the worker to the page.

/** The last [KEEP] samples are kept (as the analysis stats). */
export const KEEP = 60

/** What the sound goes through (in order of the list the panel shows). */
export type AudioStep = 'copy' | 'reencode' | 'voice' | 'sfx' | 'cuts' | 'none'

/** Whether the encoder is likely to be the device's (hardware) or the browser's own (software). WebCodecs doesn't say which it picked: this is what it says it can do. */
export type EncoderKind = 'hardware' | 'software' | 'unknown'

/** What is known when the export starts. */
export interface ExportFacts {
  /** The source's size and the result's (px). */
  srcWidth: number
  srcHeight: number
  width: number
  height: number
  /** 'avc' (H.264) or 'hevc'. */
  codec: 'avc' | 'hevc'
  encoder: EncoderKind
  /** Bits a second asked of the encoder. */
  bitrate: number
  /** The source's frame rate, and the frames it has (an estimate from its length). */
  fps: number
  totalFrames: number
  audio: AudioStep[]
}

/** The counters at one moment: totals since the export began. */
export interface ExportSnapshot {
  /** performance.now() of the worker when it was read. */
  atMs: number
  /** Frames that came in (a frame that is cut out counts too: they are what the progress counts), and those that were drawn (the blur step) with the time they spent in it (all of them, ms). */
  frames: number
  blurFrames: number
  blurMs: number
}

/** What the panel shows: current values (over the last second or so, not the whole export) and the last minute of the two main ones. */
export interface ExportStats {
  facts: ExportFacts
  latest: ExportSnapshot
  /** Frames a second, now and over the whole export so far. */
  fps: number
  fpsAvg: number
  /** ms a frame spends in the blur step, now. */
  blurMs: number
  fpsHistory: number[]
  blurHistory: number[]
}

/** Turns the snapshots of one export into [ExportStats], as the analysis' StatsHistory does. */
export class ExportHistory {
  private base: ExportSnapshot | null = null
  private first: ExportSnapshot | null = null
  private v = { fps: 0, blur: 0 }
  private fpsH: number[] = []
  private blurH: number[] = []

  private facts: ExportFacts
  private keep: number
  private stepMs: number

  // (No parameter properties: tests/exportstats.cjs runs this file in Node as it is.)
  constructor(facts: ExportFacts, keep = KEEP, stepMs = 1000) {
    this.facts = facts
    this.keep = keep
    this.stepMs = stepMs
  }

  add(s: ExportSnapshot): ExportStats {
    const b = this.base
    this.first ??= s
    if (!b) {
      this.base = s
    } else if (s.atMs - b.atMs < this.stepMs) {
      // Until a whole second is in: the values since the start.
      if (!this.fpsH.length) this.rates(this.first, s)
    } else {
      this.rates(b, s)
      this.fpsH.push(this.v.fps)
      this.blurH.push(this.v.blur)
      while (this.fpsH.length > this.keep) { this.fpsH.shift(); this.blurH.shift() }
      this.base = s
    }
    const f = this.first
    const whole = s.atMs - f.atMs
    return {
      facts: this.facts,
      latest: s,
      fps: this.v.fps,
      fpsAvg: whole > 0 ? ((s.frames - f.frames) * 1000) / whole : 0,
      blurMs: this.v.blur,
      fpsHistory: [...this.fpsH],
      blurHistory: [...this.blurH],
    }
  }

  /** The values over [b] .. [s]; each stays as it was when nothing happened. */
  private rates(b: ExportSnapshot, s: ExportSnapshot) {
    const dt = s.atMs - b.atMs
    const df = s.frames - b.frames
    if (dt > 0 && df >= 0) this.v.fps = (df * 1000) / dt
    const bf = s.blurFrames - b.blurFrames
    if (bf > 0) this.v.blur = Math.max(0, (s.blurMs - b.blurMs) / bf)
  }
}

/** A time or a count: whole from 10 up, else one decimal (always with a dot). */
export const fmt = (v: number) => (v >= 9.95 ? String(Math.round(v)) : v.toFixed(1))

const mmss = (sec: number) => {
  const s = Math.max(0, Math.floor(sec + 1e-6))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

export interface ExportReportContext {
  /** "ONIT Web 2.0 (build 123, beta)" */
  app: string
  /** The browser and the device, one line each (analysis-stats' deviceLines). */
  device: string[]
  elapsedSec: number
  percent: number
}

/**
 * The stats as plain text to paste to an AI agent: keys in English in every language, values as numbers;
 * the last 60 samples are about a second apart.
 */
export function exportReport(e: ExportStats, c: ExportReportContext): string {
  const f = e.facts
  const avg = (v: number[]) => (v.length ? v.reduce((a, b) => a + b, 0) / v.length : 0)
  const series = (key: string, now: number, h: number[], unit: string) => {
    const x = h.length ? h : [now]
    return `${key}: now ${fmt(now)}${unit}, avg ${fmt(avg(x))}${unit}, min ${fmt(Math.min(...x))}${unit}, max ${fmt(Math.max(...x))}${unit}`
  }
  return [
    '# ONIT export stats (web)',
    `app: ${c.app}`,
    ...c.device,
    `elapsed: ${mmss(c.elapsedSec)} · progress: ${c.percent}%`,
    `source: ${f.srcWidth}x${f.srcHeight}, ${fmt(f.fps)} fps`,
    `output: ${f.width}x${f.height}, ${f.codec === 'hevc' ? 'HEVC' : 'H.264'}, ${(f.bitrate / 1e6).toFixed(1)} Mbps`,
    `encoder: WebCodecs, ${f.encoder}${f.encoder === 'hardware' ? ' (likely: the browser reported hardware encoding as supported)' : ''}`,
    series('encode_fps', e.fps, e.fpsHistory, ''),
    `encode_fps_whole_export: ${fmt(e.fpsAvg)}`,
    `frames: ${e.latest.frames} of about ${f.totalFrames}`,
    series('blur_ms_per_frame', e.blurMs, e.blurHistory, ''),
    `audio_steps: ${f.audio.join(', ')}`,
  ].join('\n')
}
