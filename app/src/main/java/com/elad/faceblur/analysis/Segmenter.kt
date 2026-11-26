package com.elad.faceblur.analysis

import android.content.Context
import android.util.Log
import com.elad.faceblur.models.ModelMissing
import com.elad.faceblur.models.ModelStore
import com.elad.faceblur.models.PackId
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds the people in each frame: PP-HumanSeg (PaddleSeg, Apache 2.0, from OpenCV Zoo) through
 * OpenCV. The model takes a 192 x 192 square, so a wide (or tall) frame is covered by three
 * overlapping square tiles, blended with feathered edges: people aren't squeezed and are seen
 * bigger.
 *
 * A second model, LR-ASPP (in the same "people" pack), looks at the whole frame too, and a pixel is a person's if either model says so. On hard footage (rubble, seen
 * from above) the two found mostly different people: together they covered nearly twice as much.
 *
 * The model looks at each frame on its own, so its masks shimmer from frame to frame even when
 * nothing moves. Each stored mask is therefore (within a shot) the per-pixel median of the
 * frame's mask and its two neighbors on each side, then eased into the previous stored mask
 * wherever the picture itself didn't change, and left to follow the new one where it did. On
 * real footage this cut the flicker in still shots by two thirds. A mask is stored two frames
 * late (once its neighbors are in); [finish] stores the last ones.
 *
 * @param times analysis frame times (µs), to file each mask under its frame.
 * @param cuts first frames of shots (smoothing doesn't cross a cut).
 */
class Segmenter(context: Context, private val times: LongArray, cuts: IntArray, private val store: MaskStore) : Closeable {
    private val net: Net
    private val cutSet = cuts.toHashSet()
    private var rgba = Mat()
    private val rotated = Mat()
    private val rgb = Mat()
    // Frames of the current shot still needed: the next one to store, two before it (for its
    // median) and the ones after it that are in already.
    private val pending = ArrayList<Pending>()
    private var next = 0
    private var lastIndex = -100
    // The last stored mask and the gray picture of its frame (for the motion test).
    private var outPrev: FloatArray? = null
    private var grayPrev: FloatArray? = null
    private val graySmall = Mat()
    private val grayBlur = Mat()
    private val m8 = Mat(MaskStore.SIZE, MaskStore.SIZE, CvType.CV_8U)
    private var bytes = ByteArray(0)
    private val probs = FloatArray(IN * IN)
    private val tileProb = Mat(IN, IN, CvType.CV_32F)
    private val tileBig = Mat()
    private var acc = Mat()
    private var wsum = Mat()
    // The people pack (both models), checked now, or ModelMissing; held until close().
    private val modelStore = ModelStore.get(context)
    private val pack = modelStore.acquire(PackId.PEOPLE)
    private var packHeld = true
    // The second model, loaded for the first frame's orientation (null: it failed to load).
    private var lr: Net? = null
    private var lrLandscape: Boolean? = null
    private val lrSmall = Mat()
    private val lrIn = Mat()
    private val lrBig = Mat()
    private var lrProb = Mat()
    private var lrOut = FloatArray(0)

    /** Which models looked at the frames (for the self-test's report). */
    val models get() = if (lr != null) "pphumanseg+lraspp" else "pphumanseg"

    /** Frames done. */
    @Volatile var done = 0
        private set

    init {
        OpenCv.ensureLoaded()
        net = Dnn.readNetFromONNX((pack[MODEL] ?: throw ModelMissing(PackId.PEOPLE)).path)
    }

    /** One frame from the grab pass: RGBA, top row first; [rotation] makes it upright. */
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
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
        val prob = people(rgb)
        val sz = Size(MaskStore.SIZE.toDouble(), MaskStore.SIZE.toDouble())
        val small = Mat()
        Imgproc.resize(prob, small, sz, 0.0, 0.0, Imgproc.INTER_AREA)
        val mask = FloatArray(N)
        small.get(0, 0, mask)
        small.release()
        Imgproc.cvtColor(src, graySmall, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.resize(graySmall, grayBlur, sz, 0.0, 0.0, Imgproc.INTER_AREA)
        // Blurred, so noise and fine texture don't count as motion.
        Imgproc.GaussianBlur(grayBlur, grayBlur, Size(0.0, 0.0), 3.0)
        val g8 = ByteArray(N)
        grayBlur.get(0, 0, g8)
        val gray = FloatArray(N) { (g8[it].toInt() and 0xFF).toFloat() }

        val idx = nearest(timeUs)
        // A new shot, a jump, or the pass starting over: what's waiting is stored first.
        if (idx != lastIndex + 1 || idx in cutSet) {
            flush()
            outPrev = null
            grayPrev = null
        }
        lastIndex = idx
        pending += Pending(idx, mask, gray)
        // A frame is stored once the two after it are in.
        while (next + HALF < pending.size) storeAt(next++)
        while (next > HALF) {
            pending.removeAt(0)
            next--
        }
        done++
    }

    private class Pending(val index: Int, val mask: FloatArray, val gray: FloatArray)

    /**
     * Stores the frame at [k] of [pending]: the median of its mask and its neighbors' (up to
     * two each side), eased into the previous stored mask where the picture didn't change.
     */
    private fun storeAt(k: Int) {
        val f = pending[k]
        val lo = maxOf(0, k - HALF)
        val hi = minOf(pending.size - 1, k + HALF)
        val n = hi - lo + 1
        val v = FloatArray(n)
        val med = FloatArray(N) { i ->
            for (j in 0 until n) v[j] = pending[lo + j].mask[i]
            median(v, n)
        }
        val prev = outPrev
        val gp = grayPrev
        val out = if (prev == null || gp == null) med else FloatArray(N) { i ->
            val a = (abs(f.gray[i] - gp[i]) / MOTION).coerceIn(0f, 1f)
            val keep = STILL_KEEP * (1f - a) + MOVING_KEEP * a
            med[i] * (1f - keep) + prev[i] * keep
        }
        val bytes = ByteArray(N) { (out[it] * 255f).toInt().coerceIn(0, 255).toByte() }
        m8.put(0, 0, bytes)
        store.put(f.index, m8)
        outPrev = out
        grayPrev = f.gray
    }

    /** Stores every frame still waiting (end of a shot or of the pass). */
    private fun flush() {
        while (next < pending.size) storeAt(next++)
        pending.clear()
        next = 0
    }

    /** Stores the masks still waiting: call when the pass is over. */
    @Synchronized
    fun finish() {
        flush()
    }

    private fun median(v: FloatArray, n: Int): Float {
        // n ≤ 5: insertion sort.
        for (i in 1 until n) {
            val x = v[i]
            var j = i - 1
            while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j-- }
            v[j + 1] = x
        }
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2f
    }

    /** Person probability over the whole frame (frame size, 0..1). */
    private fun people(img: Mat): Mat {
        val w = img.cols()
        val h = img.rows()
        val side = minOf(w, h)
        val long = maxOf(w, h)
        // Nearly square: one look at the whole frame.
        val tiles = if (long < side * 1.2) 1 else 3
        if (acc.rows() != h || acc.cols() != w) {
            acc.release(); wsum.release()
            acc = Mat(h, w, CvType.CV_32F)
            wsum = Mat(h, w, CvType.CV_32F)
        }
        if (tiles == 1) {
            infer(img, tileProb)
            Imgproc.resize(tileProb, acc, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            secondOpinion(img, acc)
            return acc
        }
        acc.setTo(Scalar(0.0))
        wsum.setTo(Scalar(0.0))
        val wide = w >= h
        val weights = tileWeights(side, wide, tiles)
        for (k in 0 until tiles) {
            val off = Math.round((long - side) * k / (tiles - 1).toFloat())
            val rect = if (wide) Rect(off, 0, side, side) else Rect(0, off, side, side)
            val tile = img.submat(rect)
            infer(tile, tileProb)
            tile.release()
            Imgproc.resize(tileProb, tileBig, Size(side.toDouble(), side.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val a = acc.submat(rect)
            val s = wsum.submat(rect)
            Core.multiply(tileBig, weights[k], tileBig)
            Core.add(a, tileBig, a)
            Core.add(s, weights[k], s)
            a.release(); s.release()
        }
        Core.max(wsum, Scalar(1e-6), wsum)
        Core.divide(acc, wsum, acc)
        secondOpinion(img, acc)
        return acc
    }

    /** The second model for this orientation (loaded once), or null. */
    private fun lrNet(landscape: Boolean): Net? {
        if (lrLandscape == landscape) return lr
        lr = null
        lrLandscape = landscape
        val file = pack[if (landscape) LR_LANDSCAPE else LR_PORTRAIT] ?: return null
        lr = try {
            Dnn.readNetFromONNX(file.path)
        } catch (t: Throwable) {
            Log.w("ONIT", "LR-ASPP didn't load: $t")
            null
        }
        return lr
    }

    /**
     * LR-ASPP over the whole frame [img] (RGB), letterboxed to its input; where it sees a person
     * more surely than [acc] (frame size), [acc] takes its value.
     */
    private fun secondOpinion(img: Mat, acc: Mat) {
        val w = img.cols()
        val h = img.rows()
        val landscape = w >= h
        val net = lrNet(landscape) ?: return
        val mw = if (landscape) LR_LONG else LR_SHORT
        val mh = if (landscape) LR_SHORT else LR_LONG
        try {
            val s = minOf(mw / w.toDouble(), mh / h.toDouble())
            val rw = (w * s).roundToInt().coerceIn(1, mw)
            val rh = (h * s).roundToInt().coerceIn(1, mh)
            val ox = (mw - rw) / 2
            val oy = (mh - rh) / 2
            Imgproc.resize(img, lrSmall, Size(rw.toDouble(), rh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            // Padding in the training mean: neutral to the model.
            Core.copyMakeBorder(lrSmall, lrIn, oy, mh - rh - oy, ox, mw - rw - ox, Core.BORDER_CONSTANT, Scalar(124.0, 116.0, 104.0))
            // RGB 0..1; the model normalizes itself.
            val blob = Dnn.blobFromImage(lrIn, 1.0 / 255.0, Size(mw.toDouble(), mh.toDouble()), Scalar(0.0, 0.0, 0.0), false, false)
            net.setInput(blob)
            val o = net.forward()
            if (lrOut.size != mw * mh) lrOut = FloatArray(mw * mh)
            o.get(intArrayOf(0, 0, 0, 0), lrOut)
            o.release()
            blob.release()
            if (lrProb.rows() != mh || lrProb.cols() != mw) {
                lrProb.release()
                lrProb = Mat(mh, mw, CvType.CV_32F)
            }
            lrProb.put(0, 0, lrOut)
            val inner = lrProb.submat(Rect(ox, oy, rw, rh))
            Imgproc.resize(inner, lrBig, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            inner.release()
            Core.max(acc, lrBig, acc)
        } catch (t: Throwable) {
            // Not worth failing the pass over: PP-HumanSeg alone from here.
            Log.w("ONIT", "LR-ASPP failed: $t")
            lr = null
        }
    }

    private var weightsKey = ""
    private var weightsCache: List<Mat> = emptyList()

    /**
     * Each tile's blend weights (made once per frame size): 1 in the middle, falling to 0 over
     * the outer quarter along the tiling axis, except at the frame's own edges.
     */
    private fun tileWeights(side: Int, wide: Boolean, tiles: Int): List<Mat> {
        val key = "$side $wide $tiles"
        if (key == weightsKey) return weightsCache
        weightsCache.forEach { it.release() }
        val ramp = FloatArray(side) { i -> (minOf(i + 1, side - i) / (side * 0.25f)).coerceAtMost(1f) }
        weightsCache = List(tiles) { k ->
            val v = FloatArray(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                val along = if (wide) x else y
                val r = when {
                    k == 0 && along < side / 2 -> 1f
                    k == tiles - 1 && along >= side / 2 -> 1f
                    else -> ramp[along]
                }
                v[y * side + x] = r
            }
            Mat(side, side, CvType.CV_32F).also { it.put(0, 0, v) }
        }
        weightsKey = key
        return weightsCache
    }

    /** The model on [img] (RGB): person probability, [IN] x [IN], into [out]. */
    private fun infer(img: Mat, out: Mat) {
        // (x / 255 - 0.5) / 0.5, as the model was trained.
        val blob = Dnn.blobFromImage(img, 1.0 / 127.5, Size(IN.toDouble(), IN.toDouble()), Scalar(127.5, 127.5, 127.5), false, false)
        net.setInput(blob)
        val o = net.forward()
        o.get(intArrayOf(0, 1, 0, 0), probs)
        o.release()
        blob.release()
        out.put(0, 0, probs)
    }

    private fun nearest(t: Long): Int {
        if (times.isEmpty()) return 0
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return if (abs(times[hi] - t) < abs(times[lo] - t)) hi else lo
    }

    @Synchronized
    override fun close() {
        if (packHeld) modelStore.release(PackId.PEOPLE)
        packHeld = false
        rgba.release()
        rotated.release()
        rgb.release()
        tileProb.release()
        tileBig.release()
        acc.release()
        wsum.release()
        weightsCache.forEach { it.release() }
        weightsCache = emptyList()
        lrSmall.release()
        lrIn.release()
        lrBig.release()
        lrProb.release()
        lr = null
        graySmall.release()
        grayBlur.release()
        m8.release()
        pending.clear()
    }

    companion object {
        const val MODEL = "human_segmentation_pphumanseg_2023mar.onnx"
        /** LR-ASPP MobileNetV3 (torchvision, BSD-3), one per frame orientation (made by ml/export_seg.py). */
        const val LR_LANDSCAPE = "lraspp_mbv3_512x288_fp16.onnx"
        const val LR_PORTRAIT = "lraspp_mbv3_288x512_fp16.onnx"
        /** The model's input side. */
        private const val IN = 192
        /** Long side of the frame the tiles are cut from. */
        const val GRAB_LONG = 576
        /** LR-ASPP's input: 512 x 288 for wide frames, 288 x 512 for tall ones. */
        private const val LR_LONG = 512
        private const val LR_SHORT = 288

        private const val N = MaskStore.SIZE * MaskStore.SIZE
        /** Neighbors on each side in the median. */
        private const val HALF = 2
        /** Gray-level change (blurred picture) that counts as fully moved. */
        private const val MOTION = 6f
        /** How much of the previous mask is kept where the picture is still / moved. */
        private const val STILL_KEEP = 0.95f
        private const val MOVING_KEEP = 0.3f
    }
}
