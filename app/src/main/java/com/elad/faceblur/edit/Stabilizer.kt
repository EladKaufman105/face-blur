package com.elad.faceblur.edit

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation
import com.elad.faceblur.analysis.OfflineTracker
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Steadies a shaky video: the camera's path (measured frame to frame during the analysis) is
 * smoothed over about a second, and each frame is moved by the difference, then enlarged a
 * little so the moved edges stay out of view. Each shot (between cuts) on its own.
 *
 * [shiftX], [shiftY]: per frame, how far to move the picture (fractions of its width / height).
 */
class Stabilizer private constructor(
    val times: LongArray,
    val shiftX: FloatArray,
    val shiftY: FloatArray,
    /** The enlargement (1 = none). */
    val zoom: Float,
    /** Clockwise degrees the analysis turned the frames to make them upright. */
    private val rotation: Int,
) {
    /** The frame nearest [us] (source time), or -1. */
    fun frameAt(us: Long): Int {
        if (times.isEmpty()) return -1
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= us) lo = mid else hi = mid
        }
        return if (abs(times[hi] - us) < abs(times[lo] - us)) hi else lo
    }

    /** The move for the frame at [us], in normalized device coordinates (x right, y up). */
    fun matrix(us: Long, into: Matrix): Matrix {
        into.reset()
        val i = frameAt(us)
        if (i < 0) return into
        // The frames in the effects chain are as decoded (not yet turned upright).
        val u = shiftX[i]
        val v = shiftY[i]
        val (px, py) = when (rotation) {
            90 -> v to -u
            180 -> -u to -v
            270 -> -v to u
            else -> u to v
        }
        into.postTranslate(2f * px, -2f * py)
        into.postScale(zoom, zoom)
        return into
    }

    companion object {
        /** How long a stretch the path is smoothed over (Gaussian sigma, seconds). */
        private const val SIGMA_S = 0.5f
        /** The most a frame is moved (fraction of the picture); more shake than that stays. */
        private const val MAX_SHIFT = 0.07f

        /**
         * From the analysis's camera motion; null if there's none to steady (no motion was
         * measured, e.g. a video opened for subtitles only).
         */
        fun from(times: LongArray, motion: FloatArray, cuts: IntArray, aspect: Float, fps: Float, rotation: Int): Stabilizer? {
            val n = times.size
            if (n < 3 || motion.size < 2 * n) return null
            if (motion.all { it == 0f }) return null
            // Cumulative offsets per shot: x in widths, y in widths too (see cameraOffsets).
            val (ox, oy0) = OfflineTracker.cameraOffsets(n, motion, cuts.toList(), aspect)
            val oy = FloatArray(n) { if (aspect > 0f) oy0[it] / aspect else oy0[it] }
            val starts = (listOf(0) + cuts.filter { it in 1 until n }.sorted().distinct() + listOf(n))
            val sx = FloatArray(n)
            val sy = FloatArray(n)
            val sigma = max(1f, SIGMA_S * fps)
            val reach = (3 * sigma).toInt()
            var worst = 0f
            for (s in 0 until starts.size - 1) {
                val a = starts[s]
                val b = starts[s + 1]
                for (i in a until b) {
                    var accX = 0f
                    var accY = 0f
                    var w = 0f
                    for (j in max(a, i - reach) until min(b, i + reach + 1)) {
                        val d = (j - i) / sigma
                        val k = exp(-0.5f * d * d)
                        accX += ox[j] * k
                        accY += oy[j] * k
                        w += k
                    }
                    // The picture follows the smooth path instead of the shaky one.
                    sx[i] = (accX / w - ox[i]).coerceIn(-MAX_SHIFT, MAX_SHIFT)
                    sy[i] = (accY / w - oy[i]).coerceIn(-MAX_SHIFT, MAX_SHIFT)
                    worst = max(worst, max(abs(sx[i]), abs(sy[i])))
                }
            }
            if (worst < 0.002f) return null
            // Enough to hide the largest move (one zoom for the whole video: no "breathing").
            val zoom = 1f / (1f - 2f * worst)
            return Stabilizer(times, sx, sy, zoom, rotation)
        }
    }
}

/**
 * The stabilizer as a frame transform, for the player (always in its chain, so turning it on
 * and off needs no restart: [source] gives null when it's off) and for the export.
 */
class StabilizeEffect(private val source: () -> Stabilizer?) : MatrixTransformation {
    private val m = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val s = source() ?: return m.apply { reset() }
        return s.matrix(presentationTimeUs, m)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}
