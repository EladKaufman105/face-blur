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
 *  - YuNet adds faces RetinaFace misses (and raises the score where both agree); it runs at
 *    640 px at most, for the faces RetinaFace's fine pass is weakest on (big, tilted, close).
 *  - Detections are re-checked on a centered 192 px crop: clutter (knees, gloves, walls)
 *    almost never survives that, real faces often do. The tracker uses it as evidence, and only
 *    where a face is uncertain or new: a clear face that has been followed for several
 *    detections already has all the evidence it needs, so it isn't checked again.
 */
class FaceDetector(retinaPath: String, private val yunetPath: String, gpu: GpuFaceNet? = null, half: HalfPrecision? = null) : Closeable {

    private val retina = RetinaFaceDetector(retinaPath, gpu, half)
    private var yunet: FaceDetectorYN? = null
    private var yunetSize = Size(0.0, 0.0)
    // YuNet at the lower pool threshold, for a sensitivity above 0 (made only then).
    private var yunetPool: FaceDetectorYN? = null
    private var yunetPoolSize = Size(0.0, 0.0)
    private val faces = Mat()
    private val row = FloatArray(15)
    private val crop = Mat()
    private val yunetInput = Mat()

    // Where the last [detect] call spent its time, in microseconds (read by the same worker
    // thread right after the call).
    var lastFineUs = 0L
        private set
    var lastCoarseUs = 0L
        private set
    /** Of the fine and coarse passes, the time in calls to the GPU (prep, wait for its thread, inference). */
    var lastGpuCallUs = 0L
        private set
    var lastYunetUs = 0L
        private set
    var lastCropUs = 0L
        private set
    var lastCrops = 0
        private set
    /** The last [detect] call's extra detections, for a sensitivity above 0 (see [DetectTuning]); else empty. */
    var lastExtras: List<Det> = emptyList()
        private set

    /**
     * @param lite a slow device: no YuNet, and crops only for uncertain faces.
     * @param followed boxes of clear faces already found in several detections in a row: a clear
     *   face there isn't re-checked.
     * @param cpuSide the fine pass's long side when it runs on the CPU.
     * @param coarseMaySkip the coarse pass may be left out when the GPU's fine pass found only
     *   small faces (see [RetinaFaceDetector.detect]).
     * @param sensitivity 0..1 (see [DetectTuning]): above 0, the faces that only lower thresholds
     *   find are left in [lastExtras] (re-checked on a crop too). What is returned is the same
     *   whatever it is.
     *
     * Afterwards the time each part took is in the `last...` properties.
     */
    fun detect(
        bgr: Mat,
        lite: Boolean = false,
        followed: List<Box> = emptyList(),
        cpuSide: Int = RetinaFaceDetector.CPU_FINE_SIDE,
        coarseMaySkip: Boolean = false,
        sensitivity: Float = 0f,
    ): List<Det> {
        val w = bgr.cols()
        val h = bgr.rows()
        val more = sensitivity > 0f
        val rfPool = if (more) ArrayList<Det>() else null
        val rf = retina.detect(bgr, RF_THRESHOLD, cpuSide, coarseMaySkip, DetectTuning.POOL_RF, rfPool)
        lastFineUs = retina.lastFineUs
        lastCoarseUs = retina.lastCoarseUs
        lastGpuCallUs = retina.lastGpuCallUs
        val y0 = System.nanoTime()
        val yn = if (lite) emptyList() else runYunet(bgr, w, h)
        val ynPool = if (!more || lite) emptyList() else runYunet(bgr, w, h, pool = true)
        lastYunetUs = (System.nanoTime() - y0) / 1000

        val kept = DetectTuning.fuse(rf, yn)
        var cropUs = 0L
        var crops = 0
        for (d in kept) {
            val clear = d.score >= CLEAR
            if (lite && clear) continue
            if (clear && followed.any { Geometry.iou(it, d.box) > 0.3f }) continue
            val c0 = System.nanoTime()
            d.verify = verify(bgr, d.box)
            cropUs += (System.nanoTime() - c0) / 1000
            crops++
        }
        // A sensitivity above 0: the pool's faces that aren't one of those, from the cut up, re-checked too (the likeliest first).
        lastExtras = if (rfPool == null) emptyList() else {
            val pool = DetectTuning.fuse(rfPool, ynPool, DetectTuning.POOL_YUNET_ONLY)
            DetectTuning.extras(kept, pool, DetectTuning.thresholds(sensitivity).cut).also { extra ->
                for (d in extra.sortedByDescending { it.score }.take(DetectTuning.MAX_EXTRA_CHECKS)) {
                    val c0 = System.nanoTime()
                    d.verify = verify(bgr, d.box)
                    cropUs += (System.nanoTime() - c0) / 1000
                    crops++
                }
            }
        }
        lastCropUs = cropUs
        lastCrops = crops
        return kept
    }

    private fun runYunet(bgr: Mat, fw: Int, fh: Int, pool: Boolean = false): List<Det> {
        val s = minOf(1f, YUNET_SIDE.toFloat() / max(fw, fh))
        val w = max(16, (fw * s).toInt())
        val h = max(16, (fh * s).toInt())
        val input = if (s < 1f) {
            Imgproc.resize(bgr, yunetInput, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            yunetInput
        } else bgr
        val size = Size(w.toDouble(), h.toDouble())
        val net = if (pool) {
            yunetPool?.takeIf { yunetPoolSize == size } ?: FaceDetectorYN.create(yunetPath, "", size, DetectTuning.POOL_YUNET, 0.3f, 5000).also {
                yunetPool = it
                yunetPoolSize = size
            }
        } else {
            yunet?.takeIf { yunetSize == size } ?: FaceDetectorYN.create(yunetPath, "", size, 0.4f, 0.3f, 5000).also {
                yunet = it
                yunetSize = size
            }
        }
        net.detect(input, faces)
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
        yunetInput.release()
        yunet = null
        yunetPool = null
    }

    companion object {
        const val YUNET_MODEL = "face_detection_yunet_2023mar.onnx"
        private const val RF_THRESHOLD = 0.35f
        private const val VERIFY_SIDE = 192
        /** A face this sure counts as strong evidence by itself (see [OfflineTracker]). */
        const val CLEAR = 0.8f
        private const val YUNET_SIDE = 640
    }
}
