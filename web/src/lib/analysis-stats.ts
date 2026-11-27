// The analysis pass's numbers for the stats panel (a tap on the percentage while faces are found):
// where the time goes, now and over the last minute. Same idea as the Android app's AnalysisStats:
// the worker reads its counters (totals since the pass began) with each preview, StatsHistory turns
// them into the current per-second values and keeps the last 60 samples (about a second apart).
// Plain data only: it crosses from the worker to the page in a message.
import { diagLines, type DeviceDiag } from './devicediag.ts'

/** The counters at one moment: totals since the pass began (ms), and how detection is set up now. */
export interface StatsSnapshot {
  /** performance.now() of the worker when it was read. */
  atMs: number
  frames: number
  /** Time on the analysis thread, all frames, without the two waits below. */
  frameMs: number
  /** Time frames waited for a free detector (CPU workers only), all frames. */
  waitMs: number
  /** Time frames waited for the decoder, all frames. */
  decodeWaitMs: number
  /** The cut check's time, all frames. */
  cutMs: number
  /** Detections finished, and the time of each one (a detector's own, summed). */
  detections: number
  detectMs: number
  // The parts of the detections finished, summed (ms). Counted by the detectors, so a detection
  // can be in one and not yet the other: [partDetections] goes with these.
  partDetections: number
  pre: number
  inf: number
  dec: number
  coarse: number
  coarseRuns: number
  check: number
  verify: number
  merge: number
  /** 'webgpu' or 'wasm', and the model's precision. */
  backend: string
  precision: string
  /** Why detection is not on the GPU (or left it), if it isn't. */
  noGpu: string | null
  /** The fine pass's long side, in px. */
  side: number
  /** Detections aimed for per second of video. */
  rate: number
  /** Detectors working side by side, and how many cores the device says it has. */
  workers: number
  cores: number
  /** What detection really covered so far (lib/coverage.ts). */
  measured: Measured
  /** The detection mode ('normal', 'extended', 'fast', 'selfie') and the sensitivity (0..1, lib/sensitivity.ts). */
  mode?: string
  sensitivity?: number
  /** What the device report adds (lib/devicediag.ts): the GPU, the threads, the isolation, the workers. */
  diag?: DeviceDiag
}

/** Measured from the frames really detected: D, G, S. */
export interface Measured {
  /** D: detections a second within shots; the fewest in a judged 2 s window (null: none yet); the need on a steady clip. */
  rate: number
  minWindow: number | null
  need: number
  /** G: the longest gap within a shot, in frames and in seconds. */
  gapFrames: number
  gapS: number
  /** S: the smallest fine pass that ran (long side, px), and whether YuNet ran. */
  minSide: number
  yunet: boolean
}

/** One metric's last samples, oldest first. */
export type Series = number[]

export const sum = (v: Series) => v.reduce((a, b) => a + b, 0)
export const avg = (v: Series) => (v.length ? sum(v) / v.length : 0)
export const min = (v: Series) => (v.length ? Math.min(...v) : 0)
export const max = (v: Series) => (v.length ? Math.max(...v) : 0)

/** What the panel shows: current values (over the last second or so, not the whole pass) and the last minute of the main ones. */
export interface AnalysisStats {
  latest: StatsSnapshot
  /** ms per detection, ms a frame waited for a detector, ms per frame. */
  detectMs: number
  waitMs: number
  frameMs: number
  /** ms a frame waited for the decoder; the cut check, per frame. */
  decodeWaitMs: number
  cutMs: number
  /** Per detection, in ms: the fine and coarse passes (and the share of detections that ran the coarse one), the GPU's inference, the GPU-vs-CPU check, the crop re-check, the merge. */
  fineMs: number
  coarseMs: number
  coarseShare: number
  gpuMs: number
  checkMs: number
  verifyMs: number
  mergeMs: number
  detectHistory: Series
  waitHistory: Series
  frameHistory: Series
  sideHistory: Series
  rateHistory: Series
}

export const KEEP = 60

/**
 * Turns the snapshots of one analysis run into [AnalysisStats]: about once a second, each value is
 * what finished in that second (the time added over the count added), or the value before when
 * nothing finished; the last [keep] of them are kept.
 */
export class StatsHistory {
  private base: StatsSnapshot | null = null
  private v = { detect: 0, wait: 0, frame: 0, decodeWait: 0, cut: 0, fine: 0, coarse: 0, share: 0, gpu: 0, check: 0, verify: 0, merge: 0 }
  private detectH: Series = []
  private waitH: Series = []
  private frameH: Series = []
  private sideH: Series = []
  private rateH: Series = []

  constructor(private keep = KEEP, private stepMs = 1000) {}

