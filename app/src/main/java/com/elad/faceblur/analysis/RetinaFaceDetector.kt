package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Face box, normalized to the (upright) analysis frame: center, size, roll angle in radians
 * (image coordinates, y down). Sizes are along the face's own axes.
 */
class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)

class Det(val box: Box, val score: Float)

/**
 * RetinaFace with a MobileNetV2 backbone (yakhyo/retinaface-pytorch, MIT; trained on WIDER
 * FACE) through OpenCV DNN. Much better than YuNet on profiles, faces under helmets and
 * motion blur, and it scores clutter (rubble, gear, hands) low.
 *
 * A fine pass runs at the analysis size; a coarse pass at 480 px catches faces too big for
 * the largest anchors.
 */
class RetinaFaceDetector(modelPath: String) : Closeable {

    private val net: Net = Dnn.readNetFromONNX(modelPath)
    private val priorCache = HashMap<Long, FloatArray>()
    private val resized = Mat()
    private val outNames = listOf("loc", "conf", "landmarks")

    fun detect(bgr: Mat): List<Det> {
        val w = bgr.cols()
        val h = bgr.rows()
        val out = ArrayList<Det>()
        run(bgr, max(w, h), 0f, out)
        if (max(w, h) > COARSE_SIDE * 1.3f) run(bgr, COARSE_SIDE, COARSE_MIN_BOX, out)
        return nms(out, 0.45f)
    }

    /** @param side long side to run at; @param minBox min box side (px at that size) to keep. */
    private fun run(bgr: Mat, side: Int, minBox: Float, out: MutableList<Det>) {
        val w = bgr.cols()
        val h = bgr.rows()
        val s = min(1f, side.toFloat() / max(w, h))
        val iw = max(32, ((w * s / 32f).roundToInt()) * 32)
        val ih = max(32, ((h * s / 32f).roundToInt()) * 32)
        val input = if (iw == w && ih == h) bgr else {
            Imgproc.resize(bgr, resized, Size(iw.toDouble(), ih.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            resized
        }
        val blob = Dnn.blobFromImage(input, 1.0, Size(iw.toDouble(), ih.toDouble()), Scalar(104.0, 117.0, 123.0), false, false)
        net.setInput(blob)
        val outs = ArrayList<Mat>()
        net.forward(outs, outNames)
        blob.release()
        val loc = floats(outs[0])
        val conf = floats(outs[1])
        val lms = floats(outs[2])
        outs.forEach { it.release() }

        val pri = priors(ih, iw)
        val n = pri.size / 4
        if (loc.size < n * 4 || conf.size < n * 2 || lms.size < n * 10) return
        val raw = ArrayList<Det>()
        for (i in 0 until n) {
            val score = conf[i * 2 + 1]
            if (score < SCORE_THRESHOLD) continue
            val px = pri[i * 4]; val py = pri[i * 4 + 1]; val pw = pri[i * 4 + 2]; val ph = pri[i * 4 + 3]
            val cx = px + loc[i * 4] * 0.1f * pw
            val cy = py + loc[i * 4 + 1] * 0.1f * ph
            val bw = pw * exp(loc[i * 4 + 2] * 0.2f)
            val bh = ph * exp(loc[i * 4 + 3] * 0.2f)
            if (min(bw * iw, bh * ih) < minBox) continue
            // landmarks 0, 1: the two eyes (image-left, image-right); angle in pixel space
            val ex0 = (px + lms[i * 10] * 0.1f * pw) * w
            val ey0 = (py + lms[i * 10 + 1] * 0.1f * ph) * h
            val ex1 = (px + lms[i * 10 + 2] * 0.1f * pw) * w
            val ey1 = (py + lms[i * 10 + 3] * 0.1f * ph) * h
            var angle = atan2(ey1 - ey0, ex1 - ex0)
            if (abs(angle) > MAX_ROLL) angle = 0f
            raw += Det(Box(cx, cy, bw, bh, angle), score)
        }
        out += nmsIou(raw, 0.4f)
    }

    private fun floats(m: Mat): FloatArray {
        val total = m.total().toInt() * m.channels()
        val arr = FloatArray(total)
        val idx = IntArray(m.dims())
        m.get(idx, arr)
        return arr
    }

    /** RetinaFace anchors, order: level, row, column, size. */
    private fun priors(h: Int, w: Int): FloatArray = priorCache.getOrPut((h.toLong() shl 32) or w.toLong()) {
        val list = ArrayList<Float>()
        for (k in STEPS.indices) {
            val step = STEPS[k]
            val fh = ceil(h / step.toFloat()).toInt()
            val fw = ceil(w / step.toFloat()).toInt()
            for (i in 0 until fh) for (j in 0 until fw) for (ms in MIN_SIZES[k]) {
                list += (j + 0.5f) * step / w
                list += (i + 0.5f) * step / h
                list += ms.toFloat() / w
                list += ms.toFloat() / h
            }
        }
        list.toFloatArray()
    }

    override fun close() {
        resized.release()
    }

    companion object {
        const val MODEL = "retinaface_mv2.onnx"
        const val SCORE_THRESHOLD = 0.4f
        private const val COARSE_SIDE = 480
        private const val COARSE_MIN_BOX = 64f
        private const val MAX_ROLL = 1.31f // 75 degrees
        private val STEPS = intArrayOf(8, 16, 32)
        private val MIN_SIZES = arrayOf(intArrayOf(16, 32), intArrayOf(64, 128), intArrayOf(256, 512))

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

        private fun nmsIou(dets: List<Det>, thr: Float): List<Det> {
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
}
