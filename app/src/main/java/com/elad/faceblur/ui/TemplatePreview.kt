package com.elad.faceblur.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.subs.SubtitleRenderer
import com.elad.faceblur.subs.SubtitleStyle
import com.elad.faceblur.template.Platform
import com.elad.faceblur.template.TemplateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a preview frame shows: the frame's shape, the blur over a face, the subtitle look, and the watermark. */
internal data class PreviewSpec(
    /** Width over height of the frame. */
    val aspect: Float,
    val blurOff: Boolean,
    val mode: BlurMode,
    val strength: Float,
    val padding: Float,
    val subStyle: SubtitleStyle,
    val watermark: Watermark,
    /** The subtitle text is drawn this much larger than it would be on a phone, so it reads on a small card. */
    val textBoost: Float = 2f,
)

/** The frame sample videos are shown in (16:9) when no video is open and the template keeps the shape. */
internal const val SAMPLE_ASPECT = 16f / 9f

/** [s] as a preview: its export decides the frame's shape, [sourceAspect] is the video's own (null: the sample's). */
internal fun TemplateSettings.preview(sourceAspect: Float? = null, textBoost: Float = 2f) = PreviewSpec(
    aspect = export.aspect.ratio.takeIf { it > 0f } ?: sourceAspect ?: SAMPLE_ASPECT,
    blurOff = blurOff, mode = mode, strength = strength, padding = padding,
    subStyle = subStyle, watermark = export.watermark, textBoost = textBoost,
)

/** A rendered sample frame with the look applied. [modifier] sets one side of its size; the shape follows [PreviewSpec.aspect]. */
@Composable
internal fun TemplatePreview(spec: PreviewSpec, frame: Bitmap?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = PreviewKey(spec, frame?.let { PreviewRenderer.frameId(it) } ?: 0)
    val bmp by produceState(PreviewRenderer.cached(key), key) {
        value = PreviewRenderer.cached(key) ?: withContext(PreviewRenderer.thread) { runCatching { PreviewRenderer.render(ctx, key, frame) }.getOrNull() }
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier
            .aspectRatio(spec.aspect)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
    }
}

internal data class PreviewKey(val spec: PreviewSpec, val frameId: Int)