  add(s: StatsSnapshot): AnalysisStats {
    const b = this.base
    if (!b) {
      this.base = s
      if (!this.detectH.length) this.rates(zero(s), s)
    } else if (s.atMs - b.atMs < this.stepMs) {
      // Until a whole second is in: the averages since the start.
      if (!this.detectH.length) this.rates(zero(s), s)
    } else {
      this.rates(b, s)
      push(this.detectH, this.v.detect, this.keep)
      push(this.waitH, this.v.wait, this.keep)
      push(this.frameH, this.v.frame, this.keep)
      push(this.sideH, s.side, this.keep)
      push(this.rateH, s.rate, this.keep)
      this.base = s
    }
    const v = this.v
    return {
      latest: s,
      detectMs: v.detect, waitMs: v.wait, frameMs: v.frame, decodeWaitMs: v.decodeWait, cutMs: v.cut,
      fineMs: v.fine, coarseMs: v.coarse, coarseShare: v.share, gpuMs: v.gpu, checkMs: v.check, verifyMs: v.verify, mergeMs: v.merge,
      detectHistory: [...this.detectH], waitHistory: [...this.waitH], frameHistory: [...this.frameH], sideHistory: [...this.sideH], rateHistory: [...this.rateH],
    }
  }

  /** The values over [b] .. [s]; each stays as it was when nothing it counts finished. */
  private rates(b: StatsSnapshot, s: StatsSnapshot) {
    const v = this.v
    const pos = (x: number) => Math.max(0, x)
    const d = s.detections - b.detections
    if (d > 0) v.detect = pos((s.detectMs - b.detectMs) / d)
    const p = s.partDetections - b.partDetections
    if (p > 0) {
      v.fine = pos((s.pre - b.pre + s.inf - b.inf + s.dec - b.dec) / p)
      v.coarse = pos((s.coarse - b.coarse) / p)
      v.share = Math.min(1, pos((s.coarseRuns - b.coarseRuns) / p))
      v.gpu = s.backend === 'webgpu' ? pos((s.inf - b.inf) / p) : 0
      v.check = pos((s.check - b.check) / p)
      v.verify = pos((s.verify - b.verify) / p)
      v.merge = pos((s.merge - b.merge) / p)
    }
    const f = s.frames - b.frames
    if (f > 0) {
      v.frame = pos((s.frameMs - b.frameMs) / f)
      v.wait = pos((s.waitMs - b.waitMs) / f)
      v.decodeWait = pos((s.decodeWaitMs - b.decodeWaitMs) / f)
      v.cut = pos((s.cutMs - b.cutMs) / f)
    }
  }
}

const zero = (s: StatsSnapshot): StatsSnapshot => ({
  ...s, frames: 0, frameMs: 0, waitMs: 0, decodeWaitMs: 0, cutMs: 0, detections: 0, detectMs: 0,
  partDetections: 0, pre: 0, inf: 0, dec: 0, coarse: 0, coarseRuns: 0, check: 0, verify: 0, merge: 0,
})

function push(q: Series, x: number, keep: number) {
  q.push(x)
  while (q.length > keep) q.shift()
}

/** A time or a count: whole from 10 up, else one decimal (always with a dot). */
export const fmt = (v: number) => (v >= 9.95 ? String(Math.round(v)) : v.toFixed(1))

/** Selfie mode's [StatsSnapshot.noGpu] (the panel shows it translated). */
export const SELFIE_NO_GPU = 'selfie mode runs on the CPU'

/** "gpu fp16", "cpu fp32 · why not the GPU". */
export function backendText(s: StatsSnapshot, why: (noGpu: string) => string = (x) => x): string {
  const gpu = s.backend === 'webgpu'
  return [gpu ? 'GPU' : 'CPU', s.precision, !gpu && s.noGpu ? why(s.noGpu) : ''].filter(Boolean).join(' · ')
}

/** One line to compare runs (also in the console). */
export function summary(a: AnalysisStats): string {
  const s = a.latest
  const m = s.measured
  return `${s.backend} ${s.precision} · ${s.rate.toFixed(1)}/s · ${s.side} px · ${s.frames} frames · ${fmt(a.detectMs)} ms a detection · ${s.workers} detectors · measured ${m.rate.toFixed(1)}/s (min ${m.minWindow?.toFixed(1) ?? '-'}), gap ${m.gapFrames} frames, ${m.minSide} px, YuNet ${m.yunet ? 'on' : 'off'}${s.noGpu ? ` · no GPU: ${s.noGpu}` : ''}${s.mode && s.mode !== 'normal' ? ` · ${s.mode} detection` : ''}${s.sensitivity ? ` · sensitivity ${Math.round(s.sensitivity * 100)}%` : ''}`
}

