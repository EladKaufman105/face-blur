package com.elad.faceblur.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** Frame shape of the export: as shot, or cut to a vertical / square frame that follows the faces. */
enum class ExportAspect(val ratio: Float) {
    ORIGINAL(0f),
    VERTICAL(9f / 16f),
    SQUARE(1f),
    PORTRAIT(4f / 5f),
}

/**
 * A virtual camera operator for vertical / square exports: the crop window follows the main
 * face (the biggest, kept while it's still comparably big), stays still while it's near the
 * middle, pans smoothly when it leaves, and jumps only at hard cuts.
 */
object Reframer {
    /** Crop size as fractions of the frame (width, height) for [ratio] (w/h) on a [frameW] x [frameH] frame. */
    fun cropFraction(frameW: Int, frameH: Int, ratio: Float): Pair<Float, Float> {
        if (ratio <= 0f || frameW <= 0 || frameH <= 0) return 1f to 1f
        val frame = frameW.toFloat() / frameH
        return if (frame > ratio) (ratio / frame) to 1f else 1f to (frame / ratio)
    }

    /**
     * Crop centers per analysis frame (normalized, top-left origin).
     *
     * @param faces per frame: (center x, center y, area) of each face, or empty.
     * @param ids per frame: a stable id for each face (to keep following the same one).
     * @param shotStarts first frame of each shot (0 first).
     */
    fun path(
        faces: List<List<FloatArray>>,
        ids: List<List<Int>>,
        shotStarts: IntArray,
        fps: Float,
        cropW: Float,
        cropH: Float,
    ): Pair<FloatArray, FloatArray> {
        val n = faces.size
        val tx = FloatArray(n) { Float.NaN }
        val ty = FloatArray(n) { Float.NaN }
        var followed = -1
        for (i in 0 until n) {
            val fs = faces[i]
            if (fs.isEmpty()) continue
            val biggest = fs.indices.maxBy { fs[it][2] }
            // Keep the face being followed while it's at least 60% as big as the biggest.
            val kept = ids[i].indexOf(followed).takeIf { it >= 0 && fs[it][2] >= 0.6f * fs[biggest][2] }
            val k = kept ?: biggest
            followed = ids[i][k]
            tx[i] = fs[k][0]
            ty[i] = fs[k][1]
        }
        val starts = (shotStarts.filter { it in 1 until n } + 0).distinct().sorted() + n
        val x = FloatArray(n)
        val y = FloatArray(n)
        for (s in 0 until starts.size - 1) {
            val a = starts[s]
            val b = starts[s + 1]
            follow(tx, a, b, fps, cropW, x)
            follow(ty, a, b, fps, cropH, y)
        }
        return x to y
    }

    /** One axis of one shot [a, b): targets [t] (NaN = none) -> crop centers into [out]. */
    private fun follow(t: FloatArray, a: Int, b: Int, fps: Float, crop: Float, out: FloatArray) {
        if (crop >= 0.999f) {
            for (i in a until b) out[i] = 0.5f
            return
        }
        val lo = crop / 2
        val hi = 1f - crop / 2
        // No face: hold the last one (or the first one of the shot); none at all: the middle.
        val first = (a until b).firstOrNull { !t[it].isNaN() }
        if (first == null) {
            for (i in a until b) out[i] = 0.5f.coerceIn(lo, hi)
            return
        }
        val target = FloatArray(b - a)
        var last = t[first]
        for (i in a until b) {
            if (!t[i].isNaN()) last = t[i]
            target[i - a] = last
        }
        // Dead zone in the middle of the window; outside it the window pans at a limited speed.
        val dead = crop * 0.18f
        val maxStep = crop * 1.4f / max(1f, fps)
        var pos = target[0].coerceIn(lo, hi)
        val raw = FloatArray(b - a)
        for (i in target.indices) {
            val d = target[i] - pos
            if (abs(d) > dead) pos += sign(d) * min(abs(d) - dead, maxStep)
            pos = pos.coerceIn(lo, hi)
            raw[i] = pos
        }
        // Smooth the starts and stops (centered average over ~0.4 s).
        val r = max(1, (fps * 0.2f).roundToInt())
        for (i in raw.indices) {
            var s = 0f
            var c = 0
            for (j in max(0, i - r)..min(raw.size - 1, i + r)) {
                s += raw[j]
                c++
            }
            out[a + i] = (s / c).coerceIn(lo, hi)
        }
    }
}
