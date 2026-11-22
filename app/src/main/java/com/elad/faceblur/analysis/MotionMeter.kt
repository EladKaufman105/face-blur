package com.elad.faceblur.analysis

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The camera's motion and the hard cuts, frame by frame, without face detection: for steadying
 * a video that was opened without it (subtitles, sound…). The same measurements the face
 * analysis makes (see FaceAnalyzer).
 */
class MotionMeter : Closeable {
    private val camera = CameraMotion()
    private val cutDetector = CutDetector()
    private var rgba = Mat()
    private val rotated = Mat()
    private val small = Mat()
    private var bytes = ByteArray(0)
    private val times = ArrayList<Long>()
    private val cuts = ArrayList<Int>()
    private var motion = FloatArray(256)
    var uprightW = 0
        private set
    var uprightH = 0
        private set
    var rotation = 0
        private set

    init {
        OpenCv.ensureLoaded()
    }

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
        val s = minOf(1f, SIDE.toFloat() / max(uprightW, uprightH))
        Imgproc.resize(src, small, Size(max(16, (uprightW * s).roundToInt()).toDouble(), max(16, (uprightH * s).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        if (cutDetector.isCut(gray)) {
            cuts += idx
            camera.reset()
        }
        // Takes ownership of gray.
        val m = camera.step(gray)
        if (motion.size < 2 * (idx + 1)) motion = motion.copyOf(motion.size * 2)
        motion[2 * idx] = m[0]
        motion[2 * idx + 1] = m[1]
    }

    /** (frame times, per-frame motion, cut frame indices). */
    @Synchronized
    fun result(): Triple<LongArray, FloatArray, IntArray> {
        // Frames may arrive out of order around a seek; they never do in one pass.
        val t = times.toLongArray()
        return Triple(t, motion.copyOf(2 * t.size), cuts.toIntArray())
    }

    override fun close() {
        camera.close()
        cutDetector.close()
        rgba.release()
        rotated.release()
        small.release()
    }

    private companion object {
        /** Same as the face analysis's motion frames. */
        const val SIDE = 640
    }
}
