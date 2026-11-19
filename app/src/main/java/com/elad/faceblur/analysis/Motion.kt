package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Global camera translation between consecutive frames: median optical flow of a grid spread
 * over the whole frame, each point checked forward and backward. Faces are a small part of the
 * frame, so the median follows the background (the camera), not the people.
 */
class CameraMotion : Closeable {
    private var prev: Mat? = null
    private val grid = MatOfPoint2f()
    private val next = MatOfPoint2f()
    private val back = MatOfPoint2f()
    private val st1 = MatOfByte()
    private val st2 = MatOfByte()
    private val err = MatOfFloat()
    private var gridW = 0
    private var gridH = 0

    fun reset() {
        prev?.release()
        prev = null
    }

    /**
     * Takes ownership of [gray]. @return motion since the previous frame as (dx / width,
     * dy / height), or (0, 0) when it can't be measured.
     */
    fun step(gray: Mat): FloatArray {
        val p = prev
        prev = gray
        val w = gray.cols()
        val h = gray.rows()
        if (p == null || p.cols() != w || p.rows() != h) {
            p?.release()
            return FloatArray(2)
        }
        try {
            if (gridW != w || gridH != h) {
                val pts = ArrayList<Point>(GX * GY)
                for (j in 0 until GY) for (i in 0 until GX) {
                    pts += Point(w * (0.06 + 0.88 * i / (GX - 1)), h * (0.06 + 0.88 * j / (GY - 1)))
                }
                grid.fromList(pts)
                gridW = w
                gridH = h
            }
            val win = Size(21.0, 21.0)
            Video.calcOpticalFlowPyrLK(p, gray, grid, next, st1, err, win, 3)
            Video.calcOpticalFlowPyrLK(gray, p, next, back, st2, err, win, 3)
            val a = grid.toArray()
            val b = next.toArray()
            val c = back.toArray()
            val s1 = st1.toArray()
            val s2 = st2.toArray()
            val dx = ArrayList<Float>(a.size)
            val dy = ArrayList<Float>(a.size)
            for (k in a.indices) {
                if (k >= b.size || k >= c.size || k >= s1.size || k >= s2.size) break
                if (s1[k].toInt() != 1 || s2[k].toInt() != 1) continue
                if (hypot(a[k].x - c[k].x, a[k].y - c[k].y) >= 1.0) continue
                dx += (b[k].x - a[k].x).toFloat()
                dy += (b[k].y - a[k].y).toFloat()
            }
            if (dx.size < 12) return FloatArray(2)
            return floatArrayOf(median(dx) / w, median(dy) / h)
        } finally {
            p.release()
        }
    }

    private fun median(v: ArrayList<Float>): Float {
        v.sort()
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2f
    }

    override fun close() {
        reset()
        grid.release(); next.release(); back.release()
        st1.release(); st2.release(); err.release()
    }

    private companion object {
        const val GX = 10
        const val GY = 14
    }
}

/**
 * Hard-cut detector for edited videos: a cut is when a tiny thumbnail of the frame changes a
 * lot AND its brightness histogram changes a lot (fast pans change the first, not the second).
 */
class CutDetector : Closeable {
    private var prevThumb: FloatArray? = null
    private var prevHist: FloatArray? = null
    private val thumb = Mat()
    private var all = ByteArray(0)

    fun reset() {
        prevThumb = null
        prevHist = null
    }

    fun isCut(gray: Mat): Boolean {
        Imgproc.resize(gray, thumb, Size(32.0, 18.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val tb = ByteArray(32 * 18)
        thumb.get(0, 0, tb)
        val th = FloatArray(tb.size) { (tb[it].toInt() and 0xFF).toFloat() }

        val n = gray.cols() * gray.rows()
        if (all.size != n) all = ByteArray(n)
        gray.get(0, 0, all)
        val hist = FloatArray(32)
        for (b in all) hist[(b.toInt() and 0xFF) shr 3] += 1f
        for (i in hist.indices) hist[i] /= n.toFloat()

        val pt = prevThumb
        val ph = prevHist
        prevThumb = th
        prevHist = hist
        if (pt == null || ph == null) return false

        var diff = 0f
        for (i in th.indices) diff += abs(th[i] - pt[i])
        diff /= th.size
        val corr = correlation(hist, ph)
        return (diff > 25f && corr < 0.75f) || diff > 45f
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den < 1e-12f) 1f else num / den
    }

    override fun close() {
        thumb.release()
    }
}
