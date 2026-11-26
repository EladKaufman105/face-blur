// What face detection really covered: measured from the frames that got a detection, not from
// the rate it aimed at. Per shot (a cut starts a new one): detections a second over sliding
// windows of 2 s, the longest gap between two detections, and whether each cut's frame was
// detected. A window under its need marks the video reduced (the notice), whatever the pace said.
//
// The need of a window is what full detection would give on its very frames, at the share that
// still counts as full: per frame interval dt, min(N, 0.8 x min(maxRate, 1 / dt)) detections a
// second, N = needRate(the nominal frame rate) (lib/pace.ts). On a steady clip that is N; a stretch of a
// variable-frame-rate clip with fewer frames needs fewer, and a hole in the frames (nothing to
// detect on) needs almost nothing. Windows shorter than 1 s (a short shot, a shot's last moments)
// aren't judged: a single gap of two frames would read as half the rate.
// (No imports: the pure tests run it in Node as it is.)

/** The windows' length, and the shortest judged (s). */
export const WINDOW_S = 2
export const MIN_WINDOW_S = 1
export interface CoverageOptions {
  /** Full detection's rate (pace.ts's fullRate(fps), or fps when sensitive). */
  maxRate: number
  /** The fewest detections a second that still count as full on a steady clip (pace.ts's needRate), and the share of full detection that does on any frames (FULL_SHARE). */
  need: number
  share: number
}

export interface Coverage {
  /** Frames seen, and detected. */
  frames: number
  detections: number
  /** Detections a second within shots (the gaps between detections over their time; 0 before two). */
  rate: number
  /** The fewest detections a second in a judged window (null: none judged yet). */
  minWindow: number | null
  /** The need on a steady clip (needRate). */
  need: number
  /** Windows judged, and those under their need; where the first of those starts (s). */
  windows: number
  short: number
  shortAt: number | null
  /** The longest gap within a shot, in frames (also from a shot's start to its first detection and from its last to its end) and in seconds (between detections). */
  maxGapFrames: number
  maxGapS: number
  /** Cuts, and cuts whose frame wasn't detected. */
  cuts: number
  cutsMissed: number
}

/** Feeds frames as they come (incremental); result() at any time. */
export class CoverageMeter {
  private o: CoverageOptions
  private frames = 0
  private dets = 0
  private prevTs = NaN
  /** The need accumulated in this shot (detections). */
  private e = 0
  /** The shot's first frame's index, the last detection's index and time (-1: none yet in this shot). */
  private shotStart = 0
  private lastIdx = -1
  private lastTs = 0
  /** Detections of this shot from the oldest window not yet judged: time, need so far. */
  private wt: number[] = []
  private we: number[] = []
  private gaps = 0
  private gapTime = 0
  private c: Omit<Coverage, 'frames' | 'detections' | 'rate' | 'need'> = { minWindow: null, windows: 0, short: 0, shortAt: null, maxGapFrames: 0, maxGapS: 0, cuts: 0, cutsMissed: 0 }

  constructor(o: CoverageOptions) {
    this.o = o
  }

  /** The need of a frame interval [dt] (s), in detections. */
  private needOf(dt: number) {
    return dt > 0 ? dt * Math.min(this.o.need, this.o.share * Math.min(this.o.maxRate, 1 / dt)) : 0
  }

  /** The next frame: its time (s), whether it is a cut (the first frame of a new shot), whether it got a detection. */
  frame(ts: number, cut: boolean, detected: boolean) {
    const i = this.frames++
    if (cut && i > 0) {
      this.endShot(i)
      this.c.cuts++
      if (!detected) this.c.cutsMissed++
    } else if (i > 0) this.e += this.needOf(ts - this.prevTs)
    this.prevTs = ts
    if (!detected) return
    this.dets++
    if (this.lastIdx < 0) {
      // A shot's first detection: the frames before it are a gap too.
      this.c.maxGapFrames = Math.max(this.c.maxGapFrames, i - this.shotStart + (i > this.shotStart ? 1 : 0))
    } else {
      this.gaps++
      this.gapTime += ts - this.lastTs
      this.c.maxGapFrames = Math.max(this.c.maxGapFrames, i - this.lastIdx)
      this.c.maxGapS = Math.max(this.c.maxGapS, ts - this.lastTs)
    }
    this.lastIdx = i
    this.lastTs = ts
    this.wt.push(ts)
    this.we.push(this.e)
    // The windows from the oldest detections end at the one before this (2 s from their start).
    let j = 0
    while (j < this.wt.length - 1 && ts - this.wt[j] > WINDOW_S + 1e-6) {
      this.judge(j, this.wt.length - 2)
      j++
    }
    if (j) {
      this.wt.splice(0, j)
      this.we.splice(0, j)
    }
  }

  private judge(j: number, k: number) {
    // The window's end: the last detection within 2 s of its start.
    while (k > j && this.wt[k] - this.wt[j] > WINDOW_S + 1e-6) k--
    const span = this.wt[k] - this.wt[j]
    if (k <= j || span < MIN_WINDOW_S - 1e-6) return
    const n = k - j, need = this.we[k] - this.we[j]
    const c = this.c
    c.windows++
    c.minWindow = Math.min(c.minWindow ?? Infinity, n / span)
    if (n < need - 1e-9) {
      c.short++
      c.shortAt ??= this.wt[j]
    }
  }

  /** The shot that ends before frame [end]: its last windows, the gap to its end. */
  private endShot(end: number) {
    for (let j = 0; j < this.wt.length - 1; j++) this.judge(j, this.wt.length - 1)
    if (this.lastIdx >= 0) this.c.maxGapFrames = Math.max(this.c.maxGapFrames, end - this.lastIdx)
    else if (end > this.shotStart) this.c.maxGapFrames = Math.max(this.c.maxGapFrames, end - this.shotStart + 1)
    this.wt = []
    this.we = []
    this.e = 0
    this.lastIdx = -1
    this.shotStart = end
  }

  /** The measures so far; with [final], the last shot is closed (its last windows judged). Feed no frames after a final result. */
  result(final = false): Coverage {
    if (final && this.frames > 0) this.endShot(this.frames)
    return { frames: this.frames, detections: this.dets, rate: this.gapTime > 0 ? this.gaps / this.gapTime : 0, need: this.o.need, ...this.c }
  }
}

/** The measures of a whole analysis: the frames' times (s), the detected frames' indices, the cuts' frame indices. */
export function measureCoverage(times: ArrayLike<number>, detected: Iterable<number>, cuts: Iterable<number>, o: CoverageOptions): Coverage {
  const d = new Set(detected), c = new Set(cuts)
  const m = new CoverageMeter(o)
  for (let i = 0; i < times.length; i++) m.frame(times[i], c.has(i), d.has(i))
  return m.result(true)
}
