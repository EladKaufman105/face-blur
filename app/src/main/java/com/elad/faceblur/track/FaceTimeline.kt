package com.elad.faceblur.track

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Kind
import com.elad.faceblur.analysis.Track
import com.elad.faceblur.analysis.TrackPoint
import java.util.TreeMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

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
) {
    val trackCount get() = series.size

    private val fadeUs = max(2 * frameUs, FADE_US)

    fun facesAt(tUs: Long): List<FaceShape> {
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
        private const val BRIDGE_SECONDS = 0.5f
        // Gaussian smoothing widths in seconds (offline, centered: no lag).
        private const val SIGMA_POS = 0.07f
        private const val SIGMA_SIZE = 0.2f
        private const val SIGMA_ANGLE = 0.25f

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /**
         * @param times presentation time of each analysis frame index.
         * @param cuts presentation times where a new shot starts.
         * @param uprightW / uprightH size of the (upright) analysis frames the boxes refer to.
         * @param rotation clockwise degrees that were applied to make analysis frames upright.
         */
        fun build(
            tracks: List<Track>,
            times: LongArray,
            cuts: LongArray,
            uprightW: Int,
            uprightH: Int,
            rotation: Int,
            padding: Float,
        ): FaceTimeline {
            val diffs = (1 until times.size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
            val frameUs = if (diffs.isEmpty()) 33_333L else diffs[diffs.size / 2]
            val fps = 1_000_000f / frameUs
            val pipelineW = if (rotation == 90 || rotation == 270) uprightH else uprightW
            val rad = (rotation * PI / 180.0).toFloat()

            val series = tracks.mapNotNull { tr ->
                val pts = smooth(bridge(tr.points, fps), fps)
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

        /** Fills holes of up to half a second by linear interpolation. */
        private fun bridge(src: TreeMap<Int, TrackPoint>, fps: Float): TreeMap<Int, TrackPoint> {
            val out = TreeMap(src)
            val maxGap = max(2, (fps * BRIDGE_SECONDS).roundToInt())
            val keys = src.keys.toList()
            for (j in 0 until keys.size - 1) {
                val a = keys[j]
                val c = keys[j + 1]
                if (c - a <= 1 || c - a > maxGap) continue
                val ba = src[a]!!.box
                val bc = src[c]!!.box
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

        /**
         * Centered Gaussian smoothing over time: positions lightly, size and angle more (they
         * should not breathe or wobble). Offline, so it adds no lag.
         */
        private fun smooth(src: TreeMap<Int, TrackPoint>, fps: Float): TreeMap<Int, TrackPoint> {
            val keys = src.keys.toIntArray()
            val n = keys.size
            val v = Array(5) { FloatArray(n) }
            for (i in 0 until n) {
                val b = src[keys[i]]!!.box
                v[0][i] = b.cx; v[1][i] = b.cy; v[2][i] = b.w; v[3][i] = b.h; v[4][i] = b.angle
            }
            val sig = floatArrayOf(SIGMA_POS, SIGMA_POS, SIGMA_SIZE, SIGMA_SIZE, SIGMA_ANGLE).map { max(0.5f, it * fps) }
            val out = TreeMap<Int, TrackPoint>()
            for (i in 0 until n) {
                val r = FloatArray(5)
                for (c in 0 until 5) {
                    val s = sig[c]
                    val reach = (3 * s).toInt() + 1
                    var acc = 0f
                    var tw = 0f
                    var j = i
                    while (j >= 0 && keys[i] - keys[j] <= reach) {
                        val d = (keys[i] - keys[j]) / s
                        val wt = exp(-0.5f * d * d)
                        acc += v[c][j] * wt; tw += wt; j--
                    }
                    j = i + 1
                    while (j < n && keys[j] - keys[i] <= reach) {
                        val d = (keys[j] - keys[i]) / s
                        val wt = exp(-0.5f * d * d)
                        acc += v[c][j] * wt; tw += wt; j++
                    }
                    r[c] = acc / tw
                }
                out[keys[i]] = TrackPoint(Box(r[0], r[1], r[2], r[3], r[4]), src[keys[i]]!!.kind)
            }
            return out
        }
    }
}
