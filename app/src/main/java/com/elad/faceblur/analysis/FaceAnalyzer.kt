package com.elad.faceblur.analysis

import android.content.Context
import com.elad.faceblur.track.FaceTimeline
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

object OpenCv {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                System.loadLibrary("opencv_java4")
                loaded = true
            }
        }
    }
}

/**
 * Analysis pass: receives each frame (RGBA, top row first) from the GL pipeline, detects
 * and tracks faces. Called on the video pipeline's GL thread; the timeline is read on the
 * main thread after the pass completes.
 */
class FaceAnalyzer(context: Context, sensitive: Boolean, private val fps: Float) : Closeable {

    /** Long side of the frames handed to [onFrame]. */
    val maxSide = if (sensitive) 1920 else 1280

    private val modelPath: String
    private var detector: YuNetDetector? = null
    private var tracker: FaceTracker? = null
    private val times = ArrayList<Long>()
    private var uprightW = 0
    private var uprightH = 0
    private var rotation = 0

    private var bytes = ByteArray(0)
    private var rgba = Mat()
    private val rotated = Mat()
    private val bgr = Mat()
    private val small = Mat()

    // At high frame rates detection runs on every other frame; tracking still runs on all.
    private val detectEvery = if (fps > 35f) 2 else 1

    init {
        OpenCv.ensureLoaded()
        val file = File(context.filesDir, MODEL)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(MODEL).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        modelPath = file.absolutePath
        reset()
    }

    @Synchronized
    fun reset() {
        tracker?.release()
        tracker = FaceTracker(fps.coerceIn(5f, 240f))
        detector?.close()
        detector = YuNetDetector(modelPath)
        times.clear()
    }

    /**
     * @param rotation clockwise degrees that make the frame upright (normally 0: the pipeline
     *   already hands effects display-oriented frames).
     */
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
        this.rotation = rotation
        uprightW = src.cols()
        uprightH = src.rows()

        val idx = times.size
        times += timeUs
        val runDetection = idx % detectEvery == 0
        val dets = if (runDetection) {
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            detector!!.detect(bgr)
        } else emptyList()

        val ts = minOf(1f, TRACK_SIDE.toFloat() / max(uprightW, uprightH))
        val tw = max(16, (uprightW * ts).roundToInt())
        val th = max(16, (uprightH * ts).roundToInt())
        Imgproc.resize(src, small, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        tracker!!.step(idx, dets, runDetection, gray)
    }

    @Synchronized
    fun frameCount() = times.size

    @Synchronized
    fun buildTimeline(padding: Float): FaceTimeline =
        FaceTimeline.build(tracker!!.finish(), times.toLongArray(), uprightW, uprightH, rotation, padding)

    @Synchronized
    override fun close() {
        tracker?.release()
        detector?.close()
        rgba.release()
        rotated.release()
        bgr.release()
        small.release()
    }

    companion object {
        const val MODEL = "face_detection_yunet_2023mar.onnx"
        private const val TRACK_SIDE = 640
    }
}
