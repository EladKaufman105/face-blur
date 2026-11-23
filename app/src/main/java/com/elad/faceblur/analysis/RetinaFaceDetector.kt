package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Face box, normalized to the (upright) analysis frame: center and size. [angle] is kept for
 * the renderer but is always 0: an axis-aligned ellipse around the box covers tilted and lying
 * faces better than a rotated one fitted from noisy landmarks.
 */
class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float = 0f)

/** @param verify score of the same face re-detected on a centered, upscaled crop (0 if not). */
class Det(val box: Box, val score: Float, var verify: Float = 0f)

/**
 * RetinaFace with a MobileNetV2 backbone (yakhyo/retinaface-pytorch, MIT; trained on WIDER
 * FACE) through OpenCV DNN.
 */
class RetinaFaceDetector(modelPath: String, private val gpu: GpuFaceNet? = null, private val half: HalfPrecision? = null) : Closeable {

    private val net: Net = Dnn.readNetFromONNX(modelPath)
    // The same model on OpenCV's fp16 CPU kernels (see [HalfPrecision]).
    private val net16: Net? = half?.let {
        try {
            Dnn.readNetFromONNX(modelPath).apply { setPreferableTarget(Dnn.DNN_TARGET_CPU_FP16) }
        } catch (t: Throwable) {
            android.util.Log.w("ONIT", "no fp16 face detection: $t")
            it.disable()
            null
        }
    }
    private val resized = Mat()
    // OpenCV's JNI layer calls java.util.ArrayList methods on this: must be a real ArrayList.
    private val outNames = arrayListOf("loc", "conf", "landmarks")

    // Where the last [detect] call spent its time, in microseconds, for whoever tunes the pace
    // (read by the same worker thread right after the call; each worker has its own detector).
    /** The fine pass, GPU or CPU, with its resize and decoding. */
    var lastFineUs = 0L
        private set
    /** The coarse 480 px pass (0 when it was skipped). */
    var lastCoarseUs = 0L
        private set
    /** Of the passes' time, what went into calls to the GPU: its input prep, the wait for its thread and the inference. */
    var lastGpuCallUs = 0L
        private set

    /**
     * Fine pass at the frame's size (on the GPU) or at most [CPU_FINE_SIDE] (on the CPU, where
     * it's most of the time) + coarse pass at 480 px for faces bigger than the anchors. The two
     * passes overlap; the caller's NMS merges them. How long each took is left in [lastFineUs],
     * [lastCoarseUs] and [lastGpuCallUs].
     *
     * @param coarseMaySkip this detection may leave out the coarse pass when the fine pass ran on
     *   the GPU, found a face, and none of the faces is big (see [isBig]): the caller says so on
     *   every other detection, and not while a big face was seen in the one before. A big face
     *   is the one thing only the coarse pass is for, and the tracker fills the one detection
     *   in between. Never skipped on the CPU, where the fine pass is at most 960 px.
     */
    fun detect(bgr: Mat, threshold: Float, cpuSide: Int = CPU_FINE_SIDE, coarseMaySkip: Boolean = false): List<Det> {
        val w = bgr.cols()
        val h = bgr.rows()
        val out = ArrayList<Det>()
        val iw = max(32, (w / 32f).roundToInt() * 32)
        val ih = max(32, (h / 32f).roundToInt() * 32)
        val fine = if (gpu?.supports(iw, ih) == true) max(w, h) else min(max(w, h), cpuSide)
        lastGpuCallUs = 0L
        val t0 = System.nanoTime()
        run(bgr, fine, threshold, 0f, out)
        val t1 = System.nanoTime()
        lastFineUs = (t1 - t0) / 1000
        lastCoarseUs = 0L
        // Below ~800 px the fine pass's biggest anchors (512 px) already cover the biggest faces.
        val onGpu = fine == max(w, h) && gpu?.supports(iw, ih) == true
        val skip = coarseMaySkip && onGpu && out.isNotEmpty() && out.none { isBig(it.box, w, h) }
        if (!skip && fine > 800 && max(w, h) > COARSE_SIDE * 1.3f) {
            run(bgr, COARSE_SIDE, threshold, COARSE_MIN_BOX, out)
            lastCoarseUs = (System.nanoTime() - t1) / 1000
        }
        return out
    }

    /** Single pass on an image as is (used on verification crops). */
    fun detectPlain(img: Mat, threshold: Float): List<Det> {
        val out = ArrayList<Det>()
        run(img, max(img.cols(), img.rows()), threshold, 0f, out)
        return out
    }