export interface ReportContext {
  /** "ONIT Web 2.0 (build 123, beta)" */
  app: string
  /** The browser and the device, one line each (see [deviceLines]). */
  device: string[]
  video: { width: number; height: number; fps: number; duration: number }
  elapsedSec: number
  percent: number
}

const mmss = (sec: number) => {
  const s = Math.max(0, Math.floor(sec + 1e-6))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/**
 * The stats as plain text to paste to an AI agent: keys in English in every language, values as
 * numbers; avg/min/max over the kept samples (the last 60, about a second apart).
 */
export function statsReport(a: AnalysisStats, c: ReportContext): string {
  const l = a.latest
  const line = (key: string, now: number, h: Series, unit = '') => {
    const e = !h.length
    return `${key}: now ${fmt(now)}${unit}, avg ${fmt(e ? now : avg(h))}${unit}, min ${fmt(e ? now : min(h))}${unit}, max ${fmt(e ? now : max(h))}${unit}`
  }
  // The sizes the detector went through, each once in a row.
  const sides: number[] = []
  for (const px of a.sideHistory) if (sides[sides.length - 1] !== px) sides.push(px)
  if (!sides.length) sides.push(l.side)
  const gpu = l.backend === 'webgpu'
  const fps = String(Math.round(c.video.fps * 100) / 100)
  const out = [
    '# ONIT face detection stats (web)',
    `app: ${c.app}`,
    ...c.device,
    `video: ${c.video.width}x${c.video.height}, ${fps} fps, ${mmss(c.video.duration)}`,
    `elapsed: ${mmss(c.elapsedSec)} · progress: ${c.percent}%`,
    `backend: ${l.backend} ${l.precision}${gpu ? '' : ` (no gpu: ${l.noGpu || '-'})`}`,
    `detection_mode: ${l.mode ?? 'normal'}, sensitivity ${Math.round((l.sensitivity ?? 0) * 100)}%`,
    line('sampling_detections_per_s', l.rate, a.rateHistory),
    `resolution_px: ${l.side} (last 60 samples, ~1 s apart: ${sides.join(' -> ')})`,
    line('detect_ms', a.detectMs, a.detectHistory),
    line('wait_ms_per_frame', a.waitMs, a.waitHistory),
    line('frame_ms', a.frameMs, a.frameHistory),
    `measured_detections_per_s: ${l.measured.rate.toFixed(2)} (fewest in a 2 s window ${l.measured.minWindow?.toFixed(2) ?? '-'}, needed ${l.measured.need.toFixed(2)})`,
    `measured_max_gap: ${l.measured.gapFrames} frames, ${l.measured.gapS.toFixed(3)} s`,
    `measured_strength: ${l.measured.minSide} px (smallest fine pass), yunet ${l.measured.yunet ? 'on' : 'off'}`,
    `workers: ${l.workers} detectors, ${l.cores} cores`,
    `per_detection_ms: fine ${fmt(a.fineMs)}, coarse ${fmt(a.coarseMs)} (ran on ${Math.round(a.coarseShare * 100)}% of detections), gpu_inference ${gpu ? fmt(a.gpuMs) : '-'}, gpu_check ${fmt(a.checkMs)}, crop_check ${fmt(a.verifyMs)}, merge ${fmt(a.mergeMs)}`,
    `per_frame_ms: decode_wait ${fmt(a.decodeWaitMs)}, cut_check ${fmt(a.cutMs)}`,
    `frames: ${l.frames}, detections_per_frame: ${l.frames ? (l.detections / l.frames).toFixed(2) : '0'}`,
    ...(l.diag ? diagLines(l.diag) : []),
  ]
  return out.join('\n')
}

/** The browser and the device, as report lines: what the browser says (it says little on some). */
export async function deviceLines(): Promise<string[]> {
  const n = navigator as any
  const lines = [`browser: ${navigator.userAgent}`]
  lines.push(`device: ${n.hardwareConcurrency ?? '?'} cores, memory ${n.deviceMemory !== undefined ? `>= ${n.deviceMemory} GB` : 'not given'}, screen ${screen.width}x${screen.height} @${window.devicePixelRatio}x`)
  let gpu = 'no WebGPU'
  if (n.gpu) {
    try {
      const adapter = await Promise.race([n.gpu.requestAdapter(), new Promise<null>((r) => setTimeout(() => r(null), 1500))])
      if (adapter) {
        const i = adapter.info ?? {}
        const name = [i.vendor, i.architecture, i.device, i.description].filter(Boolean).join(' ')
        gpu = `${name || 'adapter'}${adapter.features?.has?.('shader-f16') ? ', shader-f16' : ''}${adapter.isFallbackAdapter ? ', fallback' : ''}`
      } else gpu = 'WebGPU without an adapter'
    } catch {
      gpu = 'WebGPU error'
    }
  }
  lines.push(`webgpu_adapter: ${gpu}`)
  return lines
}
