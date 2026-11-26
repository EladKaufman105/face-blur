// How fast face detection runs on this device. The app's detection is the reference: the fine
// pass at 1280 px (GPU) or 960 px (CPU), up to 15 detections per second of video. A device that
// can't keep that up goes lighter, stepwise, and says so (`reduced`), as the app's
// FaceAnalyzer.adapt does.
//
// Two times drive it. The whole detection's (RetinaFace, YuNet, the re-check of uncertain faces)
// sets the rate. RetinaFace's own passes (fine + coarse) set the frame's size: YuNet runs on a
// fixed 640 x 640 input whatever the frame's size, so its cost never makes the frame smaller.
//
// It is never weaker than the web's detection before YuNet (`OLD`: RetinaFace alone at 960 px on
// the GPU and 640 px on the CPU; on the GPU 640 px once a detection took over 200 ms; 600 ms of
// detection per second of video, at most 15 a second on the GPU and 10 on the CPU, at least 4):
//  - the frame never goes below that detection's size on this device: on the CPU 640 px; on the
//    GPU 960 px, and 640 px only once RetinaFace's own passes at 960 px take over 200 ms (then
//    that detection, which also re-checked uncertain faces, took longer still and was at 640 px);
//  - the rate never goes below that detection's rate on this device, at either of its sizes: its
//    time is at least RetinaFace's fine pass at 640 px (estimated from the fine pass now, scaled
//    down by the area, which underestimates it: the fixed costs don't shrink), so the rate from
//    that time is at least the rate it had.
// So YuNet's cost can make the analysis take longer, but never makes detection lighter than it
// was before YuNet.

/** The smallest the fine pass gets (the app's LOW_SIDE), the size between, and the fewest detections per second. */
export const LOW_SIDE = 640
export const MID_SIDE = 960
export const MIN_RATE = 4
/** On the GPU, the fine pass leaves 1280 px only when RetinaFace's own passes take longer than this (ms; the app's GPU_SLOW_MS). */
export const GPU_SLOW_MS = 80
/** Detection time allowed per second of video, per detector: analysis stays near the video's own speed. */
export const BUDGET_MS = 1000
/** Below this share of the wanted rate (or at a lower size) detection counts as reduced. */
export const FULL_SHARE = 0.8
/** The rate size steps are judged against: sensitive mode (every frame) never shrinks the frame to chase 30 a second. */
export const SIZE_RATE = 15
/** The web's detection before YuNet, the floor (see above). */
export const OLD = { gpuSide: 960, lowSide: 640, gpuSlowMs: 200, budgetMs: 600, gpuRate: 15, cpuRate: 10 } as const

export interface PaceIn {
  /** The mean time of the latest detections, whole (RetinaFace, YuNet, the re-check, the fusion), ms (not the GPU's check against the CPU, not the first ones). */
  recentMs: number
  /** Of that, RetinaFace's own passes (fine + coarse: pixels to tensor, inference, decoding), ms. */
  rfMs: number
  /** Of that, the fine pass alone, ms. */
  fineMs: number
  /** Detectors running side by side. */
  workers: number
  /** The rate wanted: 15, or every frame when sensitive. */
  maxRate: number
  /** The fine pass's long side now, and the one detection starts at (1280 GPU, 960 CPU). */
  side: number
  startSide: number
  /** The rate now. */
  rate: number
  gpu: boolean
  /** The long side of the frames detection gets: a fine pass set bigger than that runs at that (the detector doesn't enlarge), so such a size step changes nothing. */
  frameSide?: number
  /** The video's frame rate: a rate that still detects on every frame isn't reduced, whatever [maxRate] is (a 10 fps clip at 10 a second). */
  frameRate?: number
}