    /** @param side long side to run at; @param minBox min box side (px at that size) to keep. */
    private fun run(bgr: Mat, side: Int, threshold: Float, minBox: Float, out: MutableList<Det>) {
        val w = bgr.cols()
        val h = bgr.rows()
        val s = min(1f, side.toFloat() / max(w, h))
        val iw = max(32, ((w * s / 32f).roundToInt()) * 32)
        val ih = max(32, ((h * s / 32f).roundToInt()) * 32)
        val input = if (iw == w && ih == h) bgr else {
            Imgproc.resize(bgr, resized, Size(iw.toDouble(), ih.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            resized
        }
        val (loc, conf) = forward(input, iw, ih, threshold, minBox)
        out += decode(loc, conf, iw, ih, threshold, minBox)
    }

    /** The model's anchor offsets and scores for [input] ([iw] x [ih]): on the GPU when it can, else OpenCV. */
    private fun forward(input: Mat, iw: Int, ih: Int, threshold: Float, minBox: Float): Pair<FloatArray, FloatArray> {
        val g = gpu
        if (g != null && g.supports(iw, ih)) {
            val g0 = System.nanoTime()
            val r = g.run(input, iw, ih)
            lastGpuCallUs += (System.nanoTime() - g0) / 1000
            if (r != null) {
                if (g.trusted) return r
                // Not trusted yet: also on the CPU (fp32), and compare what each finds.
                val ref = cpuForward(net, input, iw, ih)
                val a = decode(r.first, r.second, iw, ih, threshold, minBox)
                val b = decode(ref.first, ref.second, iw, ih, threshold, minBox)
                if (agree(a, b) && scoresAgree(r.second, ref.second)) {
                    g.vouch()
                } else {
                    android.util.Log.w("ONIT", "GPU faces differ from the CPU's (${a.size} vs ${b.size})${if (g.precise) "" else " in half precision"}")
                    g.strike()
                }
                return ref
            }
        }
        val h = half
        val n16 = net16
        if (h != null && n16 != null && h.active) {
            if (h.trusted) return cpuForward(n16, input, iw, ih)
            // Not trusted yet: both ways, compared; fp32's answer is used meanwhile.
            val r = try {
                cpuForward(n16, input, iw, ih)
            } catch (t: Throwable) {
                android.util.Log.w("ONIT", "fp16 face detection failed: $t")
                h.disable()
                null
            }
            val ref = cpuForward(net, input, iw, ih)
            if (r != null) {
                val a = decode(r.first, r.second, iw, ih, threshold, minBox)
                val b = decode(ref.first, ref.second, iw, ih, threshold, minBox)
                if (agree(a, b) && scoresAgree(r.second, ref.second)) {
                    h.vouch()
                } else {
                    android.util.Log.w("ONIT", "fp16 faces differ from fp32's (${a.size} vs ${b.size})")
                    h.strike()
                }
            }
            return ref
        }
        return cpuForward(net, input, iw, ih)
    }

    private fun cpuForward(net: Net, input: Mat, iw: Int, ih: Int): Pair<FloatArray, FloatArray> {
        val blob = Dnn.blobFromImage(input, 1.0, Size(iw.toDouble(), ih.toDouble()), Scalar(104.0, 117.0, 123.0), false, false)
        net.setInput(blob)
        val outs = ArrayList<Mat>()
        net.forward(outs, outNames)
        blob.release()
        val loc = floats(outs[0])
        val conf = floats(outs[1])
        outs.forEach { it.release() }
        return loc to conf
    }

    /**
     * The two runs' face scores, anchor by anchor, are about the same: every anchor either run
     * gives some chance of a face, or all of them when neither does. Unlike the faces alone this
     * says something on every frame: a close-up whose one big face only the coarse pass finds
     * left the fine pass with no faces to compare, and the GPU (and fp16) never got trusted,
     * running the CPU's fp32 pass beside it to the end.
     */
    private fun scoresAgree(x: FloatArray, y: FloatArray): Boolean {
        val n = minOf(x.size, y.size) / 2
        if (n == 0) return false
        var count = 0
        var sum = 0f
        var worst = 0f
        var all = 0f
        for (i in 0 until n) {
            val a = x[2 * i + 1]
            val b = y[2 * i + 1]
            val d = kotlin.math.abs(a - b)
            all += d
            if (a >= 0.05f || b >= 0.05f) {
                count++
                sum += d
                if (d > worst) worst = d
            }
        }
        return if (count == 0) all / n < 0.01f else worst < 0.25f && sum / count < 0.06f
    }

    /**
     * The same clear faces (by place) with about the same scores. Faces near the threshold
     * aren't compared: in a crowd one of them is easily just over in one precision and just
     * under in the other, which says nothing about the GPU being wrong.
     */
    private fun agree(a: List<Det>, b: List<Det>): Boolean {
        fun covered(clear: List<Det>, other: List<Det>) = clear.filter { it.score >= AGREE_CLEAR }.all { d ->
            other.any { Geometry.iou(it.box, d.box) > 0.6f && kotlin.math.abs(it.score - d.score) < 0.15f }
        }
        return covered(a, b) && covered(b, a)
    }

    /** Boxes above [threshold] (and [minBox] px at the model's size), after NMS. */
    private fun decode(loc: FloatArray, conf: FloatArray, iw: Int, ih: Int, threshold: Float, minBox: Float): List<Det> {
        val pri = priors(ih, iw)
        val n = pri.size / 4
        if (loc.size < n * 4 || conf.size < n * 2) return emptyList()
        val raw = ArrayList<Det>()
        // Most of the ~38,000 anchors score far below the threshold: they cost one comparison
        // each, and only the few above it get a box (the landmarks aren't decoded at all).
        for (i in 0 until n) {
            val score = conf[i * 2 + 1]
            if (score < threshold) continue
            val o = i * 4
            val pw = pri[o + 2]; val ph = pri[o + 3]
            val bw = pw * exp(loc[o + 2] * 0.2f)
            val bh = ph * exp(loc[o + 3] * 0.2f)
            if (min(bw * iw, bh * ih) < minBox) continue
            val cx = pri[o] + loc[o] * 0.1f * pw
            val cy = pri[o + 1] + loc[o + 1] * 0.1f * ph
            raw += Det(Box(cx, cy, bw, bh), score)
        }
        return Geometry.nmsIou(raw, 0.4f)
    }

    private fun floats(m: Mat): FloatArray {
        val total = m.total().toInt() * m.channels()
        val arr = FloatArray(total)
        val idx = IntArray(m.dims())
        m.get(idx, arr)
        return arr
    }

    /**
     * RetinaFace anchors (cx, cy, w, h each, normalized), order: level, row, column, size. Static
     * per input size, so computed once for all the workers' detectors.
     */
    private fun priors(h: Int, w: Int): FloatArray = PRIORS.getOrPut((h.toLong() shl 32) or w.toLong()) {
        var count = 0
        for (k in STEPS.indices) count += ceil(h / STEPS[k].toFloat()).toInt() * ceil(w / STEPS[k].toFloat()).toInt() * MIN_SIZES[k].size
        val out = FloatArray(count * 4)
        var o = 0
        for (k in STEPS.indices) {
            val step = STEPS[k]
            val fh = ceil(h / step.toFloat()).toInt()
            val fw = ceil(w / step.toFloat()).toInt()
            for (i in 0 until fh) for (j in 0 until fw) for (ms in MIN_SIZES[k]) {
                out[o++] = (j + 0.5f) * step / w
                out[o++] = (i + 0.5f) * step / h
                out[o++] = ms.toFloat() / w
                out[o++] = ms.toFloat() / h
            }
        }
        out
    }

    override fun close() {
        resized.release()
    }

    companion object {
        const val MODEL = "retinaface_mv2.onnx"
        private const val COARSE_SIDE = 480
        /** The fine pass's long side on the CPU (the GPU runs it at the frame's 1280). */
        const val CPU_FINE_SIDE = 960
        /** Faces at least this sure are compared between GPU and CPU, fp16 and fp32. */
        private const val AGREE_CLEAR = 0.6f
        private const val COARSE_MIN_BOX = 64f
        /** A face whose long side is over this many px in a 1280 px frame is "big": only the coarse pass fits it well. */
        private const val BIG_FACE_PX = 300f
        // Shared by every detector (the values never change once computed).
        private val PRIORS = java.util.concurrent.ConcurrentHashMap<Long, FloatArray>()

        /** Whether [b], in a [w] x [h] frame, is a big face (see [BIG_FACE_PX]). */
        fun isBig(b: Box, w: Int, h: Int): Boolean = max(b.w * w, b.h * h) > BIG_FACE_PX * max(w, h) / 1280f
        private val STEPS = intArrayOf(8, 16, 32)
        private val MIN_SIZES = arrayOf(intArrayOf(16, 32), intArrayOf(64, 128), intArrayOf(256, 512))
    }
}

object Geometry {
    fun iou(a: Box, b: Box): Float {
        val iw = min(a.cx + a.w / 2, b.cx + b.w / 2) - max(a.cx - a.w / 2, b.cx - b.w / 2)
        val ih = min(a.cy + a.h / 2, b.cy + b.h / 2) - max(a.cy - a.h / 2, b.cy - b.h / 2)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.w * a.h + b.w * b.h - inter)
    }

    /** max(IoU, intersection / smaller area): also catches a box nested in another. */
    fun overlap(a: Box, b: Box): Float {
        val iw = min(a.cx + a.w / 2, b.cx + b.w / 2) - max(a.cx - a.w / 2, b.cx - b.w / 2)
        val ih = min(a.cy + a.h / 2, b.cy + b.h / 2) - max(a.cy - a.h / 2, b.cy - b.h / 2)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return max(inter / (a.w * a.h + b.w * b.h - inter), inter / min(a.w * a.h, b.w * b.h))
    }

    fun nmsIou(dets: List<Det>, thr: Float): List<Det> {
        val keep = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (keep.all { iou(it.box, d.box) < thr }) keep += d
        }
        return keep
    }

    fun nms(dets: List<Det>, thr: Float): List<Det> {
        val keep = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (keep.all { overlap(it.box, d.box) < thr }) keep += d
        }
        return keep
    }
}
