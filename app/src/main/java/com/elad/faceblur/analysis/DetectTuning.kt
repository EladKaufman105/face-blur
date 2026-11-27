package com.elad.faceblur.analysis

import java.util.TreeMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How faces are looked for:
 *  - [NORMAL] (the default): about 15 detections a second of video.
 *  - [EXTENDED] ("sensitive"): every frame, and the tracker takes weaker evidence.
 *  - [FAST]: the same detector at 5 detections a second (never fewer), for long videos. It may
 *    briefly miss small or distant faces, or faces at the frame's edge; the app says so.
 *  - [SELFIE]: a talking head. MediaPipe's BlazeFace on every frame ([BlazeFace]), no other
 *    detector. Very fast, and finds one face close to the camera very well; it misses most faces
 *    under about 48 px, so small or distant faces in the background may stay unblurred; the app says so.
 */
enum class DetectMode {
    NORMAL, EXTENDED, FAST, SELFIE;

    companion object {
        /** Extended wins over fast and selfie: a choice never gets weaker than one asked for. */
        fun of(sensitive: Boolean, fast: Boolean, selfie: Boolean = false): DetectMode = when {
            sensitive -> EXTENDED
            fast -> FAST
            selfie -> SELFIE
            else -> NORMAL
        }
    }
}

/**
 * The score thresholds the tracker judges detections by (see [OfflineTracker]).
 *
 * @param cut a detection under this is no evidence of a face (DET_THRESHOLD).
 * @param birth a detection that starts a new tracklet needs this much.
 * @param strong a detection this sure is strong evidence.
 * @param verified the crop re-check's score that counts as evidence.
 * @param shortVerified the crop re-check's score a very short tracklet needs (twice).
 */
data class Thresholds(
    val cut: Float,
    val birth: Float,
    val strong: Float,
    val verified: Float,
    val shortVerified: Float,
)

/** How detection runs now on a device (see [DetectTuning.adapt]): every [every]-th frame, the CPU's fine pass at [cpuSide] px, [lite] checks. */
data class Pacing(val every: Int, val cpuSide: Int, val lite: Boolean)

/** What a step of [DetectTuning.adapt] did. */
enum class PaceStep { NONE, GPU_OFF, SIDE, RATE, LITE, RELAX }

/**
 * The detection modes' pacing and the sensitivity setting, as plain values (no Android, no
 * OpenCV), so they can be tested on the JVM.
 *
 * Sensitivity is one number from 0 to 1. 0 is the default detection, exactly as without the
 * setting (no extra work at all). Above 0, every threshold moves, linearly, toward more
 * detections, never above its default (the privacy floor), down to [MOST] at 1:
 *
 * | threshold                           | 0 (default) | 1 (most) |
 * |-------------------------------------|-------------|----------|
 * | detection cut (fused score)         | 0.40        | 0.25     |
 * | tracklet birth                      | 0.45        | 0.30     |
 * | strong evidence                     | 0.60        | 0.45     |
 * | crop re-check evidence              | 0.50        | 0.30     |
 * | crop re-check, very short tracklet  | 0.90        | 0.70     |
 *
 * The detectors' own thresholds don't move with it: above 0, their candidates are taken once
 * from a fixed, lower pool ([POOL_RF], [POOL_YUNET], [POOL_YUNET_ONLY]) beside the default ones,
 * and cut at [Thresholds.cut]. So the extra detections at a higher sensitivity always include
 * those at a lower one. The default detections and the default tracks are made exactly as
 * without the setting and kept whole ([extras]); the tracks of each 5% step up to the setting
 * are added to them in turn, never changing or taking away a box ([widen], [combine]): a
 * higher sensitivity never blurs less than a lower one, nor than the default.
 * [FaceDetector.CLEAR] (0.8) doesn't move: what counts as a clear face (no re-check needed,
 * no "check the faces" flag) stays as it is.
 */
object DetectTuning {
    val DEFAULT = Thresholds(cut = 0.40f, birth = 0.45f, strong = 0.60f, verified = 0.50f, shortVerified = 0.90f)
    val MOST = Thresholds(cut = 0.25f, birth = 0.30f, strong = 0.45f, verified = 0.30f, shortVerified = 0.70f)

