package com.elad.faceblur.analysis

import android.content.Context
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
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
 * Finds the people in each frame: PP-HumanSeg (PaddleSeg, Apache 2.0, from OpenCV Zoo) on a
 * 192 x 192 picture of the frame, through OpenCV. Masks are smoothed over time within a shot
 * (less flicker) and stored by analysis frame.
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
    private val probs = FloatArray(MaskStore.SIZE * MaskStore.SIZE)

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
        val s = MaskStore.SIZE.toDouble()
        // (x / 255 - 0.5) / 0.5, as the model was trained.
        val blob = Dnn.blobFromImage(rgb, 1.0 / 127.5, Size(s, s), Scalar(127.5, 127.5, 127.5), false, false)
        net.setInput(blob)
        val out = net.forward()
        out.get(intArrayOf(0, 1, 0, 0), probs)
        out.release()
        blob.release()
        val mask = Mat(MaskStore.SIZE, MaskStore.SIZE, CvType.CV_32F)
        mask.put(0, 0, probs)
        val m8 = Mat()
        mask.convertTo(m8, CvType.CV_8U, 255.0)
        mask.release()
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
        prev?.release()
        prev = null
    }

    companion object {
        const val MODEL = "human_segmentation_pphumanseg_2023mar.onnx"
    }
}
