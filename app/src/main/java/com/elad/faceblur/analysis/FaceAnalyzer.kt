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
 * Analysis pass: receives each frame (RGBA, top row first) from the GL pipeline, runs face
 * detection on ~30 frames per second of video and measures camera motion and hard cuts on
 * every frame. When the pass is done, [buildTimeline] links everything into face tracks
 * offline. Called on the video pipeline's GL thread; the timeline is read after the pass.
 */
class FaceAnalyzer(context: Context, private val sensitive: Boolean, private val fps: Float) : Closeable {

    // Must run before any Mat field below is constructed (initializers run in declaration order).
    init {
        OpenCv.ensureLoaded()
    }

    /** Long side of the frames handed to [onFrame]: small/distant faces need the resolution. */
    val maxSide = 1280

    private val retinaPath = asset(context, RetinaFaceDetector.MODEL)
    private val yunetPath = asset(context, FaceDetector.YUNET_MODEL)
    private var detector: FaceDetector? = null
    private val camera = CameraMotion()
    private val cutDetector = CutDetector()

    private val times = ArrayList<Long>()
    private val dets = HashMap<Int, List<Det>>()
    private var motion = FloatArray(1024)
    private val cuts = ArrayList<Int>()
    private var uprightW = 0
    private var uprightH = 0
    private var rotation = 0

    private var bytes = ByteArray(0)
    private var rgba = Mat()
    private val rotated = Mat()
    private val bgr = Mat()
    private val small = Mat()

    // Detection ~30 times per second of video (every frame in sensitive mode); camera motion
    // and cut detection run on every frame.
    private val detectEvery = if (sensitive) 1 else max(1, (fps / 30f).roundToInt())

    init {
        reset()
    }

    @Synchronized
    fun reset() {
        detector?.close()
        detector = FaceDetector(retinaPath, yunetPath)
        camera.reset()
        cutDetector.reset()
        times.clear()
        dets.clear()
        cuts.clear()
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

        val ts = minOf(1f, MOTION_SIDE.toFloat() / max(uprightW, uprightH))
        val tw = max(16, (uprightW * ts).roundToInt())
        val th = max(16, (uprightH * ts).roundToInt())
        Imgproc.resize(src, small, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        if (cutDetector.isCut(gray)) {
            cuts += idx
            camera.reset()
        }
        val m = camera.step(gray)
        if (motion.size < 2 * (idx + 1)) motion = motion.copyOf(motion.size * 2)
        motion[2 * idx] = m[0]
        motion[2 * idx + 1] = m[1]

        if (idx % detectEvery == 0) {
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            dets[idx] = detector!!.detect(bgr)
        }
    }

    @Synchronized
    fun frameCount() = times.size

    @Synchronized
    fun buildTimeline(padding: Float): FaceTimeline {
        val n = times.size
        val diffs = (1 until n).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
        val measuredFps = if (diffs.isEmpty()) fps else 1_000_000f / diffs[diffs.size / 2]
        val aspect = if (uprightW > 0) uprightH.toFloat() / uprightW else 1f
        val tracks = OfflineTracker(measuredFps.coerceIn(5f, 240f), aspect, sensitive).run(n, dets, motion, cuts)
        return FaceTimeline.build(
            tracks,
            times.toLongArray(),
            cuts.filter { it < n }.map { times[it] }.toLongArray(),
            uprightW, uprightH, rotation, padding,
        )
    }

    @Synchronized
    override fun close() {
        detector?.close()
        detector = null
        camera.close()
        cutDetector.close()
        rgba.release()
        rotated.release()
        bgr.release()
        small.release()
    }

    private fun asset(context: Context, name: String): String {
        val file = File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file.absolutePath
    }

    companion object {
        private const val MOTION_SIDE = 640
    }
}
