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
import com.elad.faceblur.template.SafeZone
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the watermark sits: a 3x3 grid over the frame (left/right as on the video). */
enum class WatermarkPosition(val col: Int, val row: Int) {
    TOP_LEFT(0, 0), TOP_CENTER(1, 0), TOP_RIGHT(2, 0),
    CENTER_LEFT(0, 1), CENTER(1, 1), CENTER_RIGHT(2, 1),
    BOTTOM_LEFT(0, 2), BOTTOM_CENTER(1, 2), BOTTOM_RIGHT(2, 2),
}

/** How text stands out from the video. */
enum class WatermarkLook { PLAIN, SHADOW, OUTLINE, BOX }

/**
 * A logo or a line of text on the export.
 *
 * @param image use the picked logo image (see [Watermark.imageFile]) instead of [text].
 * @param size multiplier of the base size (logo: 10% of the short side high; text: 4%).
 * @param stamp changes when a new logo is picked (so its preview reloads).
 * @param font, [color], [look]: the text's font, color and how it stands out.
 */
data class Watermark(
    val text: String = "",
    val image: Boolean = false,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
    val size: Float = 1f,
    val opacity: Float = 0.8f,
    val stamp: Long = 0L,
    val font: SubFont = SubFont.RUBIK,
    val color: Int = Color.WHITE,
    val look: WatermarkLook = WatermarkLook.SHADOW,
    /** Weight of a variable [font] (0: its default) and Allo's roundness. */
    val weight: Int = 0,
    val round: Int = SubFont.ROUND_DEFAULT,
) {
    fun enabled(context: Context) = if (image) imageFile(context).exists() else text.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("text", text).put("image", image).put("corner", position.name)
        .put("size", size.toDouble()).put("opacity", opacity.toDouble()).put("stamp", stamp)
        .put("font", font.name).put("color", color).put("look", look.name)
        .put("weight", weight).put("round", round)
        .toString()

    companion object {
        fun imageFile(context: Context) = File(context.filesDir, "watermark.png")

        fun fromJson(s: String?): Watermark {
            if (s.isNullOrBlank()) return Watermark()
            return try {
                val o = JSONObject(s)
                Watermark(
                    text = o.optString("text"),
                    image = o.optBoolean("image"),
                    position = runCatching { WatermarkPosition.valueOf(o.optString("corner")) }.getOrDefault(WatermarkPosition.BOTTOM_RIGHT),
                    size = o.optDouble("size", 1.0).toFloat(),
                    opacity = o.optDouble("opacity", 0.8).toFloat(),
                    stamp = o.optLong("stamp"),
                    font = runCatching { SubFont.valueOf(o.optString("font")) }.getOrDefault(SubFont.RUBIK),
                    color = o.optInt("color", Color.WHITE),
                    look = runCatching { WatermarkLook.valueOf(o.optString("look")) }.getOrDefault(WatermarkLook.SHADOW),
                    weight = o.optInt("weight", 0),
                    round = o.optInt("round", SubFont.ROUND_DEFAULT).coerceIn(0, 100),
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

        /** Draws [w] on a [width] x [height] canvas; with a [zone], inside its safe area. */
        fun draw(context: Context, w: Watermark, canvas: Canvas, width: Int, height: Int, zone: SafeZone? = null) {
            val short = min(width, height).toFloat()
            val margin = short * 0.035f
            val m = zone?.watermarkMargins(width.toFloat(), height.toFloat(), margin) ?: SafeZone.Margins(margin, margin, margin, margin)
            val alpha = (w.opacity * 255).roundToInt().coerceIn(0, 255)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
            val pos = w.position
            if (w.image) {
                val logo = BitmapFactory.decodeFile(imageFile(context).path) ?: return
                var lh = short * 0.10f * w.size
                var lw = lh * logo.width / logo.height
                if (lw > width * 0.6f) {
                    lw = width * 0.6f
                    lh = lw * logo.height / logo.width
                }
                val x = SafeZone.place(pos.col, lw, width.toFloat(), m.left, m.right)
                val y = SafeZone.place(pos.row, lh, height.toFloat(), m.top, m.bottom)
                canvas.drawBitmap(logo, null, RectF(x, y, x + lw, y + lh), paint)
                logo.recycle()
                return
            }
            if (w.text.isBlank()) return
            paint.typeface = SubtitleRenderer.get(context).typeface(w.font, w.weight, w.round)
            paint.textSize = short * 0.04f * w.size
            val tw = paint.measureText(w.text)
            val fm = paint.fontMetrics
            val th = fm.descent - fm.ascent
            // A box needs room around the text.
            val pad = if (w.look == WatermarkLook.BOX) paint.textSize * 0.35f else 0f
            val x = SafeZone.place(pos.col, tw + 2 * pad, width.toFloat(), m.left, m.right) + pad
            val top = SafeZone.place(pos.row, th + 2 * pad, height.toFloat(), m.top, m.bottom) + pad
            val baseline = top - fm.ascent
            // Outline and box in black behind light text, white behind dark text.
            val contrast = if (Color.luminance(w.color) > 0.45f) Color.BLACK else Color.WHITE
            when (w.look) {
                WatermarkLook.PLAIN -> Unit
                WatermarkLook.SHADOW -> paint.setShadowLayer(paint.textSize * 0.12f, 0f, paint.textSize * 0.04f, Color.argb((w.opacity * 200).roundToInt(), 0, 0, 0))
                WatermarkLook.OUTLINE -> {
                    val stroke = Paint(paint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = textSize * 0.14f
                        strokeJoin = Paint.Join.ROUND
                        color = contrast
                        this.alpha = alpha
                    }
                    canvas.drawText(w.text, x, baseline, stroke)
                }
                WatermarkLook.BOX -> {
                    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = contrast
                        this.alpha = (alpha * 0.6f).roundToInt()
                    }
                    val r = RectF(x - pad, top - pad, x + tw + pad, top + th + pad)
                    canvas.drawRoundRect(r, r.height() * 0.3f, r.height() * 0.3f, box)
                }
            }
            paint.color = w.color
            paint.alpha = alpha
            canvas.drawText(w.text, x, baseline, paint)
        }
    }
}

/** The watermark burned in: one full-frame picture, drawn once for the video's size. */
class WatermarkOverlay(private val context: Context, private val mark: Watermark, private val zone: SafeZone? = null) : BitmapOverlay() {
    private var bitmap: Bitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val b = bitmap
        if (b == null || b.width != videoSize.width || b.height != videoSize.height) {
            bitmap = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888).also {
                Watermark.draw(context, mark, Canvas(it), it.width, it.height, zone)
            }
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { bitmap = it }
}
