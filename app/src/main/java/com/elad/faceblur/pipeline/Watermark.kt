package com.elad.faceblur.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubtitleRenderer
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

enum class WatermarkCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * A logo or a line of text in a corner of the export.
 *
 * @param image use the picked logo image (see [Watermark.imageFile]) instead of [text].
 * @param size multiplier of the base size (logo: 10% of the short side high; text: 4%).
 * @param stamp changes when a new logo is picked (so its preview reloads).
 */
data class Watermark(
    val text: String = "",
    val image: Boolean = false,
    val corner: WatermarkCorner = WatermarkCorner.BOTTOM_RIGHT,
    val size: Float = 1f,
    val opacity: Float = 0.8f,
    val stamp: Long = 0L,
) {
    fun enabled(context: Context) = if (image) imageFile(context).exists() else text.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("text", text).put("image", image).put("corner", corner.name)
        .put("size", size.toDouble()).put("opacity", opacity.toDouble()).put("stamp", stamp)
        .toString()

    companion object {
        fun imageFile(context: Context) = File(context.filesDir, "watermark.png")

        fun fromJson(s: String?): Watermark {
            if (s.isNullOrBlank()) return Watermark()
            return try {
                val o = JSONObject(s)
                Watermark(
                    o.optString("text"),
                    o.optBoolean("image"),
                    runCatching { WatermarkCorner.valueOf(o.optString("corner")) }.getOrDefault(WatermarkCorner.BOTTOM_RIGHT),
                    o.optDouble("size", 1.0).toFloat(),
                    o.optDouble("opacity", 0.8).toFloat(),
                    o.optLong("stamp"),
                )
            } catch (e: Exception) {
                Watermark()
            }
        }

        /** Keeps a copy of the picked logo (at most 1024 px, PNG keeps its transparency). */
        fun importImage(context: Context, uri: Uri): Boolean {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
            val s = min(1f, 1024f / maxOf(bmp.width, bmp.height))
            val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true) else bmp
            val f = imageFile(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return tmp.renameTo(f)
        }

        /** Draws [w] on a [width] x [height] canvas. */
        fun draw(context: Context, w: Watermark, canvas: Canvas, width: Int, height: Int) {
            val short = min(width, height).toFloat()
            val margin = short * 0.035f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (w.opacity * 255).roundToInt().coerceIn(0, 255) }
            val left = w.corner == WatermarkCorner.TOP_LEFT || w.corner == WatermarkCorner.BOTTOM_LEFT
            val top = w.corner == WatermarkCorner.TOP_LEFT || w.corner == WatermarkCorner.TOP_RIGHT
            if (w.image) {
                val logo = BitmapFactory.decodeFile(imageFile(context).path) ?: return
                var lh = short * 0.10f * w.size
                var lw = lh * logo.width / logo.height
                if (lw > width * 0.4f) {
                    lw = width * 0.4f
                    lh = lw * logo.height / logo.width
                }
                val x = if (left) margin else width - margin - lw
                val y = if (top) margin else height - margin - lh
                canvas.drawBitmap(logo, null, RectF(x, y, x + lw, y + lh), paint)
                logo.recycle()
            } else if (w.text.isNotBlank()) {
                paint.typeface = SubtitleRenderer.get(context).typeface(SubFont.RUBIK)
                paint.textSize = short * 0.04f * w.size
                paint.color = Color.WHITE
                paint.alpha = (w.opacity * 255).roundToInt().coerceIn(0, 255)
                paint.setShadowLayer(paint.textSize * 0.12f, 0f, paint.textSize * 0.04f, Color.argb((w.opacity * 200).roundToInt(), 0, 0, 0))
                val tw = paint.measureText(w.text)
                val fm = paint.fontMetrics
                val x = if (left) margin else width - margin - tw
                val y = if (top) margin - fm.ascent else height - margin - fm.descent
                canvas.drawText(w.text, x, y, paint)
            }
        }
    }
}

/** The watermark burned in: one full-frame picture, drawn once for the video's size. */
class WatermarkOverlay(private val context: Context, private val mark: Watermark) : BitmapOverlay() {
    private var bitmap: Bitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val b = bitmap
        if (b == null || b.width != videoSize.width || b.height != videoSize.height) {
            bitmap = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888).also {
                Watermark.draw(context, mark, Canvas(it), it.width, it.height)
            }
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { bitmap = it }
}
