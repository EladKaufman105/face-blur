package com.elad.faceblur.analysis

/**
 * The analysis pass's counters as they are at one moment (see [FaceAnalyzer]): totals since the
 * pass started, and how detection is set up right now. Read with each preview; [StatsHistory]
 * turns a run of these into current values and the last minute.
 */
data class StatsSnapshot(
    /** When it was read (SystemClock.elapsedRealtime). */
    val atMs: Long,
    val frames: Int,
    /** Time on the video's thread, all frames, without the waits. */
    val frameMs: Long,
    /** Time frames waited for a free detector, all frames. */
    val waitMs: Long,
    val detections: Int,
    /** Time of the detections (one worker each), summed. */
    val detectMs: Long,
    // The parts of the detections, summed, in microseconds.
    val fineUs: Long,
    val coarseUs: Long,
    val gpuCallUs: Long,
    val yunetUs: Long,
    val cropUs: Long,
    val crops: Int,
    /** "gpu", "gpu-checking" (still compared with the CPU) or "cpu". */
    val accelerator: String,
    /** On the GPU: "fp16" or "fp32"; on the CPU: its precision ("fp16", "fp16-checking", "fp32"). */
    val precision: String,
    /** On the GPU: its time per frame and the wait for its lane, in ms (0 before the first). */
    val gpuMs: Float,
    val gpuQueueMs: Float,
    /** On the CPU: why not the GPU (may be empty). */
    val noGpu: String?,
    /** The fine pass's long side, in px. */
    val side: Int,
    /** Detection on every [every]th frame; [lite]: the lighter checks. */
    val every: Int,
    val lite: Boolean,
    val workers: Int,
    val maxWorkers: Int,
) {
    val onGpu get() = accelerator != "cpu"

    /** One line with where detection ran and how it went, to compare runs ("gpu fp16 · every 1 · 640 frames · 28 ms a detection"). */
    fun summary(): String = buildString {
        append(accelerator).append(' ').append(precision)
        append(" · every ").append(every).append(if (lite) ", lite" else "")
        append(" · ").append(side).append(" px · ").append(frames).append(" frames")
        if (detections > 0) append(" · ").append(detectMs / detections).append(" ms a detection")
        if (frameMs + waitMs > 0) append(" · waiting ").append(100 * waitMs / (frameMs + waitMs)).append("%")
        append(" · ").append(workers).append('/').append(maxWorkers).append(" workers")
        if (noGpu != null && noGpu.isNotEmpty() && !onGpu) append(" · no GPU: ").append(noGpu)
    }
}

/** One metric's last samples (about a second apart, so about the last minute), oldest first. */
class Series(val values: FloatArray) {
    val avg: Float get() = if (values.isEmpty()) 0f else values.average().toFloat()
    val min: Float get() = values.minOrNull() ?: 0f
    val max: Float get() = values.maxOrNull() ?: 0f
}

/**
 * What the stats panel shows. The values are current ones (over the last second or so, see
 * [StatsHistory]), not averages over the whole pass; the series hold the last minute.
 */
class AnalysisStats(
    val latest: StatsSnapshot,
    /** ms per detection, ms a frame waited, ms per frame. */
    val detectMs: Float,
    val waitMs: Float,
    val frameMs: Float,
    /** Per detection, in ms: the fine and coarse passes, the calls to the GPU, YuNet. */
    val fineMs: Float,
    val coarseMs: Float,
    val gpuCallMs: Float,
    val yunetMs: Float,
    /** Crop checks per detection, and ms each. */
    val cropsPerDetection: Float,
    val cropMs: Float,
    val detectHistory: Series,
    val waitHistory: Series,
    val frameHistory: Series,
    val sideHistory: Series,
    val everyHistory: Series,
)

/**
 * Turns the snapshots of one analysis run into [AnalysisStats]: about once a second, each value
 * is what finished in that second (the time added over the count added), or the value before
 * when nothing finished; the last [keep] of them are kept. Not thread safe: fed from one thread.
 */
class StatsHistory(private val keep: Int = 60, private val stepMs: Long = 1000L) {
    // The snapshot the current second started from.
    private var base: StatsSnapshot? = null

