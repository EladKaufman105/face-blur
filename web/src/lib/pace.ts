// How fast face detection runs on this device. The app's detection is the reference: the fine
// pass at 1280 px (GPU) or 960 px (CPU), on the frames the app's FaceAnalyzer takes: about 15 a
// second of video, as every n-th frame (n = the frame rate / 15, rounded; at least 1): every frame
// of a 20 fps clip, every second of a 31 fps one (15.5 a second), every third of a 50 fps one. The
// full rate here is the more of the two (fullRate()): 15 a second (on any frame rate, see
// DetectGate; on a clip with fewer frames, every frame) or the app's cadence. Every frame in wide
// detection. A device that can't keep that up
// goes lighter, stepwise, and says so (`reduced`), as the app's FaceAnalyzer.adapt does, down to 8
// a second (the app's floor).
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
//    that time is at least the rate it had (and never under 8, where it went down to 4);
//  - at a rate, DetectGate detects on every frame that detection's gate (the next frame at least
//    a period after the last detected one) took: that gate runs beside the schedule.
// So YuNet's cost can make the analysis take longer, but never makes detection lighter than it
// was before YuNet.

/** The smallest the fine pass gets (the app's LOW_SIDE), the size between, and the fewest detections per second (the app's MIN_RATE). */
export const LOW_SIDE = 640
export const MID_SIDE = 960
export const MIN_RATE = 8
/** Detections per second of video the app aims at (its RATE). */
export const RATE = 15
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
  /** The rate wanted: fullRate(the frame rate), or every frame when sensitive. */
  maxRate: number
  /** The fine pass's long side now, and the one detection starts at (1280 GPU, 960 CPU). */
  side: number
  startSide: number
  /** The rate now. */
  rate: number
  gpu: boolean
  /** The long side of the frames detection gets: a fine pass set bigger than that runs at that (the detector doesn't enlarge), so such a size step changes nothing. */
  frameSide?: number
  /** The video's frame rate (measured, FrameRate): a rate that still detects on every frame isn't reduced, whatever [maxRate] is (a 10 fps clip at 10 a second). */
  frameRate?: number
  /**
   * The nominal frame rate, read from the first packets: the frame's size is judged against
   * min(15, it), as the detection before (develop) did, so a measured rate that differs never
   * keeps a bigger frame where that detection took a smaller one and more frames, nor the reverse.
   */
  nominal?: number
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

/** The app's detections per second of video at [fps]: every n-th frame, n = fps / 15 rounded, at least 1 (FaceAnalyzer's baseEvery). */
export const appRate = (fps: number) => fps / Math.max(1, Math.round(fps / RATE))

/**
 * The full rate of normal detection at a nominal [fps]: 15 a second, or the app's cadence where
 * that is more (20 fps: every frame; 31 fps: 15.5; 50 fps: 16.7). Never less than either, so never
 * weaker than the app on a device that keeps up. Never under 15, whatever [fps] says: the nominal
 * frame rate is read from the first packets and can be low (a dark start, a screen recording with
 * a long first frame) where the frames then come faster; DetectGate detects on every frame of a
 * clip that really has fewer than the rate.
 */
export const fullRate = (fps: number) => Math.max(RATE, appRate(fps))

/** Normal detection at the app's own cadence isn't reduced (23.976 fps: every second frame, 12 a second, against 80% of 15). */
export const APP_SHARE = 0.97

/**
 * The fewest detections a second, measured (lib/coverage.ts), that still count as full detection
 * where the frames come at [fps] (the frame rate measured, not the nominal one): 80% of the full
 * rate [maxRate] (at most of the frames), and in normal detection no more than 97% of the app's
 * own cadence at [fps]; wide (every frame): 80% of the frames.
 */
export const needRate = (fps: number, sensitive: boolean, maxRate = sensitive ? fps : fullRate(fps)) =>
  sensitive ? FULL_SHARE * fps : Math.min(FULL_SHARE * Math.min(maxRate, fps), APP_SHARE * appRate(fps))

/** How far before its due time a frame still counts as due (s): timestamps rounded to the millisecond (WebM) land up to 0.5 ms early. */
export const GATE_SLACK_S = 1e-3