    /** The candidate pool above sensitivity 0: RetinaFace's, YuNet's score threshold, and a face YuNet alone finds. */
    const val POOL_RF = 0.20f
    const val POOL_YUNET = 0.25f
    const val POOL_YUNET_ONLY = 0.45f

    /** Selfie mode's BlazeFace score threshold at sensitivity 0 (MediaPipe's own), and at 1. */
    const val SELFIE_THRESHOLD = 0.5f
    const val SELFIE_MOST = 0.3f

    /** Selfie mode above sensitivity 0: BlazeFace's candidates are taken once from this fixed, lower pool (its threshold at 1), beside the default ones. */
    const val SELFIE_POOL = SELFIE_MOST

    /** Extra detections a frame gets the crop re-check on (the most likely first). */
    const val MAX_EXTRA_CHECKS = 12

    /** An extra detection overlapping a default one this much (IoU) is that face: not extra. */
    const val SAME_FACE_IOU = 0.3f

    /** [s] within 0..1 (NaN: 0). */
    fun clamp(s: Float): Float = if (s.isNaN()) 0f else s.coerceIn(0f, 1f)

    /** The thresholds at sensitivity [s]: [DEFAULT] itself at 0, each one lower as [s] grows. */
    fun thresholds(s: Float): Thresholds {
        val k = clamp(s)
        if (k == 0f) return DEFAULT
        fun lerp(a: Float, b: Float) = a + (b - a) * k
        return Thresholds(
            cut = lerp(DEFAULT.cut, MOST.cut),
            birth = lerp(DEFAULT.birth, MOST.birth),
            strong = lerp(DEFAULT.strong, MOST.strong),
            verified = lerp(DEFAULT.verified, MOST.verified),
            shortVerified = lerp(DEFAULT.shortVerified, MOST.shortVerified),
        )
    }

    /** Selfie mode's BlazeFace threshold at sensitivity [s]: 0.5 at 0 (exactly), lower as [s] grows, 0.3 at 1. */
    fun selfieThreshold(s: Float): Float {
        val k = clamp(s)
        return if (k == 0f) SELFIE_THRESHOLD else SELFIE_THRESHOLD + (SELFIE_MOST - SELFIE_THRESHOLD) * k
    }

    /** Detections per second of video normal detection aims at, and the fewest a slow device goes down to. */
    const val RATE = 15f
    const val MIN_RATE = 8f
    /** Fast detection's rate: also its floor. */
    const val FAST_RATE = 5f

    /**
     * Which frames get a detection at [fps]: every `first`-th frame, and on a device that can't
     * keep up, every `second`-th at most. Normal: every fps / 15 frames (rounded), down to fps /
     * 8. Extended: every frame. Fast: never fewer than about 5 a second (within 2%: 29.97 fps
     * is every 6th frame, 4.995 a second; 24 fps every 4th, 6 a second), and the same at most.
     * Selfie: every frame (BlazeFace is light enough that no device needs fewer).
     */
    fun cadence(mode: DetectMode, fps: Float): Pair<Int, Int> = when (mode) {
        DetectMode.EXTENDED, DetectMode.SELFIE -> 1 to 1
        DetectMode.NORMAL -> {
            val base = max(1, (fps / RATE).roundToInt())
            base to max(base, (fps / MIN_RATE).toInt())
        }
        DetectMode.FAST -> {
            val every = max(1, (fps / (FAST_RATE * 0.98f)).toInt())
            every to every
        }
    }

