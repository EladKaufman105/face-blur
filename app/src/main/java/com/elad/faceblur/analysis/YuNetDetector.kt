package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.atan2
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
 * YuNet (OpenCV Zoo, MIT) run at several scales: the full analysis frame finds small and
 * distant faces, the downscaled passes find faces that fill the frame.
 */
class YuNetDetector(private val modelPath: String) : Closeable {

    private val nets = HashMap<Long, FaceDetectorYN>()
    private val scaled = Mat()
    private val faces = Mat()
    private val row = FloatArray(15)

    fun detect(bgr: Mat): List<Det> {
        val w = bgr.cols()
        val h = bgr.rows()
        val out = ArrayList<Det>()
        run(bgr, w, h, 1f, 0f, out)
        val longSide = max(w, h)
        for (side in EXTRA_SIDES) {
            if (longSide <= side * 1.3f) continue
            val s = side.toFloat() / longSide
            val sw = (w * s).roundToInt()
            val sh = (h * s).roundToInt()
            Imgproc.resize(bgr, scaled, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            // Coarse passes exist for faces too big for the full pass; small boxes there are
            // unreliable (blurry blobs, hands, textures) and duplicate the full pass anyway.
            run(scaled, w, h, 1f / s, COARSE_MIN_BOX, out)
        }
        return nms(out, 0.45f)
    }

    private fun run(img: Mat, fullW: Int, fullH: Int, k: Float, minBox: Float, out: MutableList<Det>) {
        val iw = img.cols()
        val ih = img.rows()
        val key = (iw.toLong() shl 32) or ih.toLong()
        val net = nets.getOrPut(key) {
            FaceDetectorYN.create(modelPath, "", Size(iw.toDouble(), ih.toDouble()), SCORE_THRESHOLD, 0.3f, 5000)
        }
        net.detect(img, faces)
        val n = if (faces.empty()) 0 else faces.rows()
        for (r in 0 until n) {
            faces.get(r, 0, row)
            if (min(row[2], row[3]) < minBox) continue
            val x = row[0] * k
            val y = row[1] * k
            val bw = row[2] * k
            val bh = row[3] * k
            if (bw <= 1f || bh <= 1f) continue
            // landmarks 4..7: right eye, left eye (subject's), image coordinates
            var angle = atan2(row[7] - row[5], row[6] - row[4])
            if (abs(angle) > MAX_ROLL) angle = 0f
            out += Det(
                Box((x + bw / 2) / fullW, (y + bh / 2) / fullH, bw / fullW, bh / fullH, angle),
                row[14],
            )
        }
    }

    override fun close() {
        nets.clear()
        scaled.release()
        faces.release()
    }

    companion object {
        const val SCORE_THRESHOLD = 0.45f
        private const val COARSE_MIN_BOX = 40f
        private val EXTRA_SIDES = intArrayOf(640, 320)
        private const val MAX_ROLL = 1.31f // 75 degrees

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

        fun nms(dets: List<Det>, thr: Float): List<Det> {
            val keep = ArrayList<Det>()
            for (d in dets.sortedByDescending { it.score }) {
                if (keep.all { overlap(it.box, d.box) < thr }) keep += d
            }
            return keep
        }
    }
}
