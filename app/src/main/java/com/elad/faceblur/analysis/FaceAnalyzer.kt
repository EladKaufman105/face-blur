package com.elad.faceblur.analysis

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

object OpenCv {
    @Volatile private var loaded = false

    /** Which library was loaded: "minimal" (only the modules the app uses) or "full". */
    var variant = ""
        private set

    fun ensureLoaded() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                variant = try {
                    System.loadLibrary("opencv_java_min")
                    "minimal"
                } catch (e: UnsatisfiedLinkError) {
                    android.util.Log.i("ONIT", "minimal OpenCV not loaded: ${e.message}")
                    System.loadLibrary("opencv_java4")
                    "full"
                }
                loaded = true
            }
        }
    }
}

/**
 * Analysis pass: receives each frame (RGBA, top row first) from the GL pipeline, runs face
 * detection on ~15 frames per second of video (as the web version; the offline tracker fills
 * the frames between, with the camera's motion) and measures camera motion and hard cuts on
 * every frame. When the pass is done, [result] links everything into face tracks
 * offline. Called on the video pipeline's GL thread; the timeline is read after the pass.
 *
 * Detection runs on [WORKERS] threads of its own, each with its own detector, a few frames at
 * a time: the decoder and the motion measurement don't wait for it, and the CPU's cores are
 * kept busier than by one detector alone.
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
    // The big detection pass on the GPU, when the phone and the downloaded models allow it.
    private val gpu = GpuFaceNet.create(context)
    // The rest on the CPU in fp16 once it's shown to agree with fp32 here.
    private val half = HalfPrecision()

    /** The CPU passes' precision: "fp16", "fp16-checking" or "fp32". */
    val precision get() = half.state

    /** Where detection ran: "gpu", "gpu-checking" (still compared with the CPU), "cpu". */
    val accelerator: String
        get() = when {
            gpu == null || !gpu.active -> "cpu"
            gpu.trusted -> "gpu"
            else -> "gpu-checking"
        }

    // Detection workers: each thread keeps its own detector (OpenCV nets aren't shared).
    private val detectors = java.util.Collections.synchronizedList(ArrayList<FaceDetector>())
    private val workerDetector = ThreadLocal<FaceDetector>()
    // Below the app's own threads: on a slow tablet the detectors take every core, and the
    // screen (progress, preview) must still move. When nothing else runs they get it all anyway.
    private val pool = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "face-detect")
    }
    // Frames waiting for or in detection (bounds the memory they take).
    private val slots = Semaphore(WORKERS + 1)
    private val failure = AtomicReference<Throwable?>(null)
    // Bumped by reset(): results of an abandoned pass are dropped.
    @Volatile private var generation = 0

    /**
     * Now and then, a small copy of the frame, the faces found lately, where detection runs
     * ([accelerator]) and how the pass is doing ([stats]; the progress screen shows it on a tap).
     */
    var onPreview: ((Bitmap, List<Det>, String, String) -> Unit)? = null
    private var lastPreview = 0L
    @Volatile private var lastDets: List<Det> = emptyList()
    private val preview = Mat()
    private val camera = CameraMotion()
    private val cutDetector = CutDetector()
    private var frames: FrameStore? = FrameStore(File(context.cacheDir, "frames.bin"))

    private val times = ArrayList<Long>()
    private val dets = ConcurrentHashMap<Int, List<Det>>()
    private var motion = FloatArray(1024)
    private val cuts = ArrayList<Int>()
    private var uprightW = 0
    private var uprightH = 0
    private var rotation = 0

    private val rotated = Mat()
    private val small = Mat()

    // Where the time goes (see [stats]).
    @Volatile private var detectMsTotal = 0L
    @Volatile private var detections = 0
    private var frameMsTotal = 0L
    private var waitMsTotal = 0L

    // Detection ~15 times per second of video (every frame in sensitive mode); camera motion
    // and cut detection run on every frame. On a slow phone or tablet (where the frames wait
    // for the detectors) fewer, down to ~8 per second, and the crop check only for uncertain
    // faces: the tracker fills the frames between.
    private val baseEvery = if (sensitive) 1 else max(1, (fps / RATE).roundToInt())
    private val maxEvery = if (sensitive) 1 else max(baseEvery, (fps / MIN_RATE).toInt())

    // Clear faces found in several detections in a row (see [FaceDetector]): not re-checked on
    // a crop. Updated as detections finish (workers finish out of order; close enough).
    private class Followed(var box: Box, var streak: Int, var seen: Int)
    private val followed = ArrayList<Followed>()
    @Volatile private var detectEvery = baseEvery
    @Volatile private var lite = false
    // The fine pass's long side on the CPU: lowered on a device too slow for it.
    @Volatile private var cpuSide = RetinaFaceDetector.CPU_FINE_SIDE
    private var windowStart = 0L
    private var blockedMs = 0L

    /** How detection adapted to the device: "every frame", "every 2nd frame, lite"... */
    val pace: String get() = "every ${detectEvery}" + (if (lite) ", lite" else "")

    /**
     * Where the time goes, for whoever tunes this: where detection runs, how often, the time
     * per detection (one worker), per frame on the video's thread, and how long frames waited
     * for a free worker.
     */
    val stats: String
        get() {
            val n = max(1, times.size)
            val d = max(1, detections)
            val side = if (gpu != null && gpu.active) "1280" else "$cpuSide"
            val gpuMs = gpu?.inferMs?.takeIf { it > 0f }?.let { " (gpu ${it.roundToInt()} ms)" } ?: ""
            return "$accelerator/${half.state} ${side}px$gpuMs · every $detectEvery${if (lite) " lite" else ""} · " +
                "detect ${detectMsTotal / d} ms · frame ${frameMsTotal / n} ms · wait ${waitMsTotal / n} ms/frame"
        }

    init {
        reset()
    }

    @Synchronized
    fun reset() {
        generation++
        drain()
        failure.set(null)
        lastDets = emptyList()
        synchronized(followed) { followed.clear() }
        camera.reset()
        cutDetector.reset()
        frames?.reset()
        times.clear()
        dets.clear()
        cuts.clear()
        detectEvery = baseEvery
        lite = false
        cpuSide = RetinaFaceDetector.CPU_FINE_SIDE
        windowStart = 0L
        blockedMs = 0L
        detectMsTotal = 0L
        detections = 0
        frameMsTotal = 0L
        waitMsTotal = 0L
    }

    /**
     * The size frames are measured at for camera motion (and kept for the editor's tracking):
     * [w] x [h] scaled to [MOTION_SIDE] at most. Frames handed in at this size ([onFrame] with
     * full = false) skip the scaling.
     */
    fun motionSize(w: Int, h: Int): Pair<Int, Int> {
        val s = minOf(1f, MOTION_SIDE.toFloat() / max(w, h))
        return max(16, (w * s).roundToInt()) to max(16, (h * s).roundToInt())
    }

    /** Whether the next frame is one to detect faces on (so it's needed at full size). */
    @Synchronized
    fun wantsFull(): Boolean = times.size % detectEvery == 0

    /**
     * @param full the frame is at [maxSide] (for detection); else at [motionSize] (motion only).
     * @param rotation clockwise degrees that make the frame upright (normally 0: the pipeline
     *   already hands effects display-oriented frames).
     */
    @Synchronized
    fun onFrame(pixels: ByteBuffer, width: Int, height: Int, timeUs: Long, rotation: Int, full: Boolean = true) {
        val t0 = SystemClock.elapsedRealtime()
        // The frame's own buffer, read in place (no copies).
        pixels.rewind()
        val rgba = Mat(height, width, CvType.CV_8UC4, pixels)
        val src = when (rotation) {
            90 -> { Core.rotate(rgba, rotated, Core.ROTATE_90_CLOCKWISE); rotated }
            180 -> { Core.rotate(rgba, rotated, Core.ROTATE_180); rotated }
            270 -> { Core.rotate(rgba, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); rotated }
            else -> rgba
        }
        this.rotation = rotation
        if (full) {
            uprightW = src.cols()
            uprightH = src.rows()
        }

        val idx = times.size
        times += timeUs

        val (tw, th) = motionSize(src.cols(), src.rows())
        val gray = Mat()
        if (src.cols() == tw && src.rows() == th) {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        } else {
            Imgproc.resize(src, small, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        }
        frames?.append(gray)
        if (cutDetector.isCut(gray)) {
            cuts += idx
            camera.reset()
        }
        val m = camera.step(gray)
        if (motion.size < 2 * (idx + 1)) motion = motion.copyOf(motion.size * 2)
        motion[2 * idx] = m[0]
        motion[2 * idx + 1] = m[1]

        var waited = 0L
        if (full && idx % detectEvery == 0) {
            failure.get()?.let { throw it }
            // The worker gets its own copy (native, once); the frame's buffer is reused.
            val frame = Mat()
            Imgproc.cvtColor(src, frame, Imgproc.COLOR_RGBA2BGR)
            val gen = generation
            val waitFrom = SystemClock.elapsedRealtime()
            slots.acquire()
            waited = SystemClock.elapsedRealtime() - waitFrom
            blockedMs += waited
            waitMsTotal += waited
            adapt(idx)
            val known = followedBoxes()
            pool.execute {
                try {
                    if (gen == generation) {
                        val d0 = SystemClock.elapsedRealtime()
                        val found = detector().detect(frame, lite, known, cpuSide)
                        detectMsTotal += SystemClock.elapsedRealtime() - d0
                        detections++
                        if (gen == generation) {
                            dets[idx] = found
                            lastDets = found
                            follow(idx, found)
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    frame.release()
                    slots.release()
                }
            }
        }

        val cb = onPreview
        val now = SystemClock.elapsedRealtime()
        if (cb != null && now - lastPreview >= PREVIEW_MS) {
            lastPreview = now
            val ps = minOf(1f, PREVIEW_SIDE.toFloat() / max(uprightW, uprightH))
            val pw = max(2, (uprightW * ps).roundToInt())
            val ph = max(2, (uprightH * ps).roundToInt())
            Imgproc.resize(src, preview, Size(pw.toDouble(), ph.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val px = ByteArray(pw * ph * 4)
            preview.get(0, 0, px)
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px))
            cb(bmp, lastDets, accelerator, stats)
        }
        rgba.release()
        frameMsTotal += SystemClock.elapsedRealtime() - t0 - waited
    }

    /** Boxes of the clear faces seen in at least [FOLLOWED_AFTER] detections in a row. */
    private fun followedBoxes(): List<Box> = synchronized(followed) { followed.filter { it.streak >= FOLLOWED_AFTER }.map { it.box } }

    /** Extends each clear face's run with this detection's faces; drops runs not seen lately. */
    private fun follow(idx: Int, found: List<Det>) = synchronized(followed) {
        for (d in found) {
            if (d.score < FaceDetector.CLEAR) continue
            val f = followed.maxByOrNull { Geometry.iou(it.box, d.box) }?.takeIf { Geometry.iou(it.box, d.box) > 0.3f }
            if (f == null) followed += Followed(d.box, 1, idx)
            else {
                if (idx > f.seen) { f.streak++; f.seen = idx; f.box = d.box }
            }
        }
        // A run broken for more than a couple of detections starts over.
        val gap = detectEvery * 3
        followed.removeAll { idx - it.seen > gap }
    }

    /**
     * Every 1.5 s or so: if the frames spent over a third of the time waiting for a free
     * detector, detection is what holds the analysis back, so it gets lighter: on the CPU, the
     * fine pass at 640 px first (about half the work); then less often (down to [MIN_RATE]).
     * Lighter also means crops only for uncertain faces and no YuNet.
     */
    private fun adapt(idx: Int) {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) {
            windowStart = now
            blockedMs = 0L
            return
        }
        val span = now - windowStart
        if (span < 1500 || idx < 20) return
        if (blockedMs > span / 3) {
            val g = gpu
            if (g != null && g.active && g.inferMs > GPU_SLOW_MS) {
                // A GPU this slow (one lane, ~2-10 frames a second) holds back the CPU's cores:
                // they do better together, at the lighter size a device like this needs.
                g.disable()
                cpuSide = LOW_SIDE
                lite = true
                android.util.Log.i("ONIT", "the GPU takes ${g.inferMs.roundToInt()} ms a frame: CPU at $LOW_SIDE px from now on")
            } else if ((g == null || !g.active) && cpuSide > LOW_SIDE) {
                cpuSide = LOW_SIDE
                lite = true
                android.util.Log.i("ONIT", "face detection slower than the video: $LOW_SIDE px, lite")
            } else if (detectEvery < maxEvery) {
                detectEvery++
                lite = true
                android.util.Log.i("ONIT", "face detection slower than the video: every $detectEvery frames, lite")
            }
        }
        windowStart = now
        blockedMs = 0L
    }

    private fun detector(): FaceDetector = workerDetector.get() ?: FaceDetector(retinaPath, yunetPath, gpu, half).also {
        workerDetector.set(it)
        detectors += it
    }

    /** Waits until every frame handed to the workers is done. */
    private fun drain() {
        slots.acquireUninterruptibly(WORKERS + 1)
        slots.release(WORKERS + 1)
    }

    @Synchronized
    fun frameCount() = times.size

    /** Everything the analysis found; takes over the frame store (closing the result frees it). */
    @Synchronized
    fun result(): AnalysisResult {
        drain()
        failure.get()?.let { throw it }
        val n = times.size
        val diffs = (1 until n).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
        val measuredFps = (if (diffs.isEmpty()) fps else 1_000_000f / diffs[diffs.size / 2]).coerceIn(5f, 240f)
        val aspect = if (uprightW > 0) uprightH.toFloat() / uprightW else 1f
        val cutList = cuts.filter { it in 1 until n }
        val tracks = OfflineTracker(measuredFps, aspect, sensitive).run(n, dets, motion, cutList)
        val store = frames
        frames = null
        return AnalysisResult(
            times = times.toLongArray(),
            cuts = cutList.toIntArray(),
            motion = motion.copyOf(2 * n),
            dets = HashMap(dets),
            tracks = tracks,
            uprightW = uprightW,
            uprightH = uprightH,
            rotation = rotation,
            fps = measuredFps,
            frames = store?.takeIf { it.size == n },
        ).also { if (it.frames == null) store?.close() }
    }

    @Synchronized
    override fun close() {
        generation++
        drain()
        pool.shutdown()
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        synchronized(detectors) {
            detectors.forEach { it.close() }
            detectors.clear()
        }
        gpu?.close()
        preview.release()
        frames?.close()
        frames = null
        camera.close()
        cutDetector.close()
        rotated.release()
        small.release()
    }

    companion object {
        private const val MOTION_SIDE = 640
        private const val PREVIEW_SIDE = 480
        private const val PREVIEW_MS = 200L

        /** Detections per second of video, and the fewest a slow device goes down to. */
        private const val RATE = 15f
        private const val MIN_RATE = 8f
        /** The fine pass's long side on the CPU when the device can't keep up. */
        private const val LOW_SIDE = 640
        /** A GPU slower than this per frame, when detection holds the pass back, gives way to the CPU. */
        private const val GPU_SLOW_MS = 80f
        /** Detections in a row after which a clear face isn't re-checked on a crop. */
        private const val FOLLOWED_AFTER = 4

        /** Detection threads (measured on ARM: 3 keep the cores busiest). */
        const val WORKERS = 3

        /**
         * The model file to load: the asset, copied out once. Copied again when the app ships a
         * different one (sizes differ), or an update would keep using the old model.
         */
        fun asset(context: Context, name: String): String {
            val file = File(context.filesDir, name)
            val size = runCatching { context.assets.openFd(name).use { it.length } }.getOrDefault(-1L)
            if (!file.exists() || file.length() == 0L || (size > 0 && file.length() != size)) {
                val tmp = File(context.filesDir, "$name.tmp")
                context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
            return file.absolutePath
        }
    }
}
