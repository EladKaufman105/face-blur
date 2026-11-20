package com.elad.faceblur.analysis

import android.content.Context
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Finds the people in each frame: PP-HumanSeg (PaddleSeg, Apache 2.0, from OpenCV Zoo) through
 * OpenCV. The model takes a 192 x 192 square, so a wide (or tall) frame is covered by three
 * overlapping square tiles, blended with feathered edges: people aren't squeezed and are seen
 * bigger. Masks are smoothed over time within a shot (less flicker) and stored by analysis frame.
 *
 * @param times analysis frame times (µs), to file each mask under its frame.
 * @param cuts first frames of shots (smoothing doesn't cross a cut).
 */
class Segmenter(context: Context, private val times: LongArray, cuts: IntArray, private val store: MaskStore) : Closeable {
    private val net: Net
    private val cutSet = cuts.toHashSet()
    private var rgba = Mat()
    private val rotated = Mat()
    private val rgb = Mat()
    private var prev: Mat? = null
    private var prevIndex = -100
    private var bytes = ByteArray(0)
    private val probs = FloatArray(IN * IN)
    private val tileProb = Mat(IN, IN, CvType.CV_32F)
    private val tileBig = Mat()
    private var acc = Mat()
    private var wsum = Mat()

    /** Frames done. */
    @Volatile var done = 0
        private set

    init {
        OpenCv.ensureLoaded()
        val f = File(context.filesDir, MODEL)
        if (!f.exists() || f.length() == 0L) context.assets.open(MODEL).use { i -> f.outputStream().use { i.copyTo(it) } }
        net = Dnn.readNetFromONNX(f.absolutePath)
    }

    /** One frame from the grab pass: RGBA, top row first; [rotation] makes it upright. */
    @Synchronized
    fun onFrame(pixels: ByteBuffer, width: Int, height: Int, timeUs: Long, rotation: Int) {
        val n = width * height * 4
        if (bytes.size != n) bytes = ByteArray(n)
        pixels.rewind()
        pixels.get(bytes)
        if (rgba.rows() != height || rgba.cols() != width) {
            rgba.release()
            rgba = Mat(height, width, CvType.CV_8UC4)
        }
        rgba.put(0, 0, bytes)
        val src = when (rotation) {
            90 -> { Core.rotate(rgba, rotated, Core.ROTATE_90_CLOCKWISE); rotated }
            180 -> { Core.rotate(rgba, rotated, Core.ROTATE_180); rotated }
            270 -> { Core.rotate(rgba, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); rotated }
            else -> rgba
        }
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
        val prob = people(rgb)
        val m8 = Mat()
        Imgproc.resize(prob, m8, Size(MaskStore.SIZE.toDouble(), MaskStore.SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        m8.convertTo(m8, CvType.CV_8U, 255.0)
        val idx = nearest(timeUs)
        val p = prev
        if (p != null && idx == prevIndex + 1 && idx !in cutSet) {
            Core.addWeighted(m8, 0.7, p, 0.3, 0.0, m8)
        }
        store.put(idx, m8)
        prev?.release()
        prev = m8
        prevIndex = idx
        done++
    }

    /** Person probability over the whole frame (frame size, 0..1). */
    private fun people(img: Mat): Mat {
        val w = img.cols()
        val h = img.rows()
        val side = minOf(w, h)
        val long = maxOf(w, h)
        // Nearly square: one look at the whole frame.
        val tiles = if (long < side * 1.2) 1 else 3
        if (acc.rows() != h || acc.cols() != w) {
            acc.release(); wsum.release()
            acc = Mat(h, w, CvType.CV_32F)
            wsum = Mat(h, w, CvType.CV_32F)
        }
        if (tiles == 1) {
            infer(img, tileProb)
            Imgproc.resize(tileProb, acc, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            return acc
        }
        acc.setTo(Scalar(0.0))
        wsum.setTo(Scalar(0.0))
        val wide = w >= h
        val weight = feather(side)
        for (k in 0 until tiles) {
            val off = Math.round((long - side) * k / (tiles - 1).toFloat())
            val rect = if (wide) Rect(off, 0, side, side) else Rect(0, off, side, side)
            val tile = img.submat(rect)
            infer(tile, tileProb)
            tile.release()
            Imgproc.resize(tileProb, tileBig, Size(side.toDouble(), side.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            // Edges of a tile count less, except at the frame's own edges.
            val wt = weight.clone()
            if (k == 0) wt.submat(if (wide) Rect(0, 0, side / 2, side) else Rect(0, 0, side, side / 2)).setTo(Scalar(1.0))
            if (k == tiles - 1) wt.submat(if (wide) Rect(side / 2, 0, side - side / 2, side) else Rect(0, side / 2, side, side - side / 2)).setTo(Scalar(1.0))
            val a = acc.submat(rect)
            val s = wsum.submat(rect)
            val pw = Mat()
            Core.multiply(tileBig, wt, pw)
            Core.add(a, pw, a)
            Core.add(s, wt, s)
            pw.release(); wt.release(); a.release(); s.release()
        }
        weight.release()
        Core.max(wsum, Scalar(1e-6), wsum)
        Core.divide(acc, wsum, acc)
        return acc
    }

    /** A square's weights: 1 in the middle, falling to 0 over the outer quarter on the tiling axis. */
    private fun feather(side: Int): Mat {
        val ramp = FloatArray(side) { i -> (minOf(i + 1, side - i) / (side * 0.25f)).coerceAtMost(1f) }
        val m = Mat(side, side, CvType.CV_32F)
        val row = FloatArray(side * side)
        for (y in 0 until side) for (x in 0 until side) row[y * side + x] = ramp[x]
        m.put(0, 0, row)
        return m
    }

    private val tmpT = Mat()

    /** The model on [img] (RGB): person probability, [IN] x [IN], into [out]. */
    private fun infer(img: Mat, out: Mat) {
        // (x / 255 - 0.5) / 0.5, as the model was trained.
        val blob = Dnn.blobFromImage(img, 1.0 / 127.5, Size(IN.toDouble(), IN.toDouble()), Scalar(127.5, 127.5, 127.5), false, false)
        net.setInput(blob)
        val o = net.forward()
        o.get(intArrayOf(0, 1, 0, 0), probs)
        o.release()
        blob.release()
        out.put(0, 0, probs)
    }

    private fun nearest(t: Long): Int {
        if (times.isEmpty()) return 0
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return if (abs(times[hi] - t) < abs(times[lo] - t)) hi else lo
    }

    @Synchronized
    override fun close() {
        rgba.release()
        rotated.release()
        rgb.release()
        tileProb.release()
        tileBig.release()
        acc.release()
        wsum.release()
        tmpT.release()
        prev?.release()
        prev = null
    }

    companion object {
        const val MODEL = "human_segmentation_pphumanseg_2023mar.onnx"
        /** The model's input side. */
        private const val IN = 192
        /** Long side of the frame the tiles are cut from. */
        const val GRAB_LONG = 576
    }
}
