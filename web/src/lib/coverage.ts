// What face detection really covered: measured from the frames that got a detection, not from
// the rate it aimed at. Per shot (a cut starts a new one): detections a second over sliding
// windows of 2 s, the longest gap between two detections, and whether each cut's frame was
// detected. A window under its need marks the video reduced (the notice), whatever the pace said.
//
// The need of a window is what full detection would give on its very frames, at the share that
// still counts as full: per frame interval dt, needAt(1 / dt) x dt detections (lib/pace.ts's
// needRate at the frame rate the frames really come at, not the nominal one, which is read from
// the first packets and can be wrong). On a steady clip that is needRate(fps); a stretch of a
// variable-frame-rate clip with fewer frames needs fewer, and a hole in the frames (nothing to
// detect on) needs almost nothing: never more than 0.8 of a detection a frame interval. Windows shorter
// than 1 s (a short shot, a shot's last moments) aren't judged: a single gap of two frames would
// read as half the rate. A gap over 2 s is judged by itself. Between two detections around an
// interval longer than a period at the rate detection aimed at (a hole in the frames), the need is
// at most one detection: a schedule at that rate can take no more there (at 120 fps with holes the
// need of the frames before a hole and of the hole would add up to more). A window may be short of its need
// by one frame interval's need (under one detection): the schedule (DetectGate) can take its last
// frame up to a frame after it was due, so a rate at the need could read just under it.
// (No imports: the pure tests run it in Node as it is.)

/** The windows' length, and the shortest judged (s). */
export const WINDOW_S = 2
export const MIN_WINDOW_S = 1

export interface CoverageOptions {
  /** The fewest detections a second that still count as full where the frames come at [fps] a second (lib/pace.ts's needRate with the full rate). */
  needAt: (fps: number) => number
}

export interface Coverage {
  /** Frames seen, and detected. */
  frames: number
  detections: number
  /** Detections a second within shots (the gaps between detections over their time; 0 before two). */
  rate: number
  /** The fewest detections a second in a judged window (null: none judged yet). */
  minWindow: number | null
  /** The need, a second, over the frames so far (within shots; 0 before two frames). */
  need: number
  /** Windows judged, and those under their need; where the first of those starts (s), its detections a second and its need a second. */
  windows: number
  short: number
  shortAt: number | null
  shortRate: number | null
  shortNeed: number | null
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
  /** The need accumulated in this shot (detections), and that of the last frame interval. */
  private e = 0
  private eStep = 0
  /** The shot's first frame's index; the last detection's index (-1: none yet in this shot), time and need so far. */
  private shotStart = 0
  private shotStartTs = 0
  private lastIdx = -1
  private lastTs = 0
  private lastE = 0
  /** An interval longer than a period at the rate since the last detection. */
  private hole = false
  /** Detections of this shot from the oldest window not yet judged: time, need so far, need of the frame interval before it. */
  private wt: number[] = []
  private we: number[] = []
  private ws: number[] = []
  private gaps = 0
  private gapTime = 0
  private needSum = 0
  private needTime = 0
  private c: Omit<Coverage, 'frames' | 'detections' | 'rate' | 'need'> = { minWindow: null, windows: 0, short: 0, shortAt: null, shortRate: null, shortNeed: null, maxGapFrames: 0, maxGapS: 0, cuts: 0, cutsMissed: 0 }

  constructor(o: CoverageOptions) {
    this.o = o
  }

  /** The need of a frame interval [dt] (s), in detections. */
  private needOf(dt: number) {
    return dt > 0 ? dt * this.o.needAt(1 / dt) : 0
  }

  /** A window from [t] (s) of [span] s with [n] detections, under its [need]. */
  private shortFrom(t: number, n: number, span: number, need: number) {
    const c = this.c
    c.short++
    if (c.shortAt === null) {
      c.shortAt = t
      c.shortRate = n / span
      c.shortNeed = need / span
    }
  }

