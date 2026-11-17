package com.elad.faceblur.track

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Kind
import com.elad.faceblur.analysis.Track
import com.elad.faceblur.analysis.TrackPoint
import kotlin.math.PI
import kotlin.math.abs

/**
 * A face region to cover in one frame, in the pipeline frame's own orientation:
 * center normalized to width/height (top-left origin); half-axes as a fraction of the frame
 * WIDTH; roll angle in radians (image coordinates, y down).
 */
class FaceShape(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float)

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
) {
    val trackCount get() = series.size

    fun facesAt(tUs: Long): List<FaceShape> {
        val out = ArrayList<FaceShape>()
        val tol = frameUs / 2
        for (s in series) {
            val n = s.t.size
            val first = s.t[0]
            val last = s.t[n - 1]
            if (tUs < first - EDGE_FRAMES * frameUs - tol || tUs > last + EDGE_FRAMES * frameUs + tol) continue
            when {
                tUs <= first -> out += shape(s, 0)
                tUs >= last -> out += shape(s, n - 1)
                else -> {
                    var lo = 0
                    var hi = n - 1
                    while (hi - lo > 1) {
                        val mid = (lo + hi) ushr 1
                        if (s.t[mid] <= tUs) lo = mid else hi = mid
                    }
                    when {
                        abs(s.t[lo] - tUs) <= tol -> out += shape(s, lo)
                        abs(s.t[hi] - tUs) <= tol -> out += shape(s, hi)
                        s.t[hi] - s.t[lo] <= MAX_INTERP_FRAMES * frameUs -> {
                            val f = (tUs - s.t[lo]).toFloat() / (s.t[hi] - s.t[lo])
                            out += FaceShape(
                                lerp(s.cx[lo], s.cx[hi], f),
                                lerp(s.cy[lo], s.cy[hi], f),
                                lerp(s.rx[lo], s.rx[hi], f),
                                lerp(s.ry[lo], s.ry[hi], f),
                                lerp(s.angle[lo], s.angle[hi], f),
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    private fun shape(s: TrackSeries, i: Int) = FaceShape(s.cx[i], s.cy[i], s.rx[i], s.ry[i], s.angle[i])

    companion object {
        private const val EDGE_FRAMES = 3
        private const val MAX_INTERP_FRAMES = 6

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /**
         * @param times presentation time of each analysis frame index.
         * @param uprightW / uprightH size of the (upright) analysis frames the boxes refer to.
         * @param rotation clockwise degrees that were applied to make analysis frames upright.
         */
        fun build(
            tracks: List<Track>,
            times: LongArray,
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
                val pts = smooth(tr.points)
                if (pts.isEmpty()) return@mapNotNull null
                val keys = pts.keys.filter { it < times.size }
                if (keys.isEmpty()) return@mapNotNull null
                val n = keys.size
                val ts = LongArray(n)
                val cx = FloatArray(n)
                val cy = FloatArray(n)
                val rx = FloatArray(n)
                val ry = FloatArray(n)
                val an = FloatArray(n)
                keys.forEachIndexed { i, k ->
                    val b = pts[k]!!.box
                    // Shift up a little to take in the forehead / hairline.
                    val ux = b.cx
                    val uy = b.cy - 0.06f * b.h
                    val (px, py) = unrotate(ux, uy, rotation)
                    ts[i] = times[k]
                    cx[i] = px
                    cy[i] = py
                    rx[i] = b.w * uprightW / 2f * padding / pipelineW
                    ry[i] = b.h * uprightH / 2f * padding * 1.18f / pipelineW
                    an[i] = b.angle - rad
                }
                TrackSeries(ts, cx, cy, rx, ry, an)
            }
            return FaceTimeline(series, frameUs)
        }

        /** Maps a normalized point from the upright frame back to the pipeline frame. */
        private fun unrotate(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }

        /** Centered 5-tap smoothing (no lag, offline) + interpolation of short holes. */
        private fun smooth(src: java.util.TreeMap<Int, TrackPoint>): java.util.TreeMap<Int, TrackPoint> {
            val out = java.util.TreeMap<Int, TrackPoint>()
            if (src.isEmpty()) return out
            val weights = floatArrayOf(1f, 2f, 3f, 2f, 1f)
            for ((i, p) in src) {
                var cx = 0f; var cy = 0f; var w = 0f; var h = 0f; var a = 0f; var tw = 0f
                for (k in -2..2) {
                    val q = src[i + k] ?: continue
                    val wt = weights[k + 2]
                    cx += q.box.cx * wt; cy += q.box.cy * wt
                    w += q.box.w * wt; h += q.box.h * wt
                    a += q.box.angle * wt
                    tw += wt
                }
                out[i] = TrackPoint(Box(cx / tw, cy / tw, w / tw, h / tw, a / tw), p.kind)
            }
            val keys = out.keys.toList()
            for (j in 0 until keys.size - 1) {
                val a = keys[j]
                val c = keys[j + 1]
                if (c - a <= 1 || c - a > MAX_INTERP_FRAMES) continue
                val ba = out[a]!!.box
                val bc = out[c]!!.box
                for (i in a + 1 until c) {
                    val f = (i - a).toFloat() / (c - a)
                    out[i] = TrackPoint(
                        Box(
                            lerp(ba.cx, bc.cx, f), lerp(ba.cy, bc.cy, f),
                            lerp(ba.w, bc.w, f), lerp(ba.h, bc.h, f),
                            lerp(ba.angle, bc.angle, f),
                        ),
                        Kind.INTERPOLATED,
                    )
                }
            }
            return out
        }
    }
}
