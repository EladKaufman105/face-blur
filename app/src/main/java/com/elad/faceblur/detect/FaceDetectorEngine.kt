package com.elad.faceblur.detect

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device face detection (ML Kit, bundled model). Blocking: called from the video
 * pipeline's GL thread, never from the main thread.
 *
 * Sensitive mode adds a 2x2 grid of overlapping high-res tiles on top of the full-frame
 * pass, so small / distant faces (crowds, wide shots) are found too.
 */
class FaceDetectorEngine(private val sensitive: Boolean) : Closeable {

    private val fullDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(if (sensitive) 0.03f else 0.05f)
            .build()
    )

    /**
     * @param frame upright-or-not RGBA frame read back from the pipeline.
     * @param rotation clockwise degrees needed to make [frame] upright (0 when the pipeline
     *   already delivers display-oriented frames).
     * @return face boxes, normalized to [frame] (top-left origin), in the frame's own orientation.
     */
    fun detect(frame: Bitmap, rotation: Int): List<RectF> {
        val upright = if (rotation == 0) frame else rotate(frame, rotation)
        val w = upright.width
        val h = upright.height
        val found = ArrayList<RectF>()

        if (!sensitive) {
            run(upright, 0f, 0f, 1f, found)
        } else {
            val longSide = max(w, h)
            if (longSide > FULL_PASS_SIDE) {
                val s = FULL_PASS_SIDE.toFloat() / longSide
                val small = Bitmap.createScaledBitmap(upright, (w * s).roundToInt(), (h * s).roundToInt(), true)
                run(small, 0f, 0f, 1f / s, found)
                small.recycle()
            } else {
                run(upright, 0f, 0f, 1f, found)
            }
            val tw = (w * TILE_FRACTION).roundToInt()
            val th = (h * TILE_FRACTION).roundToInt()
            for (ty in 0..1) for (tx in 0..1) {
                val x0 = if (tx == 0) 0 else w - tw
                val y0 = if (ty == 0) 0 else h - th
                val tile = Bitmap.createBitmap(upright, x0, y0, tw, th)
                run(tile, x0.toFloat(), y0.toFloat(), 1f, found)
                tile.recycle()
            }
        }
        if (upright !== frame) upright.recycle()

        return merge(found).map { r ->
            val n = RectF(r.left / w, r.top / h, r.right / w, r.bottom / h)
            unrotate(n, rotation)
        }
    }

    private fun run(bitmap: Bitmap, ox: Float, oy: Float, scale: Float, out: MutableList<RectF>) {
        val faces = Tasks.await(fullDetector.process(InputImage.fromBitmap(bitmap, 0)))
        for (face in faces) {
            val b = face.boundingBox
            out += RectF(ox + b.left * scale, oy + b.top * scale, ox + b.right * scale, oy + b.bottom * scale)
        }
    }

    override fun close() {
        fullDetector.close()
    }

    companion object {
        private const val FULL_PASS_SIDE = 960
        private const val TILE_FRACTION = 0.6f

        private fun rotate(src: Bitmap, degrees: Int): Bitmap {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        /** Maps a normalized rect from the upright image back to the un-rotated frame. */
        private fun unrotate(r: RectF, rotation: Int): RectF {
            fun map(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
                90 -> v to (1f - u)
                180 -> (1f - u) to (1f - v)
                270 -> (1f - v) to u
                else -> u to v
            }
            val (x1, y1) = map(r.left, r.top)
            val (x2, y2) = map(r.right, r.bottom)
            return RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        }

        /** Merges duplicates (full pass vs. tiles, overlapping tiles) into their union. */
        private fun merge(rects: List<RectF>): List<RectF> {
            val sorted = rects.sortedByDescending { it.width() * it.height() }
            val out = ArrayList<RectF>()
            for (r in sorted) {
                val hit = out.firstOrNull { overlapRatio(it, r) > 0.45f }
                if (hit == null) out += RectF(r) else hit.union(r)
            }
            return out
        }

        private fun overlapRatio(a: RectF, b: RectF): Float {
            val ix = min(a.right, b.right) - max(a.left, b.left)
            val iy = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (ix <= 0f || iy <= 0f) return 0f
            val inter = ix * iy
            val smaller = min(a.width() * a.height(), b.width() * b.height())
            return if (smaller <= 0f) 0f else inter / smaller
        }
    }
}
