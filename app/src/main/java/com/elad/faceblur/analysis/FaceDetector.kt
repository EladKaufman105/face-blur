package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max

/**
 * Detector ensemble, tuned on labeled frames of real rescue footage:
 *  - RetinaFace-MobileNetV2 is the main detector.
 *  - YuNet adds faces RetinaFace misses (and raises the score where both agree).
 *  - Every detection is re-checked on a centered 192 px crop: clutter (knees, gloves, walls)
 *    almost never survives that, real faces often do. The tracker uses it as evidence.
 */
class FaceDetector(retinaPath: String, private val yunetPath: String) : Closeable {

    private val retina = RetinaFaceDetector(retinaPath)
    private var yunet: FaceDetectorYN? = null
    private var yunetSize = Size(0.0, 0.0)
    private val faces = Mat()
    private val row = FloatArray(15)
    private val crop = Mat()

    fun detect(bgr: Mat): List<Det> {
        val w = bgr.cols()
        val h = bgr.rows()
        val rf = retina.detect(bgr, RF_THRESHOLD)
        val yn = runYunet(bgr, w, h)

        val fused = ArrayList<Det>()
        val usedYn = HashSet<Int>()
        for (d in rf) {
            var score = d.score
            yn.forEachIndexed { j, y ->
                if (Geometry.iou(d.box, y.box) > 0.3f) {
                    score = 1f - (1f - score) * (1f - y.score * 0.8f)
                    usedYn += j
                }
            }
            fused += Det(d.box, score)
        }
        yn.forEachIndexed { j, y ->
            if (j !in usedYn && y.score >= 0.6f && rf.none { Geometry.iou(y.box, it.box) > 0.3f }) {
                fused += Det(y.box, y.score * 0.85f)
            }
        }
        val kept = Geometry.nmsIou(fused, 0.4f)
        for (d in kept) d.verify = verify(bgr, d.box)
        return kept
    }

    private fun runYunet(bgr: Mat, w: Int, h: Int): List<Det> {
        val size = Size(w.toDouble(), h.toDouble())
        val net = yunet?.takeIf { yunetSize == size } ?: FaceDetectorYN.create(yunetPath, "", size, 0.4f, 0.3f, 5000).also {
            yunet = it
            yunetSize = size
        }
        net.detect(bgr, faces)
        val n = if (faces.empty()) 0 else faces.rows()
        val out = ArrayList<Det>(n)
        for (r in 0 until n) {
            faces.get(r, 0, row)
            val bw = row[2]
            val bh = row[3]
            if (bw <= 1f || bh <= 1f) continue
            out += Det(Box((row[0] + bw / 2) / w, (row[1] + bh / 2) / h, bw / w, bh / h), row[14])
        }
        return out
    }

    /** Re-detect on a square crop twice the box size, scaled to 192 px. */
    private fun verify(bgr: Mat, b: Box): Float {
        val w = bgr.cols()
        val h = bgr.rows()
        val cx = b.cx * w
        val cy = b.cy * h
        val r = max(b.w * w, b.h * h)
        val k = VERIFY_SIDE / (2f * r)
        val m = Mat(2, 3, org.opencv.core.CvType.CV_64F)
        m.put(0, 0, k.toDouble(), 0.0, (-(cx - r) * k).toDouble(), 0.0, k.toDouble(), (-(cy - r) * k).toDouble())
        Imgproc.warpAffine(
            bgr, crop, m, Size(VERIFY_SIDE.toDouble(), VERIFY_SIDE.toDouble()),
            if (k < 1f) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE,
        )
        m.release()
        var best = 0f
        val s = VERIFY_SIDE.toFloat()
        for (d in retina.detectPlain(crop, 0.05f)) {
            if (abs(d.box.cx * s - s / 2) < s * 0.25f && abs(d.box.cy * s - s / 2) < s * 0.25f && d.box.w * s > s * 0.2f) {
                best = max(best, d.score)
            }
        }
        return best
    }

    override fun close() {
        retina.close()
        faces.release()
        crop.release()
        yunet = null
    }

    companion object {
        const val YUNET_MODEL = "face_detection_yunet_2023mar.onnx"
        private const val RF_THRESHOLD = 0.35f
        private const val VERIFY_SIDE = 192
    }
}