    private var detect = 0f
    private var wait = 0f
    private var frame = 0f
    private var fine = 0f
    private var coarse = 0f
    private var gpuCall = 0f
    private var yunet = 0f
    private var crops = 0f
    private var cropMs = 0f

    private val detectH = ArrayDeque<Float>()
    private val waitH = ArrayDeque<Float>()
    private val frameH = ArrayDeque<Float>()
    private val sideH = ArrayDeque<Float>()
    private val everyH = ArrayDeque<Float>()

    fun add(s: StatsSnapshot): AnalysisStats {
        val b = base
        if (b == null || restarted(b, s)) {
            // The first snapshot, or the pass started over (another try at the video): from here on.
            base = s
            if (detectH.isEmpty()) rates(zero(s), s)
        } else if (s.atMs - b.atMs < stepMs) {
            // Until a whole second is in: the averages since the start.
            if (detectH.isEmpty()) rates(zero(s), s)
        } else {
            rates(b, s)
            push(detectH, detect)
            push(waitH, wait)
            push(frameH, frame)
            push(sideH, s.side.toFloat())
            push(everyH, s.every.toFloat())
            base = s
        }
        return AnalysisStats(
            latest = s,
            detectMs = detect,
            waitMs = wait,
            frameMs = frame,
            fineMs = fine,
            coarseMs = coarse,
            gpuCallMs = gpuCall,
            yunetMs = yunet,
            cropsPerDetection = crops,
            cropMs = cropMs,
            detectHistory = Series(detectH.toFloatArray()),
            waitHistory = Series(waitH.toFloatArray()),
            frameHistory = Series(frameH.toFloatArray()),
            sideHistory = Series(sideH.toFloatArray()),
            everyHistory = Series(everyH.toFloatArray()),
        )
    }

    /** Any total went down: the analyzer was reset. */
    private fun restarted(b: StatsSnapshot, s: StatsSnapshot) =
        s.frames < b.frames || s.frameMs < b.frameMs || s.waitMs < b.waitMs ||
            s.detections < b.detections || s.detectMs < b.detectMs || s.fineUs < b.fineUs ||
            s.coarseUs < b.coarseUs || s.gpuCallUs < b.gpuCallUs || s.yunetUs < b.yunetUs ||
            s.cropUs < b.cropUs || s.crops < b.crops

    /**
     * The values over [b] .. [s]; each one stays as it was when nothing it counts finished (but
     * detections without crop checks make the crop time 0).
     */
    private fun rates(b: StatsSnapshot, s: StatsSnapshot) {
        val d = s.detections - b.detections
        if (d > 0) {
            detect = ((s.detectMs - b.detectMs).toFloat() / d).coerceAtLeast(0f)
            fine = ((s.fineUs - b.fineUs) / 1000f / d).coerceAtLeast(0f)
            coarse = ((s.coarseUs - b.coarseUs) / 1000f / d).coerceAtLeast(0f)
            gpuCall = ((s.gpuCallUs - b.gpuCallUs) / 1000f / d).coerceAtLeast(0f)
            yunet = ((s.yunetUs - b.yunetUs) / 1000f / d).coerceAtLeast(0f)
            val c = s.crops - b.crops
            crops = (c.toFloat() / d).coerceAtLeast(0f)
            cropMs = if (c > 0) ((s.cropUs - b.cropUs) / 1000f / c).coerceAtLeast(0f) else 0f
        }
        val f = s.frames - b.frames
        if (f > 0) {
            frame = ((s.frameMs - b.frameMs).toFloat() / f).coerceAtLeast(0f)
            wait = ((s.waitMs - b.waitMs).toFloat() / f).coerceAtLeast(0f)
        }
    }

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > keep) q.removeFirst()
    }

    private fun zero(s: StatsSnapshot) = s.copy(
        frames = 0, frameMs = 0, waitMs = 0, detections = 0, detectMs = 0,
        fineUs = 0, coarseUs = 0, gpuCallUs = 0, yunetUs = 0, cropUs = 0, crops = 0,
    )
}
