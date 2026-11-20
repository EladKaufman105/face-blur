package com.elad.faceblur.pipeline

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cuts a [cropW] x [cropH] window (fractions of the frame) out of each frame, centered where
 * [x], [y] say for the analysis frame at that time (see Reframer): a vertical or square video
 * that follows the faces.
 */
class ReframeEffect(
    private val times: LongArray,
    private val x: FloatArray,
    private val y: FloatArray,
    private val cropW: Float,
    private val cropH: Float,
) : MatrixTransformation {
    private val matrix = Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(even(inputWidth * cropW), even(inputHeight * cropH))

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val i = nearest(presentationTimeUs)
        // Normalized device coordinates: x to the right, y up, -1..1.
        val cx = if (i >= 0) 2f * x[i] - 1f else 0f
        val cy = if (i >= 0) 1f - 2f * y[i] else 0f
        matrix.reset()
        matrix.postTranslate(-cx, -cy)
        matrix.postScale(1f / cropW, 1f / cropH)
        return matrix
    }

    private fun nearest(t: Long): Int {
        if (times.isEmpty() || x.isEmpty()) return -1
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        val i = if (abs(times[hi] - t) < abs(times[lo] - t)) hi else lo
        return i.coerceAtMost(x.size - 1)
    }

    private fun even(v: Float) = max(2, (v / 2f).roundToInt() * 2)
}
