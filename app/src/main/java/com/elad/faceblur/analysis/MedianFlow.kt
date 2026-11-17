package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.video.Video
import kotlin.math.hypot
import kotlin.math.max

/** Box in pixels of the tracking frame. */
class PxBox(val cx: Float, val cy: Float, val w: Float, val h: Float)

/**
 * Median-flow tracker step (Kalal et al.): a grid of points inside the face is followed with
 * pyramidal Lucas-Kanade forwards and backwards; points whose round trip doesn't return home
 * are discarded, and the box moves/scales by the median of the rest.
 */
object MedianFlow {

    private const val GRID = 10

    fun track(prev: Mat, cur: Mat, box: PxBox): PxBox? {
        val width = prev.cols()
        val height = prev.rows()
        if (box.w < 6f || box.h < 6f) return null

        val pts = ArrayList<Point>(GRID * GRID)
        for (j in 0 until GRID) for (i in 0 until GRID) {
            val x = box.cx - box.w * 0.4f + box.w * 0.8f * i / (GRID - 1)
            val y = box.cy - box.h * 0.4f + box.h * 0.8f * j / (GRID - 1)
            if (x >= 0 && x < width && y >= 0 && y < height) pts += Point(x.toDouble(), y.toDouble())
        }
        if (pts.size < 10) return null

        val win = (box.w * 0.35f).toInt().coerceIn(9, 31) or 1
        val winSize = Size(win.toDouble(), win.toDouble())
        val p0 = MatOfPoint2f(*pts.toTypedArray())
        val p1 = MatOfPoint2f()
        val pb = MatOfPoint2f()
        val st1 = MatOfByte()
        val st2 = MatOfByte()
        val e1 = MatOfFloat()
        val e2 = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(prev, cur, p0, p1, st1, e1, winSize, 3)
            Video.calcOpticalFlowPyrLK(cur, prev, p1, pb, st2, e2, winSize, 3)
            val a = pts
            val b = p1.toArray()
            val back = pb.toArray()
            val s1 = st1.toArray()
            val s2 = st2.toArray()
            val n = a.size
            if (b.size != n || back.size != n) return null

            val fb = FloatArray(n)
            val ok = BooleanArray(n)
            var okCount = 0
            for (k in 0 until n) {
                ok[k] = s1[k].toInt() == 1 && s2[k].toInt() == 1
                fb[k] = hypot(a[k].x - back[k].x, a[k].y - back[k].y).toFloat()
                if (ok[k]) okCount++
            }
            if (okCount < 8) return null
            val medFb = median(FloatArray(okCount).also { arr ->
                var c = 0
                for (k in 0 until n) if (ok[k]) arr[c++] = fb[k]
            })
            val fbLimit = max(2f, box.w * 0.05f)
            val good = ArrayList<Int>()
            for (k in 0 until n) if (ok[k] && fb[k] <= max(medFb, 0.5f) && fb[k] < fbLimit) good += k
            if (good.size < 6) return null

            val dxs = FloatArray(good.size) { (b[good[it]].x - a[good[it]].x).toFloat() }
            val dys = FloatArray(good.size) { (b[good[it]].y - a[good[it]].y).toFloat() }
            val dx = median(dxs.copyOf())
            val dy = median(dys.copyOf())

            val ratios = ArrayList<Float>(good.size * good.size / 2)
            for (i in good.indices) for (j in i + 1 until good.size) {
                val gi = good[i]
                val gj = good[j]
                val d0 = hypot(a[gi].x - a[gj].x, a[gi].y - a[gj].y)
                if (d0 < 1e-3) continue
                val d1 = hypot(b[gi].x - b[gj].x, b[gi].y - b[gj].y)
                ratios += (d1 / d0).toFloat()
            }
            val scale = (if (ratios.isEmpty()) 1f else median(ratios.toFloatArray())).coerceIn(0.87f, 1.15f)

            val resid = median(FloatArray(good.size) { hypot(dxs[it] - dx, dys[it] - dy) })
            if (resid > box.w * 0.15f) return null

            return PxBox(box.cx + dx, box.cy + dy, box.w * scale, box.h * scale)
        } finally {
            p0.release(); p1.release(); pb.release()
            st1.release(); st2.release(); e1.release(); e2.release()
        }
    }

    private fun median(v: FloatArray): Float {
        v.sort()
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2f
    }
}
