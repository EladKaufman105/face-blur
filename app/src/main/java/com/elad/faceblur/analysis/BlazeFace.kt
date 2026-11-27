package com.elad.faceblur.analysis

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * MediaPipe's BlazeFace full-range face detector (Apache-2.0), the post-processing as MediaPipe's
 * own calculators do it (SsdAnchorsCalculator, TensorsToDetectionsCalculator,
 * NonMaxSuppressionCalculator with the weighted algorithm), as plain values (no Android, no
 * OpenCV) so they run in JVM unit tests. [BlazeFaceDetector] runs the model.
 *
 * The model takes the frame letterboxed into a 192 x 192 square (black bars, scaled to fit),
 * pixels as (v - 127.5) / 127.5, and gives for each of its 2304 anchors (one per cell of a 48 x 48
 * grid, stride 4) 16 numbers (x, y, width, height of the box relative to the anchor, in input
 * pixels, then 6 landmarks) and a score as a logit.
 */
object BlazeFace {
    /** The model's input side, in pixels. */
    const val SIZE = 192

    /** The anchors' grid: [GRID] x [GRID], one anchor per cell. */
    const val GRID = 48

    /** Anchors, and the numbers per anchor in the regressors' tensor (4 box, 6 landmarks x 2). */
    const val ANCHORS = GRID * GRID
    const val VALUES = 16

    /** MediaPipe's score threshold for this model's faces (min_score_thresh). */
    const val THRESHOLD = 0.5f

    /** Detections overlapping this much (IoU) are one face (min_suppression_threshold). */
    const val NMS_IOU = 0.3f

    /** Scores are clipped to +-100 as logits before the sigmoid (score_clipping_thresh). */
    private const val CLIP = 100f

    /** One detection in the model's square: [score], and the box's corners as fractions of the side. */
    class Cand(val score: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** Anchor centers (x, y as fractions of the square), row by row: anchor i is cell (i % GRID, i / GRID); every anchor's width and height is 1 (fixed_anchor_size). */
    val anchors: FloatArray = FloatArray(ANCHORS * 2).also {
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val i = y * GRID + x
            it[2 * i] = (x + 0.5f) / GRID
            it[2 * i + 1] = (y + 0.5f) / GRID
        }
    }

    fun sigmoid(logit: Float): Float = 1f / (1f + exp(-logit.coerceIn(-CLIP, CLIP)))

    /**
     * The anchors whose score reaches [threshold], decoded to boxes in the square. [regressors]
     * is [ANCHORS] x [VALUES] floats, [logits] [ANCHORS]. Not suppressed yet (see [weightedNms]).
     */
    fun decode(regressors: FloatArray, logits: FloatArray, threshold: Float): List<Cand> {
        require(regressors.size >= ANCHORS * VALUES && logits.size >= ANCHORS) { "unexpected output sizes" }
        val out = ArrayList<Cand>()
        for (i in 0 until ANCHORS) {
            val score = sigmoid(logits[i])
            if (score < threshold) continue
            val r = i * VALUES
            val xc = regressors[r] / SIZE + anchors[2 * i]
            val yc = regressors[r + 1] / SIZE + anchors[2 * i + 1]
            val w = regressors[r + 2] / SIZE
            val h = regressors[r + 3] / SIZE
            out += Cand(score, xc - w / 2, yc - h / 2, xc + w / 2, yc + h / 2)
        }
        return out
    }

    private fun overlap(a: Cand, b: Cand): Float {
        val iw = min(a.x1, b.x1) - max(a.x0, b.x0)
        val ih = min(a.y1, b.y1) - max(a.y0, b.y0)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
        return if (union > 0f) inter / union else 0f
    }

    /**
     * MediaPipe's weighted non-maximum suppression: from the highest score down, every detection
     * that overlaps it by more than [minOverlap] (IoU; itself included) joins its group, which becomes one
     * detection: the group's boxes averaged by score, with the highest score. Highest score first.
     */
    fun weightedNms(cands: List<Cand>, minOverlap: Float = NMS_IOU): List<Cand> {
        var remaining = cands.sortedByDescending { it.score }
        val out = ArrayList<Cand>()
        while (remaining.isNotEmpty()) {
            val top = remaining[0]
            val group = ArrayList<Cand>()
            val rest = ArrayList<Cand>()
            for (c in remaining) if (overlap(c, top) > minOverlap) group += c else rest += c
            if (group.isEmpty()) {
                // Can't happen (the top overlaps itself, unless its box is empty): drop it, as MediaPipe stops.
                break
            }
            var total = 0f
            var x0 = 0f
            var y0 = 0f
            var x1 = 0f
            var y1 = 0f
            for (c in group) {
                total += c.score
                x0 += c.x0 * c.score; y0 += c.y0 * c.score; x1 += c.x1 * c.score; y1 += c.y1 * c.score
            }
            out += Cand(top.score, x0 / total, y0 / total, x1 / total, y1 / total)
            remaining = rest
        }
        return out
    }

    /**
     * How a [w] x [h] frame sits in the square: scaled to fit and centered, so the bars are
     * [padX] (left and right) and [padY] (top and bottom), as fractions of the square.
     */
    class Letterbox(val w: Int, val h: Int) {
        val scale: Float = SIZE.toFloat() / max(w, h)
        val padX: Float = (SIZE - w * scale) / 2f / SIZE
        val padY: Float = (SIZE - h * scale) / 2f / SIZE

        /** A detection as a box in the frame (fractions of its width and height). */
        fun toDet(c: Cand): Det {
            val sx = 1f - 2f * padX
            val sy = 1f - 2f * padY
            val x0 = (c.x0 - padX) / sx
            val x1 = (c.x1 - padX) / sx
            val y0 = (c.y0 - padY) / sy
            val y1 = (c.y1 - padY) / sy
            // The tracker's own score for a crop re-check is the detector's score: BlazeFace runs alone here, with no second look.
            return Det(Box((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0), c.score, verify = c.score)
        }
    }

    /**
     * The faces in a frame: the default ones (score [THRESHOLD], exactly as at sensitivity 0), and
     * the extra ones a sensitivity above 0 adds. Faces from a lower, fixed pool ([DetectTuning.SELFIE_POOL])
     * that are not a face the default ones have ([DetectTuning.extras], by IoU in the frame). The
     * pool doesn't change with the sensitivity, so a higher sensitivity has every face a lower one has.
     */
    class Result(val faces: List<Det>, val extras: List<Det>)

    fun faces(regressors: FloatArray, logits: FloatArray, frameW: Int, frameH: Int, withExtras: Boolean): Result {
        val box = Letterbox(frameW, frameH)
        val faces = weightedNms(decode(regressors, logits, THRESHOLD)).map { box.toDet(it) }
        if (!withExtras) return Result(faces, emptyList())
        val pool = weightedNms(decode(regressors, logits, DetectTuning.SELFIE_POOL)).map { box.toDet(it) }
        return Result(faces, DetectTuning.extras(faces, pool, DetectTuning.SELFIE_POOL))
    }
}
