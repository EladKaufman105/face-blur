package com.elad.faceblur.subs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay

/** One subtitle to burn in: media-time range (µs) and its text. */
class BurnCue(val startUs: Long, val endUs: Long, val text: String)

/**
 * Burns subtitles into the video: white text with a black outline near the bottom, wrapped,
 * right-to-left for Hebrew. Each subtitle is drawn once into a full-frame transparent bitmap.
 */
class SubtitleOverlay(private val cues: List<BurnCue>) : BitmapOverlay() {
    private var frameW = 0
    private var frameH = 0
    private var shown: BurnCue? = null
    private var bitmap: Bitmap? = null
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        frameW = videoSize.width
        frameH = videoSize.height
        bitmap = null
        shown = null
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val cue = cues.firstOrNull { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
        if (cue == null || frameW <= 0) return empty
        if (cue !== shown || bitmap == null) {
            bitmap = render(cue.text, frameW, frameH)
            shown = cue
        }
        return bitmap!!
    }

    companion object {
        /** Full-frame transparent bitmap with the subtitle at the bottom. */
        fun render(text: String, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp), text, w, h)
            return bmp
        }

        /** Draws the subtitle at the bottom of a [w]×[h] frame (also the editor's preview). */
        fun draw(canvas: Canvas, text: String, w: Int, h: Int) {
            if (w <= 0 || h <= 0 || text.isBlank()) return
            val size = minOf(w, h) * 0.052f
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val width = (w * 0.86f).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.05f)
                .build()
            val x = (w - width) / 2f
            val y = h - h * 0.08f - layout.height
            canvas.save()
            canvas.translate(x, y)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.16f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.BLACK
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.setShadowLayer(size * 0.08f, 0f, size * 0.04f, Color.argb(160, 0, 0, 0))
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