  /**
   * The next frame: its time (s), whether it is a cut (the first frame of a new shot), whether it
   * got a detection, and the rate detection aimed at then (a second; 0: not given, no interval
   * counts as a hole).
   */
  frame(ts: number, cut: boolean, detected: boolean, rate = 0) {
    const i = this.frames++
    this.eStep = 0
    if (cut && i > 0) {
      this.endShot(i)
      this.shotStartTs = ts
      this.c.cuts++
      if (!detected) this.c.cutsMissed++
    } else if (i === 0) this.shotStartTs = ts
    else {
      this.eStep = this.needOf(ts - this.prevTs)
      this.e += this.eStep
      // An interval longer than a period at the rate (a hole in the frames): between the detections around it, one is all a schedule at that rate can take.
      if (ts - this.prevTs > 1 / rate) this.hole = true
      this.needSum += this.eStep
      this.needTime += Math.max(0, ts - this.prevTs)
    }
    this.prevTs = ts
    if (!detected) return
    this.dets++
    // A hole since the last detection: this one is the need of the stretch, at most (see frame()).
    if (this.hole && this.lastIdx >= 0 && this.e - this.lastE > 1) {
      this.needSum -= this.e - this.lastE - 1
      this.e = this.lastE + 1
      this.eStep = Math.min(this.eStep, 1)
    }
    this.hole = false
    if (this.lastIdx < 0) {
      // A shot's first detection: the frames before it are a gap too.
      this.c.maxGapFrames = Math.max(this.c.maxGapFrames, i - this.shotStart + (i > this.shotStart ? 1 : 0))
    } else {
      this.gaps++
      this.gapTime += ts - this.lastTs
      this.c.maxGapFrames = Math.max(this.c.maxGapFrames, i - this.lastIdx)
      this.c.maxGapS = Math.max(this.c.maxGapS, ts - this.lastTs)
      // A gap over a window: no window holds two detections, it is judged by itself.
      if (ts - this.lastTs > WINDOW_S + 1e-6) this.judgeGap(this.lastTs, ts - this.lastTs, this.e - this.lastE - this.eStep)
    }
    this.lastIdx = i
    this.lastTs = ts
    this.lastE = this.e
    this.wt.push(ts)
    this.we.push(this.e)
    this.ws.push(this.eStep)
    // The windows from the oldest detections end at the one before this (2 s from their start).
    let j = 0
    while (j < this.wt.length - 1 && ts - this.wt[j] > WINDOW_S + 1e-6) {
      this.judge(j, this.wt.length - 2)
      j++
    }
    if (j) {
      this.wt.splice(0, j)
      this.we.splice(0, j)
      this.ws.splice(0, j)
    }
  }

  /** A stretch of [span] s with no detection from [from] (s) that needed [need] detections (less a frame interval's): short if that is one or more. */
  private judgeGap(from: number, span: number, need: number) {
    this.c.windows++
    this.c.minWindow = Math.min(this.c.minWindow ?? Infinity, 1 / span)
    if (need >= 1) this.shortFrom(from, 0, span, need)
  }

  private judge(j: number, k: number) {
    // The window's end: the last detection within 2 s of its start.
    while (k > j && this.wt[k] - this.wt[j] > WINDOW_S + 1e-6) k--
    const span = this.wt[k] - this.wt[j]
    if (k <= j || span < MIN_WINDOW_S - 1e-6) return
    const n = k - j, need = this.we[k] - this.we[j] - this.ws[k]
    const c = this.c
    c.windows++
    c.minWindow = Math.min(c.minWindow ?? Infinity, n / span)
    if (n < need - 1e-9) this.shortFrom(this.wt[j], n, span, need)
  }

  /** The shot that ends before frame [end]: its last windows, the gap to its end. */
  private endShot(end: number) {
    for (let j = 0; j < this.wt.length - 1; j++) this.judge(j, this.wt.length - 1)
    if (this.lastIdx >= 0) {
      this.c.maxGapFrames = Math.max(this.c.maxGapFrames, end - this.lastIdx)
      // Over 2 s from the last detection to the shot's end.
      if (this.prevTs - this.lastTs > WINDOW_S + 1e-6) this.judgeGap(this.lastTs, this.prevTs - this.lastTs, this.e - this.lastE)
    } else if (end > this.shotStart) {
      this.c.maxGapFrames = Math.max(this.c.maxGapFrames, end - this.shotStart + 1)
      // A shot with no detection at all.
      if (this.prevTs - this.shotStartTs > WINDOW_S + 1e-6) this.judgeGap(this.shotStartTs, this.prevTs - this.shotStartTs, this.e)
    }
    this.wt = []
    this.we = []
    this.ws = []
    this.e = 0
    this.hole = false
    this.lastIdx = -1
    this.shotStart = end
  }

  /** The measures so far; with [final], the last shot is closed (its last windows judged). Feed no frames after a final result. */
  result(final = false): Coverage {
    if (final && this.frames > 0) {
      this.eStep = 0
      this.endShot(this.frames)
    }
    return { frames: this.frames, detections: this.dets, rate: this.gapTime > 0 ? this.gaps / this.gapTime : 0, need: this.needTime > 0 ? this.needSum / this.needTime : 0, ...this.c }
  }
}

/** The measures of a whole analysis: the frames' times (s), the detected frames' indices, the cuts' frame indices, the rate detection aimed at on each frame. */
export function measureCoverage(times: ArrayLike<number>, detected: Iterable<number>, cuts: Iterable<number>, o: CoverageOptions, rateAt: (i: number) => number = () => 0): Coverage {
  const d = new Set(detected), c = new Set(cuts)
  const m = new CoverageMeter(o)
  for (let i = 0; i < times.length; i++) m.frame(times[i], c.has(i), d.has(i), rateAt(i))
  return m.result(true)
}