/**
 * Which frames get a detection: [rate] a second of video, on a fixed schedule, together with the
 * old gate (develop's) alongside it.
 *
 * The schedule: each due time is a period after the last one was due, not after the frame that
 * met it: snapping to the frame rounded every gap up to whole frames (15 a second on a 25 fps clip
 * was 12.5; 11 a second on a 12 fps clip, 6). It never runs ahead of the frames (a frame taken a
 * little early moves it back) nor more than a period behind them (after a hole in the frames, a
 * dropped stretch or a VFR pause, it goes on from about that frame: at most one detection to catch
 * up). On steady frames it detects on covered(rate, fps) frames a second.
 *
 * The old gate: the next frame at least 1 / min(rate, [oldMax]) after the last frame it took (or a
 * cut), as develop's analysis did, kept with its own last frame. Every frame it takes is taken, so
 * the detections are a superset of the old gate's at that rate: never fewer in any stretch, never
 * a longer gap, on any timestamps (on variable frame rates the schedule alone could leave a frame
 * the old gate took, and a longer gap). A frame the old gate takes and the schedule doesn't moves
 * the schedule to it (it goes on a period from there). Without [oldMax], the schedule alone.
 *
 * A cut, or [all] (wide detection at its full rate: every frame), takes the frame and starts the
 * schedule again from it.
 */
export class DetectGate {
  /** When the last detection was due (s). */
  private anchor = -Infinity
  /** The last frame the old gate took (s). */
  private oldLast = -Infinity
  private oldMax: number | undefined

  /** [oldMax]: the old gate's highest rate (develop: 15 a second, or the nominal frame rate in wide detection). */
  constructor(oldMax?: number) {
    this.oldMax = oldMax
  }

  /** Whether the frame at [ts] (s) gets a detection at [rate] a second; [cut]: a cut's frame; [all]: every frame. */
  take(ts: number, rate: number, cut = false, all = false): boolean {
    const due = this.anchor + 1 / rate
    const restart = cut || all || this.anchor === -Infinity
    const own = restart || ts >= due - GATE_SLACK_S
    // The old gate, exactly as it was (its own last frame; a cut always).
    const old = this.oldMax !== undefined && (cut || ts - this.oldLast >= 1 / Math.min(rate, this.oldMax) - GATE_SLACK_S)
    if (old) this.oldLast = ts
    if (own) this.anchor = restart ? ts : Math.min(ts, Math.max(due, ts - 1 / rate))
    else if (old) this.anchor = ts
    return own || old
  }
}

/**
 * The frame rate the frames really come at: the median of the last [FrameRate.KEEP] intervals
 * (duplicate times left out), between 1 and 240; the nominal one until 5 intervals are in, and
 * while the measure is within 3% of it. The
 * nominal rate is read from the first packets and can be far off (a screen recording that reads 8
 * fps and runs at 60, a dark start, or one that reads high).
 */
export class FrameRate {
  static readonly KEEP = 31
  private dts: number[] = []
  private prev = NaN
  private nominal: number

  constructor(nominal: number) {
    this.nominal = nominal
  }

  add(ts: number) {
    if (Number.isFinite(this.prev) && ts > this.prev) {
      this.dts.push(ts - this.prev)
      if (this.dts.length > FrameRate.KEEP) this.dts.shift()
    }
    this.prev = ts
  }

  get fps(): number {
    if (this.dts.length < 5) return this.nominal
    const d = [...this.dts].sort((a, b) => a - b)
    const fps = Math.min(240, Math.max(1, 1 / d[d.length >> 1]))
    // Within 3% of the nominal rate: the nominal one (times rounded to the millisecond read 14.93 for 15).
    return Math.abs(fps / this.nominal - 1) < 0.03 ? this.nominal : fps
  }
}

/**
 * Wide detection (every frame) as the measured frame rate [fps] changes: the full rate is [fps]; a
 * rate at the full one follows it (unless [pinned] by a test), a lower one (the pace's) stays,
 * under the full one. Every frame ([all]) while the rate covers the frames (fps at most 5% over
 * it); else the schedule at the rate, which the pace can lower (to 8 a second at least).
 */
