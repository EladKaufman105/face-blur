package com.elad.faceblur.track

import com.elad.faceblur.analysis.Box
import java.util.SortedMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * A face region to cover in one frame, in the pipeline frame's own orientation:
 * center normalized to width/height (top-left origin); half-axes as a fraction of the frame
 * WIDTH; roll angle in radians (image coordinates, y down); opacity 0..1 (fades at the ends).
 */
class FaceShape(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float, val alpha: Float)

private class TrackSeries(
    val t: LongArray,
    val cx: FloatArray,
    val cy: FloatArray,
    val rx: FloatArray,
    val ry: FloatArray,
    val angle: FloatArray,
)

/** Smoothed per-track face positions over time, looked up by presentation timestamp. */
class FaceTimeline private constructor(
    private val series: List<TrackSeries>,
    private val frameUs: Long,
    /** Presentation times of the first frame of each new shot (hard cuts). */
    private val cuts: LongArray,
) : FaceSource {
    val trackCount get() = series.size

    private val fadeUs = max(2 * frameUs, FADE_US)

    override fun facesAt(tUs: Long): List<FaceShape> {
        val out = ArrayList<FaceShape>()
        val tol = frameUs / 2
        for (s in series) {
            val n = s.t.size
            val first = s.t[0]
            val last = s.t[n - 1]
            if (tUs < first - fadeUs - tol || tUs > last + fadeUs + tol) continue
            when {
                tUs < first - tol -> if (!cutBetween(tUs, first)) {
                    out += shape(s, 0, 1f - (first - tUs).toFloat() / (fadeUs + frameUs))
                }
                tUs > last + tol -> if (!cutBetween(last, tUs)) {
                    out += shape(s, n - 1, 1f - (tUs - last).toFloat() / (fadeUs + frameUs))
                }
                tUs <= first -> out += shape(s, 0, 1f)
                tUs >= last -> out += shape(s, n - 1, 1f)
                else -> {
                    var lo = 0
                    var hi = n - 1
                    while (hi - lo > 1) {
                        val mid = (lo + hi) ushr 1
                        if (s.t[mid] <= tUs) lo = mid else hi = mid
                    }
                    when {
                        abs(s.t[lo] - tUs) <= tol -> out += shape(s, lo, 1f)
                        abs(s.t[hi] - tUs) <= tol -> out += shape(s, hi, 1f)
                        else -> {
                            val f = (tUs - s.t[lo]).toFloat() / (s.t[hi] - s.t[lo])
                            out += FaceShape(
                                lerp(s.cx[lo], s.cx[hi], f),
                                lerp(s.cy[lo], s.cy[hi], f),
                                lerp(s.rx[lo], s.rx[hi], f),
                                lerp(s.ry[lo], s.ry[hi], f),
                                lerp(s.angle[lo], s.angle[hi], f),
                                1f,
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    private fun shape(s: TrackSeries, i: Int, alpha: Float) =
        FaceShape(s.cx[i], s.cy[i], s.rx[i], s.ry[i], s.angle[i], alpha.coerceIn(0f, 1f))

    /** True if a shot starts in (a, b]: a track's fade must not spill into another shot. */
    private fun cutBetween(a: Long, b: Long): Boolean = cuts.any { it > a && it <= b }

    companion object {
        private const val FADE_US = 150_000L

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /**
         * @param tracks per track: analysis frame index -> box (already gap-filled and smoothed).
         * @param times presentation time of each analysis frame index.
         * @param cuts presentation times where a new shot starts.
         * @param uprightW / uprightH size of the (upright) analysis frames the boxes refer to.
         * @param rotation clockwise degrees that were applied to make analysis frames upright.
         */
        fun build(
            tracks: List<SortedMap<Int, Box>>,
            times: LongArray,
            cuts: LongArray,
            uprightW: Int,
            uprightH: Int,
            rotation: Int,
            padding: Float,
        ): FaceTimeline {
            val diffs = (1 until times.size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
            val frameUs = if (diffs.isEmpty()) 33_333L else diffs[diffs.size / 2]
            val pipelineW = if (rotation == 90 || rotation == 270) uprightH else uprightW
            val rad = (rotation * PI / 180.0).toFloat()

            val series = tracks.mapNotNull { tr ->
                val keys = tr.keys.filter { it < times.size }
                if (keys.isEmpty()) return@mapNotNull null
                val n = keys.size
                val ts = LongArray(n)
                val cx = FloatArray(n)
                val cy = FloatArray(n)
                val rx = FloatArray(n)
                val ry = FloatArray(n)
                val an = FloatArray(n)
                keys.forEachIndexed { i, k ->
                    val b = tr[k]!!
                    // Shift up a little to take in the forehead / hairline.
                    val (px, py) = unrotate(b.cx, b.cy - 0.06f * b.h, rotation)
                    ts[i] = times[k]
                    cx[i] = px
                    cy[i] = py
                    rx[i] = b.w * uprightW / 2f * padding / pipelineW
                    ry[i] = b.h * uprightH / 2f * padding * 1.18f / pipelineW
                    an[i] = b.angle - rad
                }
                TrackSeries(ts, cx, cy, rx, ry, an)
            }
            return FaceTimeline(series, frameUs, cuts)
        }

        /** Maps a normalized point from the upright frame back to the pipeline frame. */
        private fun unrotate(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }
    }
}