/** Draws the preview frames: a generated sample scene (or a frame of the open video), the face's blur, the subtitles and the watermark. */
internal object PreviewRenderer {
    /** Up to 1/16 of the app's memory, by size. */
    private val cache = object : LruCache<PreviewKey, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: PreviewKey, value: Bitmap) = value.byteCount
    }

    /** One preview drawn at a time (a pickerful at once would take a lot of memory). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val thread: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** A number for each video frame shown (an identity hash can come back for another frame). */
    private val frameIds = java.util.WeakHashMap<Bitmap, Int>()
    private var nextFrameId = 1
    fun frameId(frame: Bitmap): Int = synchronized(frameIds) { frameIds.getOrPut(frame) { nextFrameId++ } }

    fun cached(key: PreviewKey): Bitmap? = cache.get(key)

    private const val LONG_SIDE = 520f

    fun render(context: Context, key: PreviewKey, frame: Bitmap?): Bitmap {
        cache.get(key)?.let { return it }
        val spec = key.spec
        val w: Int
        val h: Int
        if (spec.aspect >= 1f) {
            w = LONG_SIDE.roundToInt(); h = (LONG_SIDE / spec.aspect).roundToInt().coerceAtLeast(2)
        } else {
            h = LONG_SIDE.roundToInt(); w = (LONG_SIDE * spec.aspect).roundToInt().coerceAtLeast(2)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val face = RectF()
        if (frame != null) {
            drawCropped(c, frame, w, h)
        } else {
            drawScene(c, w, h, face)
            if (!spec.blurOff) obscure(out, face, spec)
        }
        if (frame != null && !spec.blurOff) drawBadge(c, w, h, spec)
        if (spec.watermark.enabled(context)) Watermark.draw(context, spec.watermark, c, w, h)
        SubtitleRenderer.get(context).draw(
            c, SampleBlocks.take(1), 200_000L, 2_200_000L, 1_100_000L,
            spec.subStyle.copy(size = spec.subStyle.size * spec.textBoost), w, h,
        )
        cache.put(key, out)
        return out
    }

    private fun drawCropped(c: Canvas, frame: Bitmap, w: Int, h: Int) {
        val want = w.toFloat() / h
        val have = frame.width.toFloat() / frame.height
        val sw: Float
        val sh: Float
        if (have > want) { sh = frame.height.toFloat(); sw = sh * want } else { sw = frame.width.toFloat(); sh = sw / want }
        val sx = (frame.width - sw) / 2f
        val sy = (frame.height - sh) / 2f
        c.drawBitmap(frame, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** A generated scene: dusk sky, hills and a person's head and shoulders ([face] gets the head's box). */
    private fun drawScene(c: Canvas, w: Int, h: Int, face: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF2B2F6B.toInt(), 0xFF8E4A8F.toInt(), 0xFFF2A07B.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFFFE3A3.toInt()
        c.drawCircle(w * 0.80f, h * 0.22f, min(w, h) * 0.08f, p)
        p.color = 0xFF2A2547.toInt()
        val hills = Path().apply {
            moveTo(0f, h * 0.78f)
            quadTo(w * 0.3f, h * 0.62f, w * 0.55f, h * 0.76f)
            quadTo(w * 0.8f, h * 0.88f, w.toFloat(), h * 0.70f)
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(hills, p)
        val m = min(w, h).toFloat()
        val r = m * 0.17f
        val cx = w * 0.5f
        val cy = h * 0.42f
        // Shoulders, neck, head, hair, eyes.
        p.color = 0xFF3F7CAC.toInt()
        c.drawRoundRect(RectF(cx - r * 1.9f, cy + r * 1.25f, cx + r * 1.9f, cy + r * 3.6f), r, r, p)
        p.color = 0xFFD9A07E.toInt()
        c.drawRect(cx - r * 0.35f, cy + r * 0.7f, cx + r * 0.35f, cy + r * 1.5f, p)
        c.drawOval(RectF(cx - r * 0.85f, cy - r, cx + r * 0.85f, cy + r), p)
        p.color = 0xFF3B2A22.toInt()
        c.drawArc(RectF(cx - r * 0.92f, cy - r * 1.1f, cx + r * 0.92f, cy + r * 0.35f), 180f, 180f, true, p)
        p.color = 0xFF2A1B16.toInt()
        c.drawCircle(cx - r * 0.33f, cy - r * 0.05f, r * 0.08f, p)
        c.drawCircle(cx + r * 0.33f, cy - r * 0.05f, r * 0.08f, p)
        c.drawRoundRect(RectF(cx - r * 0.25f, cy + r * 0.40f, cx + r * 0.25f, cy + r * 0.48f), r * 0.05f, r * 0.05f, p)
        face.set(cx - r * 0.85f, cy - r, cx + r * 0.85f, cy + r)
    }

    /** The blur, pixels or cover over [face] (grown by the blur's padding), as the app draws it. */
    private fun obscure(bmp: Bitmap, face: RectF, spec: PreviewSpec) {
        val grow = ((spec.padding - 1f).coerceIn(0f, 2f) * 0.5f + 1f)
        val cx = face.centerX()
        val cy = face.centerY()
        val hw = face.width() / 2f * grow
        val hh = face.height() / 2f * grow
        val area = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
        val x = max(0, area.left.toInt()); val y = max(0, area.top.toInt())
        val x2 = min(bmp.width, area.right.toInt()); val y2 = min(bmp.height, area.bottom.toInt())
        if (x2 - x < 4 || y2 - y < 4) return
        val rw = x2 - x
        val rh = y2 - y
        val region = Bitmap.createBitmap(bmp, x, y, rw, rh)
        val s = spec.strength.coerceIn(0f, 1f)
        val result: Bitmap? = when (spec.mode) {
            BlurMode.BLUR -> {
                val k = 4f + 14f * s
                val small = Bitmap.createScaledBitmap(region, max(2, (rw / k).toInt()), max(2, (rh / k).toInt()), true)
                val mid = Bitmap.createScaledBitmap(small, max(3, rw / 3), max(3, rh / 3), true)
                Bitmap.createScaledBitmap(mid, rw, rh, true)
            }
            BlurMode.PIXELATE -> {
                val k = 5f + 14f * s
                val small = Bitmap.createScaledBitmap(region, max(2, (rw / k).toInt()), max(2, (rh / k).toInt()), true)
                Bitmap.createScaledBitmap(small, rw, rh, false)
            }
            BlurMode.SOLID -> null
        }
        val c = Canvas(bmp)
        val clip = Path().apply { addRoundRect(RectF(x.toFloat(), y.toFloat(), x2.toFloat(), y2.toFloat()), rw * 0.22f, rw * 0.22f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        if (result != null) c.drawBitmap(result, x.toFloat(), y.toFloat(), null)
        else c.drawColor(0xFF15151C.toInt())
        c.restore()
    }

    /** With a video's own frame (no faces known here) the blur's look is shown on a small sample head in a corner. */
    private fun drawBadge(c: Canvas, w: Int, h: Int, spec: PreviewSpec) {
        val side = (min(w, h) * 0.30f).roundToInt().coerceAtLeast(16)
        val badge = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val bc = Canvas(badge)
        val face = RectF()
        drawScene(bc, side, side, face)
        obscure(badge, face, spec)
        val pad = min(w, h) * 0.04f
        val dst = RectF(pad, pad, pad + side, pad + side)
        val path = Path().apply { addRoundRect(dst, side * 0.18f, side * 0.18f, Path.Direction.CW) }
        c.save()
        c.clipPath(path)
        c.drawBitmap(badge, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        c.drawRoundRect(dst, side * 0.18f, side * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFFFFFFF.toInt() })
    }
}

/** The platform's name. */
internal fun platformName(p: Platform): String = when (p) {
    Platform.TIKTOK -> com.elad.faceblur.i18n.t("טיקטוק")
    Platform.REELS -> com.elad.faceblur.i18n.t("ריילס")
    Platform.SHORTS -> com.elad.faceblur.i18n.t("שורטס")
}

/** A fixed [width]x[height] stage with the preview fitted inside it (so cards line up whatever the frame's shape). */
@Composable
internal fun PreviewStage(spec: PreviewSpec, frame: Bitmap?, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(width, height), contentAlignment = androidx.compose.ui.Alignment.Center) {
        val wide = spec.aspect > width / height
        TemplatePreview(spec, frame, if (wide) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
    }
}
