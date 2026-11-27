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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 * Detection runs on threads of its own, each with its own detector, a few frames at a time: the
 * decoder and the motion measurement don't wait for it, and the CPU's cores are kept busier
 * than by one detector alone. The pool has [WORKERS] threads, but only [BASE_WORKERS] frames
 * are in flight while the CPU does the fine pass (more threads would only fight over its big
 * cores); with a trusted GPU doing it, the CPU side of a detection is small and all of them work.
 */
class FaceAnalyzer private constructor(
    context: Context,
    /** Normal, extended (every frame, weaker evidence), fast (5 a second) or selfie (BlazeFace on every frame): see [DetectMode]. */
    private val mode: DetectMode,
    private val fps: Float,
    /** 0..1: above 0, lower thresholds add faces, never take any away (see [DetectTuning]). */
    private val sensitivity: Float,
    // The whole face pack (selfie mode: the selfie pack), each file checked (size and SHA-256)
    // right before (see invoke()): face blur never runs on part of it, and never on a weaker
    // detector instead. Held (it can't be deleted or switched) until close().
    private val faceFiles: Map<String, File>,
) : Closeable {
    private val appContext: Context = context.applicationContext

    // Must run before any Mat field below is constructed (initializers run in declaration order).
    init {
        OpenCv.ensureLoaded()
    }

    /** Long side of the frames handed to [onFrame]: small/distant faces need the resolution. */
    val maxSide = 1280

    // Selfie mode runs BlazeFace alone: none of the face pack's detectors, nor its GPU models.
    private val selfie = mode == DetectMode.SELFIE
    private val pack = if (selfie) com.elad.faceblur.models.PackId.SELFIE else com.elad.faceblur.models.PackId.FACES

    @Volatile private var packHeld = true
    private val retinaPath = if (selfie) "" else packFile(RetinaFaceDetector.MODEL)
    private val yunetPath = if (selfie) "" else packFile(FaceDetector.YUNET_MODEL)
    private val blazeFile = if (selfie) packFile(BlazeFaceDetector.MODEL) else ""
    // The big detection pass on the GPU, when the phone allows it (its models are in the pack).
    private val gpu = if (selfie) null else GpuFaceNet.create(faceFiles)

    private fun packFile(name: String): String =
        faceFiles[name]?.absolutePath ?: throw com.elad.faceblur.models.ModelMissing(pack)
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
    // Selfie mode's detectors, one per worker thread too.
    private val blazes = java.util.Collections.synchronizedList(ArrayList<BlazeFaceDetector>())
    private val workerBlaze = ThreadLocal<BlazeFaceDetector>()
    // At the default priority: below the screen's threads (the main thread runs at -2, so on a
    // slow tablet the progress and preview keep moving), but not "background", which on some
    // phones keeps a thread on the small cores (a Dimensity 9500 took 411 ms a detection at
    // 640 px that way, against 186 ms at 960 before).
    private val pool = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, "face-detect")
    }
    // Frames waiting for or in detection (bounds the memory they take).
    private val slots = Semaphore(BASE_WORKERS + 1)
    // Permits added beyond BASE_WORKERS + 1 while a trusted GPU does the fine pass. Changed only
    // under the analyzer's lock (onFrame, reset), which drain() runs under too.
    @Volatile private var extraSlots = 0
    private val failure = AtomicReference<Throwable?>(null)
    // Bumped by reset(): results of an abandoned pass are dropped.
    @Volatile private var generation = 0

    /**
     * Now and then, a small copy of the frame, the faces found lately, where detection runs
     * ([accelerator]) and the pass's counters ([snapshot]; the progress screen shows them on a tap).
     */
    var onPreview: ((Bitmap, List<Det>, String, StatsSnapshot) -> Unit)? = null
    private var lastPreview = 0L
    @Volatile private var lastDets: List<Det> = emptyList()
    private val preview = Mat()
    private val camera = CameraMotion()
    private val cutDetector = CutDetector()
    private var frames: FrameStore? = FrameStore(File(context.cacheDir, "frames_" + java.util.UUID.randomUUID().toString().take(8) + ".bin"))

    private val times = ArrayList<Long>()
    private val dets = ConcurrentHashMap<Int, List<Det>>()
    // A sensitivity above 0: the faces only the lower thresholds find, per frame (see [DetectTuning]).
    private val extraDets = ConcurrentHashMap<Int, List<Det>>()
    private val sensitive = mode == DetectMode.EXTENDED
    private var motion = FloatArray(1024)
    private val cuts = ArrayList<Int>()
    private var uprightW = 0
    private var uprightH = 0
    private var rotation = 0

    private val rotated = Mat()
    private val small = Mat()

    // Where the time goes (see [snapshot]).
    private val detectMsTotal = AtomicLong()
    private val detections = AtomicInteger()
    // Per detection, summed over the workers, in microseconds (see [FaceDetector]'s last... values).
    private val fineUsTotal = AtomicLong()
    private val coarseUsTotal = AtomicLong()
    private val gpuCallUsTotal = AtomicLong()
    private val yunetUsTotal = AtomicLong()
    private val cropUsTotal = AtomicLong()
    private val cropsTotal = AtomicInteger()
    private var frameMsTotal = 0L
    private var waitMsTotal = 0L

    // Detection ~15 times per second of video (every frame in sensitive mode, 5 a second in fast
    // mode); camera motion and cut detection run on every frame. On a slow phone or tablet (where
    // the frames wait for the detectors) fewer, down to ~8 per second (fast mode stays at 5), and
    // the crop check only for uncertain faces: the tracker fills the frames between.
    private val baseEvery = DetectTuning.cadence(mode, fps).first
    private val maxEvery = DetectTuning.cadence(mode, fps).second
    /** Where the CPU's fine pass starts: fast mode at 640 px (on a trusted GPU it's at the frame's size anyway). */
    private val startCpuSide = if (mode == DetectMode.FAST) LOW_SIDE else RetinaFaceDetector.CPU_FINE_SIDE

    // Clear faces found in several detections in a row (see [FaceDetector]): not re-checked on
    // a crop. Updated as detections finish (workers finish out of order; close enough).
    private class Followed(var box: Box, var streak: Int, var seen: Int)
    private val followed = ArrayList<Followed>()
    @Volatile private var detectEvery = baseEvery
    @Volatile private var lite = false
    // The detectors' time on a sensitivity's extra work in this window (see [DetectTuning.blockedWithoutExtras]).
    private val extraUsWindow = AtomicLong()
    // The latest finished detection found a big face: the coarse pass, which fits those, then
    // runs on every detection (see [RetinaFaceDetector.detect]).
    @Volatile private var bigFace = false
    // The fine pass's long side on the CPU: lowered on a device too slow for it.
    @Volatile private var cpuSide = startCpuSide
    private var windowStart = 0L
    private var blockedMs = 0L

    /** How detection adapted to the device: "every frame", "every 2nd frame, lite"... */
    val pace: String get() = "every ${detectEvery}" + (if (lite) ", lite" else "")

    /**
     * Where the time goes, for whoever tunes this (the progress screen's stats panel): the pass's
     * totals so far (per frame on the video's thread, waits for a free worker, detections and
     * their parts) and how detection is set up now. Cheap: read with each preview.
     */
    fun snapshot(): StatsSnapshot {
        val g = gpu?.takeIf { it.active }
        return StatsSnapshot(
            atMs = SystemClock.elapsedRealtime(),
            frames = times.size,
            frameMs = frameMsTotal,
            waitMs = waitMsTotal,
            detections = detections.get(),
            detectMs = detectMsTotal.get(),
            fineUs = fineUsTotal.get(),
            coarseUs = coarseUsTotal.get(),
            gpuCallUs = gpuCallUsTotal.get(),
            yunetUs = yunetUsTotal.get(),
            cropUs = cropUsTotal.get(),
            crops = cropsTotal.get(),
            // All from the one read of the GPU above (as [accelerator]), so CPU and GPU fields don't mix.
            accelerator = when {
                g == null -> "cpu"
                g.trusted -> "gpu"
                else -> "gpu-checking"
            },
            // On the GPU: its precision and time per frame; on the CPU: why not the GPU.
            precision = if (g != null) (if (g.precise) "fp32" else "fp16") else if (selfie) "fp32" else half.state,
            gpuMs = g?.inferMs ?: 0f,
            gpuQueueMs = g?.queueMs ?: 0f,
            // (Selfie mode never uses the GPU or the fp16 passes: GpuFaceNet.unavailable is the last face-pack pass's.)
            noGpu = if (g != null) null else if (selfie) "selfie mode runs on the CPU" else (gpu?.why ?: GpuFaceNet.unavailable),
            side = if (g != null) maxSide else cpuSide,
            every = detectEvery,
            lite = lite,
            workers = BASE_WORKERS + extraSlots,
            maxWorkers = WORKERS,
            mode = mode.name.lowercase(),
            sensitivity = sensitivity,
        )
    }

    init {
        reset()
    }

    @Synchronized
    fun reset() {
        generation++
        drain()
        // Everything is free now: take the extra permits back so a new pass starts at the base.
        slots.acquireUninterruptibly(extraSlots)
        extraSlots = 0
        failure.set(null)
        lastDets = emptyList()
        synchronized(followed) { followed.clear() }
        camera.reset()
        cutDetector.reset()
        frames?.reset()
        times.clear()
        dets.clear()
        extraDets.clear()
        cuts.clear()
        detectEvery = baseEvery
        lite = false
        extraUsWindow.set(0)
        bigFace = false
        cpuSide = startCpuSide
        windowStart = 0L
        blockedMs = 0L
        detectMsTotal.set(0)
        detections.set(0)
        fineUsTotal.set(0)
        coarseUsTotal.set(0)
        gpuCallUsTotal.set(0)
        yunetUsTotal.set(0)
        cropUsTotal.set(0)
        cropsTotal.set(0)
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
            // More frames in flight only while a trusted GPU takes the fine pass off the CPU.
            val g = gpu
            val want = if (g != null && g.active && g.trusted) WORKERS - BASE_WORKERS else 0
            if (want > extraSlots) slots.release(want - extraSlots)
            else if (want < extraSlots) slots.acquireUninterruptibly(extraSlots - want)
            extraSlots = want
            val waitFrom = SystemClock.elapsedRealtime()
            slots.acquire()
            waited = SystemClock.elapsedRealtime() - waitFrom
            blockedMs += waited
            waitMsTotal += waited
            // Selfie mode's one light detector on every frame has nothing to adapt.
            if (!selfie) adapt(idx)
            val known = followedBoxes()
            // On a GPU, every other detection may leave out the coarse pass (it's the CPU's
            // biggest cost there) unless a big face is in view.
            val maySkipCoarse = g != null && g.active && !bigFace && (idx / detectEvery) % 2 == 1
            pool.execute {
                try {
                    if (gen == generation && selfie) {
                        val d0 = SystemClock.elapsedRealtime()
                        val det = blaze()
                        val r = det.detect(frame, sensitivity > 0f)
                        detectMsTotal.addAndGet(SystemClock.elapsedRealtime() - d0)
                        detections.incrementAndGet()
                        fineUsTotal.addAndGet(det.lastUs)
                        if (gen == generation) {
                            if (r.extras.isNotEmpty()) extraDets[idx] = r.extras
                            dets[idx] = r.faces
                            lastDets = r.faces
                        }
                    } else if (gen == generation) {
                        val d0 = SystemClock.elapsedRealtime()
                        val det = detector()
                        val found = det.detect(frame, lite, known, cpuSide, maySkipCoarse, sensitivity)
                        detectMsTotal.addAndGet(SystemClock.elapsedRealtime() - d0)
                        detections.incrementAndGet()
                        fineUsTotal.addAndGet(det.lastFineUs)
                        coarseUsTotal.addAndGet(det.lastCoarseUs)
                        gpuCallUsTotal.addAndGet(det.lastGpuCallUs)
                        yunetUsTotal.addAndGet(det.lastYunetUs)
                        cropUsTotal.addAndGet(det.lastCropUs)
                        cropsTotal.addAndGet(det.lastCrops)
                        extraUsWindow.addAndGet(det.lastExtraUs)
                        if (gen == generation) {
                            if (det.lastExtras.isNotEmpty()) extraDets[idx] = det.lastExtras
                            dets[idx] = found
                            lastDets = found
                            bigFace = found.any { RetinaFaceDetector.isBig(it.box, frame.cols(), frame.rows()) }
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
            val snap = snapshot()
            cb(bmp, lastDets, snap.accelerator, snap)
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
     * fine pass at 640 px first (about half the work); then less often (down to [DetectTuning.MIN_RATE];
     * fast mode stays at its 5 a second and only gets lighter).
     * Lighter also means crops only for uncertain faces and no YuNet.
     */
    private fun adapt(idx: Int) {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) {
            windowStart = now
            blockedMs = 0L
            extraUsWindow.set(0)
            return
        }
        val span = now - windowStart
        if (span < 1500 || idx < 20) return
        val g = gpu
        val gpuActive = g != null && g.active
        // Without a sensitivity's extra work: it never moves the pacing (see [DetectTuning.blockedWithoutExtras]).
        val waited = DetectTuning.blockedWithoutExtras(blockedMs, extraUsWindow.getAndSet(0) / 1000)
        val (next, step) = DetectTuning.adapt(
            Pacing(detectEvery, cpuSide, lite),
            blocked = waited > span / 3,
            relaxed = waited < span / 10,
            gpuActive = gpuActive,
            gpuSlow = gpuActive && g!!.inferMs > GPU_SLOW_MS,
            mode = mode,
            baseEvery = baseEvery,
            maxEvery = maxEvery,
            lowSide = LOW_SIDE,
            // The size detection started at (960 px; 640 in fast mode): the full checks come back there.
            fineSide = startCpuSide,
        )
        when (step) {
            // A GPU this slow (one lane, ~2-10 frames a second) holds back the CPU's cores:
            // they do better together, at the lighter size a device like this needs.
            PaceStep.GPU_OFF -> {
                g!!.disable("slow (${g.inferMs.roundToInt()} ms)")
                android.util.Log.i("ONIT", "the GPU takes ${g.inferMs.roundToInt()} ms a frame: CPU at $LOW_SIDE px from now on")
            }
            PaceStep.SIDE -> android.util.Log.i("ONIT", "face detection slower than the video: $LOW_SIDE px, lite")
            PaceStep.RATE -> android.util.Log.i("ONIT", "face detection slower than the video: every ${next.every} frames, lite")
            // Fast mode never goes under 5 a second: lighter checks only.
            PaceStep.LITE -> android.util.Log.i("ONIT", "fast face detection slower than the video: lite")
            // Detection keeps up easily now (the GPU's check is over, say): back toward the usual pace.
            PaceStep.RELAX, PaceStep.NONE -> Unit
        }
        detectEvery = next.every
        cpuSide = next.cpuSide
        lite = next.lite
        windowStart = now
        blockedMs = 0L
    }

    private fun blaze(): BlazeFaceDetector = workerBlaze.get() ?: BlazeFaceDetector(File(blazeFile)).also {
        workerBlaze.set(it)
        blazes += it
    }

    private fun detector(): FaceDetector = workerDetector.get() ?: FaceDetector(retinaPath, yunetPath, gpu, half).also {
        workerDetector.set(it)
        detectors += it
    }

    /** Waits until every frame handed to the workers is done. */
    private fun drain() {
        val n = BASE_WORKERS + 1 + extraSlots
        slots.acquireUninterruptibly(n)
        slots.release(n)
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
        var tracks = OfflineTracker(measuredFps, aspect, sensitive).run(n, dets, motion, cutList)
        if (sensitivity > 0f && extraDets.isNotEmpty()) {
            // A sensitivity above 0: the default tracks as they are, and step by step the faces
            // the lower thresholds add (see [DetectTuning.widen]).
            val gen = generation
            tracks = DetectTuning.widen(tracks, sensitivity, cancelled = { gen != generation || Thread.currentThread().isInterrupted }) { level ->
                val thr = DetectTuning.thresholds(level)
                val all = HashMap<Int, List<Det>>(dets)
                // Selfie mode's extras come from BlazeFace's fixed pool: each step takes those over its own threshold.
                val minScore = if (selfie) DetectTuning.selfieThreshold(level) else thr.cut
                for ((i, extra) in extraDets) {
                    val more = extra.filter { it.score >= minScore }
                    if (more.isNotEmpty()) all[i] = (all[i] ?: emptyList()) + more
                }
                OfflineTracker(measuredFps, aspect, sensitive, thr).run(n, all, motion, cutList)
            }
        }
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
        synchronized(blazes) {
            blazes.forEach { it.close() }
            blazes.clear()
        }
        gpu?.close()
        if (packHeld) com.elad.faceblur.models.ModelStore.get(appContext).release(pack)
        packHeld = false
        preview.release()
        frames?.close()
        frames = null
        camera.close()
        cutDetector.close()
        rotated.release()
        small.release()
    }

    companion object {
        /**
         * An analyzer on the whole face pack, checked now (or [com.elad.faceblur.models.ModelMissing]).
         * The pack stays in use until close(); if making the analyzer fails, it is let go.
         */
        operator fun invoke(context: Context, sensitive: Boolean, fps: Float): FaceAnalyzer =
            invoke(context, if (sensitive) DetectMode.EXTENDED else DetectMode.NORMAL, fps, 0f)

        /** As above, in [mode], at [sensitivity] (0..1, see [DetectTuning]). */
        operator fun invoke(context: Context, mode: DetectMode, fps: Float, sensitivity: Float): FaceAnalyzer {
            val store = com.elad.faceblur.models.ModelStore.get(context)
            val pack = if (mode == DetectMode.SELFIE) com.elad.faceblur.models.PackId.SELFIE else com.elad.faceblur.models.PackId.FACES
            val files = store.acquire(pack)
            try {
                return FaceAnalyzer(context, mode, fps, DetectTuning.quantize(sensitivity), files)
            } catch (t: Throwable) {
                store.release(pack)
                throw t
            }
        }

        private const val MOTION_SIDE = 640
        private const val PREVIEW_SIDE = 480
        private const val PREVIEW_MS = 200L

        // Detections per second of video, and the fewest a slow device goes down to: DetectTuning.cadence.
        /** The fine pass's long side on the CPU when the device can't keep up. */
        private const val LOW_SIDE = 640
        /** A GPU slower than this per frame, when detection holds the pass back, gives way to the CPU. */
        private const val GPU_SLOW_MS = 80f
        /** Detections in a row after which a clear face isn't re-checked on a crop. */
        private const val FOLLOWED_AFTER = 4

        /** Detection threads working while the CPU does the fine pass (measured on ARM: 3 keep the cores busiest). */
        private const val BASE_WORKERS = 3

        /**
         * The most detection threads, by the device's cores: used only while a trusted GPU does the
         * fine pass, as then the workers' CPU side (the coarse pass, crops, preparing inputs,
         * waiting for the one GPU lane) is what holds detection back; on the CPU more threads
         * would only fight over the big cores.
         */
        val WORKERS: Int = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 5 else if (it >= 6) 4 else 3 }

    }
}
