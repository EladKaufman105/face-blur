package com.elad.faceblur.edit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.elad.faceblur.analysis.Box
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Small pictures of the faces, for the list where each face's blur is turned on and off. */
object FaceThumbs {
    /** The frame to show a blur's face from: the middle of the frames it has a position for. */
    fun frameOf(t: EditTrack): Int {
        val keys = t.path.keys.filter { it in t.start..t.end }.ifEmpty { t.path.keys.toList() }
        return if (keys.isEmpty()) t.start else keys[keys.size / 2]
    }

    /**
     * The square (left, top, size in pixels) cut from a [w] x [h] frame around [box] (centre and
     * size as fractions of the frame), with some room around the face, kept inside the frame.
     */
    fun crop(box: Box, w: Int, h: Int, room: Float = 1.7f): IntArray {
        val side = (max(box.w * w, box.h * h) * room).roundToInt().coerceIn(1, min(w, h))
        val left = (box.cx * w - side / 2f).roundToInt().coerceIn(0, w - side)
        val top = (box.cy * h - side / 2f).roundToInt().coerceIn(0, h - side)
        return intArrayOf(left, top, side)
    }

    /** What a picture needs of one blur: where its face is and when. Read on the main thread, used on another. */
    class Spec(val id: Int, val box: Box, val timeUs: Long)

    /** [Spec]s for the blurs [ids], taken from [model] as it is now. */
    fun specs(model: EditModel, ids: List<Int>): List<Spec> = ids.mapNotNull { id ->
        val t = model.track(id) ?: return@mapNotNull null
        val f = frameOf(t)
        val box = model.rawBox(t, f) ?: return@mapNotNull null
        val us = model.times.getOrNull(f)?.coerceAtLeast(0L) ?: return@mapNotNull null
        Spec(id, box, us)
    }

    /** One picture per blur (by id), cut from the video's own frames; a blur whose frame can't be read has none. Stops when [isActive] turns false. */
    fun load(context: Context, uri: Uri, specs: List<Spec>, size: Int = 160, isActive: () -> Boolean = { true }): Map<Int, Bitmap> {
        val out = HashMap<Int, Bitmap>()
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            for (spec in specs) {
                if (!isActive()) break
                val frame = mmr.getFrameAtTime(spec.timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val c = crop(spec.box, frame.width, frame.height)
                val cut = Bitmap.createBitmap(frame, c[0], c[1], c[2], c[2])
                out[spec.id] = if (cut.width > size) Bitmap.createScaledBitmap(cut, size, size, true) else cut
                if (cut !== frame) frame.recycle()
            }
        } catch (e: Exception) {
            android.util.Log.w("ONIT", "face pictures", e)
        } finally {
            runCatching { mmr.release() }
        }
        return out
    }
}