export interface Pace {
  /** Detections per second of video from now on. */
  rate: number
  /** The fine pass's long side from now on. */
  side: number
  /** Detection is lighter than the app's (a smaller frame than it would have, or a rate under 80% of the wanted). */
  reduced: boolean
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

/** The analysis detects on a frame when this long after the last detection: 1 / rate, less this slack (s). */
export const GATE_SLACK_S = 1e-3

/**
 * The frames per second a [rate] really detects on, in a video of [frameRate] frames a second: the
 * analysis takes the first frame at least 1 / rate after the last detection (less the slack), so
 * on a 12 fps clip 11 a second is every second frame, 6 a second. Without a frame rate, the rate.
 */
export function covered(rate: number, frameRate?: number): number {
  if (!frameRate) return rate
  return frameRate / Math.max(1, Math.ceil(frameRate * (1 / rate - GATE_SLACK_S) - 1e-9))
}

/** The long side the fine pass really runs at when set to [side] (as FaceDetector.detect: it resizes only a frame over 5% bigger). */
export const runSide = (side: number, frameSide?: number) => (frameSide !== undefined && frameSide <= side * 1.05 ? frameSide : side)

/** The highest rate the detection before YuNet could have had on this device, at either of its sizes (see above): the floor of the rate. */
export function oldRate(i: PaceIn): number {
  const now = runSide(i.side, i.frameSide), low = runSide(OLD.lowSide, i.frameSide)
  const ms = i.fineMs * Math.min(1, (low / now) ** 2)
  return clamp((OLD.budgetMs * i.workers) / Math.max(1, ms), MIN_RATE, Math.min(i.maxRate, i.gpu ? OLD.gpuRate : OLD.cpuRate))
}

/**
 * One step of pacing: a size step leaves the rate as it is (the next timings say what the new
 * size allows); a rate step leaves the size. When timings recover, the size steps back up.
 */
export function pace(i: PaceIn): Pace {
  const eff = (s: number) => runSide(s, i.frameSide)
  // Rates are judged by the frames they really detect on (see covered()), against no more than
  // the video has frames (a 10 fps clip needs 10 a second, not 15); the frame's size against 15.
  const cov = (r: number) => covered(r, i.frameRate) + 1e-9
  const target = Math.min(i.maxRate, SIZE_RATE, i.frameRate ?? Infinity)
  const wanted = (BUDGET_MS * i.workers) / Math.max(1, i.recentMs)
  const floor = oldRate(i)
  const out = (rate: number, side = i.side): Pace => {
    const r = Math.min(i.maxRate, Math.max(rate, floor))
    return { rate: r, side, reduced: eff(side) < eff(i.startSide) || cov(r) < Math.min(i.maxRate, i.frameRate ?? Infinity) * FULL_SHARE }
  }
  /** On the GPU, how long RetinaFace's own passes may take at a size before the frame leaves it. */
  const gpuLimit = (side: number) => (side > MID_SIDE ? GPU_SLOW_MS : OLD.gpuSlowMs)
  if (cov(Math.min(i.maxRate, wanted)) >= target * FULL_SHARE) {
    // Keeps up. If the frame was shrunk: back up a step when the bigger one would keep up too (RetinaFace's time grows about with the area, YuNet's doesn't).
    if (i.side < i.startSide) {
      const up = Math.min(i.startSide, i.side < MID_SIDE ? MID_SIDE : i.startSide)
      const rfUp = i.rfMs * (eff(up) / eff(i.side)) ** 2
      const wantedUp = (BUDGET_MS * i.workers) / Math.max(1, i.recentMs - i.rfMs + rfUp)
      // On the GPU not back over the bigger size's own limit (no back and forth).
      if ((!i.gpu || rfUp <= gpuLimit(up)) && cov(Math.min(i.maxRate, wantedUp)) >= target * FULL_SHARE * 1.25) return out(Math.min(i.maxRate, wantedUp), up)
    }
    return out(Math.min(i.maxRate, wanted))
  }
  // Too slow. A smaller frame first, if it makes a difference: on the CPU always (960 -> 640);
  // on the GPU only when RetinaFace's own passes are what is slow (1280 -> 960 over 80 ms,
  // 960 -> 640 over 200 ms).
  const down = i.side > MID_SIDE ? MID_SIDE : LOW_SIDE
  if (i.side > LOW_SIDE && eff(down) < eff(i.side) && (!i.gpu || i.rfMs > gpuLimit(i.side))) return out(i.rate, down)
  // Then fewer detections (never under the floor).
  return out(clamp(wanted, MIN_RATE, i.maxRate))
}

/** A detection's times (ms): whole, RetinaFace's passes (fine + coarse), its fine pass; and the GPU's check against the CPU inside it, if it ran. */
export interface Timing {
  ms: number
  rfMs: number
  fineMs: number
  checkMs?: number
}

/**
 * The detections' times, in windows of [WINDOW]: each full window gives the means the pace
 * takes. The first [warmup] detections don't count (the shaders compile, the WebAssembly heap
 * grows). The GPU's check against the CPU (on the first detections, and on every one until faces
 * are seen, so on a video with none all along) isn't the device's pace: its time is taken out,
 * the detection still counts.
 */
export class Pacer {
  static readonly WINDOW = 4
  private seen = 0
  private window: Timing[] = []
  private warmup: number

  constructor(warmup: number) {
    this.warmup = warmup
  }

  /** The means when [t] completes a window, else null. */
  add(t: Timing): { recentMs: number; rfMs: number; fineMs: number } | null {
    if (++this.seen <= this.warmup) return null
    this.window.push({ ms: Math.max(0, t.ms - (t.checkMs ?? 0)), rfMs: t.rfMs, fineMs: t.fineMs })
    if (this.window.length < Pacer.WINDOW) return null
    const mean = (k: 'ms' | 'rfMs' | 'fineMs') => this.window.reduce((a, b) => a + b[k], 0) / this.window.length
    const out = { recentMs: mean('ms'), rfMs: mean('rfMs'), fineMs: mean('fineMs') }
    this.window = []
    return out
  }
}