export function follow(fps: number, maxRate: number, rate: number, pinned = false): { maxRate: number; rate: number; all: boolean } {
  const r = Math.min(fps, !pinned && rate >= maxRate - 1e-9 ? fps : rate)
  return { maxRate: fps, rate: r, all: fps <= r * 1.05 }
}

/** The frames a second DetectGate detects on at [rate] in a video of [frameRate]: the rate, at most every frame. */
export const covered = (rate: number, frameRate?: number) => Math.min(rate, frameRate ?? Infinity)

/** The long side the fine pass really runs at when set to [side] (as FaceDetector.detect: it resizes only a frame over 5% bigger). */
export const runSide = (side: number, frameSide?: number) => (frameSide !== undefined && frameSide <= side * 1.05 ? frameSide : side)

/** The highest rate the detection before YuNet could have had on this device, at either of its sizes (see above): the floor of the rate. */
export function oldRate(i: PaceIn): number {
  const now = runSide(i.side, i.frameSide), low = runSide(OLD.lowSide, i.frameSide)
  const ms = i.fineMs * Math.min(1, (low / now) ** 2)
  const most = Math.min(i.maxRate, i.gpu ? OLD.gpuRate : OLD.cpuRate)
  return clamp((OLD.budgetMs * i.workers) / Math.max(1, ms), Math.min(MIN_RATE, most), most)
}

/**
 * One step of pacing: a size step leaves the rate as it is (the next timings say what the new
 * size allows); a rate step leaves the size. When timings recover, the size steps back up.
 */
export function pace(i: PaceIn): Pace {
  const eff = (s: number) => runSide(s, i.frameSide)
  // The rate the frame's size is judged against: no more than the video has frames (a 10 fps clip needs 10 a second, not 15); with the nominal frame rate, as before (see [nominal]).
  const target = i.nominal !== undefined ? Math.min(SIZE_RATE, i.nominal) : Math.min(i.maxRate, SIZE_RATE, i.frameRate ?? Infinity)
  const wanted = (BUDGET_MS * i.workers) / Math.max(1, i.recentMs)
  const floor = oldRate(i)
  const out = (rate: number, side = i.side): Pace => {
    const r = Math.min(i.maxRate, Math.max(rate, floor))
    return { rate: r, side, reduced: eff(side) < eff(i.startSide) || covered(r, i.frameRate) < covered(i.maxRate, i.frameRate) * FULL_SHARE }
  }
  /** On the GPU, how long RetinaFace's own passes may take at a size before the frame leaves it. */
  const gpuLimit = (side: number) => (side > MID_SIDE ? GPU_SLOW_MS : OLD.gpuSlowMs)
  if (wanted >= target * FULL_SHARE) {
    // Keeps up. If the frame was shrunk: back up a step when the bigger one would keep up too (RetinaFace's time grows about with the area, YuNet's doesn't).
    if (i.side < i.startSide) {
      const up = Math.min(i.startSide, i.side < MID_SIDE ? MID_SIDE : i.startSide)
      const rfUp = i.rfMs * (eff(up) / eff(i.side)) ** 2
      const wantedUp = (BUDGET_MS * i.workers) / Math.max(1, i.recentMs - i.rfMs + rfUp)
      // On the GPU not back over the bigger size's own limit (no back and forth).
      if ((!i.gpu || rfUp <= gpuLimit(up)) && wantedUp >= target * FULL_SHARE * 1.25) return out(Math.min(i.maxRate, wantedUp), up)
    }
    return out(Math.min(i.maxRate, wanted))
  }
  // Too slow. A smaller frame first, if it makes a difference: on the CPU always (960 -> 640);
  // on the GPU only when RetinaFace's own passes are what is slow (1280 -> 960 over 80 ms,
  // 960 -> 640 over 200 ms).
  const down = i.side > MID_SIDE ? MID_SIDE : LOW_SIDE
  if (i.side > LOW_SIDE && eff(down) < eff(i.side) && (!i.gpu || i.rfMs > gpuLimit(i.side))) return out(i.rate, down)
  // Then fewer detections (never under the floor, nor under 8 a second: the app's floor).
  return out(clamp(wanted, Math.min(MIN_RATE, i.maxRate), i.maxRate))
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