    /**
     * One step of FaceAnalyzer.adapt (about every 1.5 s), as plain values. [blocked]: the frames
     * waited for a free detector over a third of the time (detection holds the analysis back);
     * [relaxed]: under a tenth. Both are measured without a sensitivity's extra work (see
     * [blockedWithoutExtras]): a sensitivity never moves the pacing. When blocked, the lightest
     * change first:
     *  1. a GPU too slow ([gpuSlow]) gives way to the CPU, at [lowSide] px, lite;
     *  2. on the CPU, the fine pass at [lowSide] px, lite;
     *  3. fewer detections (up to every [maxEvery]-th frame), lite;
     *  4. fast mode (its rate already the floor): lite.
     * When relaxed, back a step toward every [baseEvery]-th frame, and the full checks once there
     * (on a GPU, or with the CPU's fine pass back at [fineSide], the size detection started at:
     * 960 px, or 640 in fast mode). Fast mode, whose rate never moves, gets its full checks back
     * as soon as it keeps up easily.
     */
    fun adapt(
        p: Pacing,
        blocked: Boolean,
        relaxed: Boolean,
        gpuActive: Boolean,
        gpuSlow: Boolean,
        mode: DetectMode,
        baseEvery: Int,
        maxEvery: Int,
        lowSide: Int,
        fineSide: Int,
    ): Pair<Pacing, PaceStep> = when {
        blocked && gpuActive && gpuSlow -> p.copy(cpuSide = lowSide, lite = true) to PaceStep.GPU_OFF
        blocked && !gpuActive && p.cpuSide > lowSide -> p.copy(cpuSide = lowSide, lite = true) to PaceStep.SIDE
        blocked && p.every < maxEvery -> p.copy(every = p.every + 1, lite = true) to PaceStep.RATE
        blocked && mode == DetectMode.FAST && !p.lite -> p.copy(lite = true) to PaceStep.LITE
        blocked -> p to PaceStep.NONE
        relaxed && p.every > baseEvery -> {
            val every = p.every - 1
            p.copy(every = every, lite = if (every == baseEvery && (gpuActive || p.cpuSide == fineSide)) false else p.lite) to PaceStep.RELAX
        }
        relaxed && mode == DetectMode.FAST && p.lite && p.every == baseEvery && (gpuActive || p.cpuSide == fineSide) ->
            p.copy(lite = false) to PaceStep.RELAX
        else -> p to PaceStep.NONE
    }

    /**
     * The frames' wait for a free detector in a window ([blockedMs]), less the time the detectors
     * spent on a sensitivity's extra work in it ([extraMs]: the pool's decoding and fusion, YuNet's
     * whole run, the extra detections' crops), at 0 at least. Adding work to the detectors delays
     * the frames by at most that work's time, so the wait without it is at most the wait at
     * sensitivity 0 for the same detection: the extra work can't make the pacing lighter. (YuNet's
     * whole run counts, not just its extra candidates: more than its share, so a sensitivity can
     * only make the pacing hold on longer, and the analysis slower, never lighter.)
     */
    fun blockedWithoutExtras(blockedMs: Long, extraMs: Long): Long = max(0L, blockedMs - extraMs)

    /** The extra detections that get a crop re-check: the likeliest [MAX_EXTRA_CHECKS]. As the extras are nested and the new ones score lower, a higher sensitivity checks every one a lower one does. */
    fun extraChecks(extra: List<Det>): List<Det> = extra.sortedByDescending { it.score }.take(MAX_EXTRA_CHECKS)

    /**
     * RetinaFace's faces [rf] and YuNet's [yn] as one list (YuNet adds the faces RetinaFace
     * misses and raises the score where both agree), then NMS at 0.4: a YuNet face that no
     * RetinaFace face overlaps is added at 0.85 of its score when it is [yunetOnly] or more.
     */
    fun fuse(rf: List<Det>, yn: List<Det>, yunetOnly: Float = 0.6f): List<Det> {
        val fused = ArrayList<Det>()
        val usedYn = HashSet<Int>()
        for (d in rf) {
            var score = d.score
            yn.forEachIndexed { j, y ->
                if (Geometry.iou(d.box, y.box) > 0.3f) {
                    score = 1f - (1f - score) * (1f - y.score * 0.8f)
                    usedYn += j
                }
            }
            fused += Det(d.box, score)
        }
        yn.forEachIndexed { j, y ->
            if (j !in usedYn && y.score >= yunetOnly && rf.none { Geometry.iou(y.box, it.box) > 0.3f }) {
                fused += Det(y.box, y.score * 0.85f)
            }
        }
        return Geometry.nmsIou(fused, 0.4f)
    }

    /**
     * The extra detections at a sensitivity whose cut is [cut]: the pool's fused faces [pool]
     * that reach the cut and aren't a face the default detection has ([kept]). The pool doesn't
     * change with the sensitivity, so a lower cut keeps every face a higher one does.
     */
    fun extras(kept: List<Det>, pool: List<Det>, cut: Float): List<Det> =
        pool.filter { p -> p.score >= cut && kept.none { Geometry.iou(it.box, p.box) > SAME_FACE_IOU } }

    /**
     * Adds the tracks [more] (made with lower thresholds) to [tracks], never changing or taking
     * away a box that is there: a track that is mostly the face of one there (IoU over
     * [SAME_FACE_IOU] on at least half the frames they share) only adds its frames right before
     * and after it (a face found earlier or followed longer is blurred longer); any other is
     * another face. The result has every frame and box of [tracks].
     */
    fun combine(tracks: List<TreeMap<Int, Box>>, more: List<TreeMap<Int, Box>>): List<TreeMap<Int, Box>> {
        if (more.isEmpty()) return tracks
        val out = ArrayList<TreeMap<Int, Box>>(tracks.size + more.size)
        tracks.mapTo(out) { TreeMap(it) }
        for (m in more) {
            val t = out.firstOrNull { sameFace(it, m) }
            if (t == null) {
                out += TreeMap(m)
                continue
            }
            // Only the frames right before and after the track (contiguous with it).
            var f = t.firstKey() - 1
            while (f >= m.firstKey() && m.containsKey(f)) { t[f] = m[f]!!; f-- }
            f = t.lastKey() + 1
            while (f <= m.lastKey() && m.containsKey(f)) { t[f] = m[f]!!; f++ }
        }
        return out
    }

    /** [s] on the setting's steps of 5% (the slider's), within 0..1. */
    fun quantize(s: Float): Float = kotlin.math.round(clamp(s) * STEPS) / STEPS

    /** [s] up to the next 5% step (a template's value between steps never ends up less sensitive), within 0..1. */
    // ceil of a tiny negative is -0.0, which a data class's equals tells apart from 0: never return it.
    fun roundUp(s: Float): Float = (kotlin.math.ceil(clamp(s) * STEPS - 1e-3f) / STEPS).let { if (it <= 0f) 0f else it }

    /** The steps a sensitivity of [s] goes through: 5%, 10%, ... up to [s] (none at 0). */
    fun levels(s: Float): List<Float> = (1..kotlin.math.round(clamp(s) * STEPS).toInt()).map { it / STEPS }

    /**
     * The tracks at sensitivity [s]: the default tracks [base] as they are, then step by step
     * ([levels]) the tracks [more] makes at each step's thresholds ([combine]). Each step only
     * adds, so a higher sensitivity has every box a lower one has: it never blurs less.
     */
    fun widen(
        base: List<TreeMap<Int, Box>>,
        s: Float,
        cancelled: () -> Boolean = { false },
        more: (Float) -> List<TreeMap<Int, Box>>,
    ): List<TreeMap<Int, Box>> {
        var out = base
        for (level in levels(s)) {
            // A pass given up (cancelled, a new pass or close): stop between steps, never with a part
            // of the steps passed off as the whole.
            if (cancelled()) throw InterruptedException("sensitivity steps given up")
            out = combine(out, more(level))
        }
        return out
    }

    /** Steps of the setting. */
    private const val STEPS = 20f

    /**
     * Mostly the same face: IoU over [SAME_FACE_IOU] on at least half the frames both tracks have
     * (both are contiguous). Tracks apart in time are rejected at once, and the count stops as
     * soon as the answer is known.
     */
    private fun sameFace(a: TreeMap<Int, Box>, b: TreeMap<Int, Box>): Boolean {
        val from = max(a.firstKey(), b.firstKey())
        val to = kotlin.math.min(a.lastKey(), b.lastKey())
        if (from > to) return false
        val shared = to - from + 1
        var over = 0
        var seen = 0
        for (f in from..to) {
            val x = a[f]
            val y = b[f]
            seen++
            if (x != null && y != null && Geometry.iou(x, y) > SAME_FACE_IOU) over++
            if (over * 2 >= shared) return true
            if ((over + shared - seen) * 2 < shared) return false
        }
        return false
    }
}
