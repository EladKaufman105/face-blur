package com.elad.faceblur.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.template.Platform
import com.elad.faceblur.template.SafeZone

/** Plain Material glyph outlines (24x24, Apache 2.0). Generic shapes only: no platform's own artwork. */
internal object MockGlyphs {
    const val NOTE = "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
    const val MOVIE = "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"
    const val PLAY_BOX = "M20 3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H4V5h16v14zM10 8v8l6-4z"
    const val HEART = "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
    const val COMMENT = "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"
    const val ARROW = "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z"
    const val BOOKMARK = "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z"
    const val PLANE = "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"
    const val THUMB = "M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"
    const val DOTS = "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
    const val SEARCH = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
}

/**
 * A generic, made-up short-video app drawn over the frame to show what a platform's own screens
 * will cover. It borrows no platform's logo, name or artwork: white shapes with a soft shadow,
 * placed from the platform's [SafeZone] margins. Preview only: it is never burned into the video.
 */
private object Mock {
    private val paths = HashMap<String, Path>()
    fun path(d: String): Path = paths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

    // Only the UI thread draws, so the paints are shared.
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
}

private val INK = Color.White.copy(alpha = 0.92f)
private val SOFT = Color.White.copy(alpha = 0.55f)
private val SHADOW = Color.Black.copy(alpha = 0.38f)

private fun DrawScope.glyph(d: String, cx: Float, cy: Float, size: Float, color: Color = INK, flip: Boolean = false, turn: Float = 0f) {
    val path = Mock.path(d)
    val k = size / 24f
    for (pass in 0..1) {
        val shadow = pass == 0
        val dx = if (shadow) size * 0.04f else 0f
        val dy = if (shadow) size * 0.06f else 0f
        withTransform({
            translate(cx - size / 2f + dx, cy - size / 2f + dy)
            if (flip) { translate(size, 0f); scale(-k, k, Offset.Zero) } else scale(k, k, Offset.Zero)
            if (turn != 0f) rotate(turn, Offset(12f, 12f))
        }) { drawPath(path, if (shadow) SHADOW else color) }
    }
}

private fun DrawScope.bar(x: Float, y: Float, w: Float, h: Float, color: Color = SOFT) {
    val r = CornerRadius(h / 2f, h / 2f)
    drawRoundRect(SHADOW, Offset(x + h * 0.1f, y + h * 0.25f), Size(w, h), r)
    drawRoundRect(color, Offset(x, y), Size(w, h), r)
}

private fun DrawScope.dot(cx: Float, cy: Float, r: Float, color: Color = INK) {
    drawCircle(SHADOW, r, Offset(cx + r * 0.06f, cy + r * 0.12f))
    drawCircle(color, r, Offset(cx, cy))
}

private fun DrawScope.label(s: String, x: Float, baseline: Float, size: Float, bold: Boolean, alpha: Float, centered: Boolean = false) {
    val p = Mock.text
    p.textSize = size
    p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    p.color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255)
    p.setShadowLayer(size * 0.18f, 0f, size * 0.06f, 0x88000000.toInt())
    p.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(s, x, baseline, p)
    p.clearShadowLayer()
}

private fun measure(s: String, size: Float, bold: Boolean): Float {
    val p = Mock.text
    p.textSize = size
    p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    return p.measureText(s)
}

/**
 * The platform's own interface, imitated, over a frame at [origin] of [frame] size, laid out by
 * the platform's [zone]: a tab strip at the top, a column of buttons at the right, the account,
 * caption and sound lines at the bottom and a thin progress bar. A soft shade behind the top and
 * bottom parts and a thin dashed outline around the clear middle help the eye. Only drawn on
 * screen, never burned into the video.
 */
internal fun DrawScope.drawSafeZone(platform: Platform, zone: SafeZone, origin: Offset, frame: Size) {
    val w = frame.width
    val h = frame.height
    val topH = h * zone.top
    val botH = h * zone.bottom
    val leftW = w * zone.left
    val rightW = w * zone.right
    withTransform({ translate(origin.x, origin.y) }) {
        // Light shade where the platform's screens sit, so white shapes read on any video.
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent), 0f, topH), Offset.Zero, Size(w, topH))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)), h - botH, h), Offset(0f, h - botH), Size(w, botH))

        // Top: the tab strip (and, per platform, search / menu).
        val tabSize = w * 0.04f
        val tabY = topH * 0.62f
        val a = t("עוקבים")
        val b = t("בשבילך")
        val gap = w * 0.05f
        val wa = measure(a, tabSize, false)
        val wb = measure(b, tabSize, true)
        val sx = (w - (wa + wb + gap)) / 2f
        label(a, sx, tabY, tabSize, false, 0.62f)
        label(b, sx + wa + gap, tabY, tabSize, true, 0.95f)
        bar(sx + wa + gap, tabY + tabSize * 0.35f, wb, maxOf(2.dp.toPx(), tabSize * 0.1f), INK)
        val topIcon = minOf(w * 0.06f, topH * 0.36f)
        when (platform) {
            Platform.TIKTOK -> glyph(MockGlyphs.SEARCH, w - rightW / 2f - topIcon * 0.1f, tabY - tabSize * 0.35f, topIcon)
            Platform.SHORTS -> {
                glyph(MockGlyphs.SEARCH, w - rightW / 2f - topIcon * 1.15f, tabY - tabSize * 0.35f, topIcon)
                glyph(MockGlyphs.DOTS, w - rightW / 2f + topIcon * 0.1f, tabY - tabSize * 0.35f, topIcon)
            }
            Platform.REELS -> Unit
        }

        // Right: the column of buttons, ending with the sound disc / thumbnail low in the bottom zone.
        val icon = (rightW * 0.5f).coerceIn(w * 0.045f, w * 0.07f)
        val cx = w - rightW / 2f
        val step = icon * 2.05f
        val last = h - botH * 0.52f
        val items = when (platform) {
            Platform.TIKTOK -> listOf("avatar", "heart", "comment", "bookmark", "share", "disc")
            Platform.REELS -> listOf("heart", "comment", "plane", "dots", "square")
            Platform.SHORTS -> listOf("like", "dislike", "comment", "share", "dots", "square")
        }
        items.forEachIndexed { i, kind ->
            val cy = last - (items.size - 1 - i) * step
            when (kind) {
                "avatar" -> {
                    dot(cx, cy, icon * 0.62f, SOFT)
                    dot(cx, cy - icon * 0.12f, icon * 0.2f)
                    drawArc(INK, 180f, 180f, true, Offset(cx - icon * 0.38f, cy + icon * 0.12f), Size(icon * 0.76f, icon * 0.5f))
                    val bx = cx
                    val by = cy + icon * 0.66f
                    dot(bx, by, icon * 0.22f, Color.White)
                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(bx - icon * 0.11f, by), Offset(bx + icon * 0.11f, by), icon * 0.05f)
                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(bx, by - icon * 0.11f), Offset(bx, by + icon * 0.11f), icon * 0.05f)
                }
                "heart" -> { glyph(MockGlyphs.HEART, cx, cy - icon * 0.2f, icon); bar(cx - icon * 0.3f, cy + icon * 0.5f, icon * 0.6f, icon * 0.16f) }
                "like" -> { glyph(MockGlyphs.THUMB, cx, cy - icon * 0.2f, icon); bar(cx - icon * 0.3f, cy + icon * 0.5f, icon * 0.6f, icon * 0.16f) }
                "dislike" -> { glyph(MockGlyphs.THUMB, cx, cy - icon * 0.2f, icon, turn = 180f); bar(cx - icon * 0.3f, cy + icon * 0.5f, icon * 0.6f, icon * 0.16f) }
                "comment" -> { glyph(MockGlyphs.COMMENT, cx, cy - icon * 0.2f, icon); bar(cx - icon * 0.3f, cy + icon * 0.5f, icon * 0.6f, icon * 0.16f) }
                "bookmark" -> { glyph(MockGlyphs.BOOKMARK, cx, cy - icon * 0.2f, icon); bar(cx - icon * 0.3f, cy + icon * 0.5f, icon * 0.6f, icon * 0.16f) }
                "share" -> { glyph(MockGlyphs.ARROW, cx, cy - icon * 0.2f, icon, flip = true); bar(cx - icon * 0.3f, cy + icon * 0.5f, icon * 0.6f, icon * 0.16f) }
                "plane" -> glyph(MockGlyphs.PLANE, cx, cy, icon)
                "dots" -> glyph(MockGlyphs.DOTS, cx, cy, icon)
                "disc" -> {
                    dot(cx, cy, icon * 0.62f, Color.White.copy(alpha = 0.35f))
                    dot(cx, cy, icon * 0.42f, Color.Black.copy(alpha = 0.55f))
                    glyph(MockGlyphs.NOTE, cx, cy, icon * 0.5f)
                }
                else -> {
                    val s = icon * 0.95f
                    drawRoundRect(SHADOW, Offset(cx - s / 2f + 2f, cy - s / 2f + 3f), Size(s, s), CornerRadius(s * 0.2f))
                    drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(cx - s / 2f, cy - s / 2f), Size(s, s), CornerRadius(s * 0.2f))
                    drawRoundRect(INK, Offset(cx - s / 2f, cy - s / 2f), Size(s, s), CornerRadius(s * 0.2f), style = Stroke(maxOf(1.5f, s * 0.07f)))
                    glyph(MockGlyphs.NOTE, cx, cy, s * 0.55f)
                }
            }
        }

        // Bottom: account, caption (two lines), sound line, then the progress bar.
        val x0 = maxOf(leftW * 0.5f, w * 0.03f)
        val textSize = w * 0.036f
        val lineH = botH * 0.14f
        val progH = maxOf(2.dp.toPx(), h * 0.0035f)
        val soundY = h - botH * 0.30f
        val cap2 = soundY - lineH * 1.35f
        val cap1 = cap2 - lineH * 0.95f
        val userY = cap1 - lineH * 1.15f
        var ux = x0
        if (platform != Platform.TIKTOK) {
            val ar = textSize * 0.9f
            dot(ux + ar, userY - textSize * 0.3f, ar, SOFT)
            dot(ux + ar, userY - textSize * 0.55f, ar * 0.32f)
            ux += ar * 2f + textSize * 0.5f
        }
        val user = "\u2066@username\u2069"
        label(user, ux, userY, textSize, true, 0.96f)
        val pillW0 = measure(user, textSize, true)
        when (platform) {
            Platform.REELS, Platform.SHORTS -> {
                val txt = if (platform == Platform.REELS) t("עקוב") else t("הרשמה")
                val ps = textSize * 0.8f
                val tw = measure(txt, ps, true)
                val px = ux + pillW0 + textSize * 0.7f
                val ph = ps * 1.9f
                val py = userY - textSize * 0.35f - ph / 2f
                val r = CornerRadius(ph / 2f)
                if (platform == Platform.SHORTS) {
                    drawRoundRect(Color.White.copy(alpha = 0.92f), Offset(px, py), Size(tw + ps * 1.6f, ph), r)
                    val p = Mock.text
                    p.textSize = ps; p.typeface = Typeface.DEFAULT_BOLD; p.color = 0xFF202020.toInt(); p.textAlign = Paint.Align.LEFT
                    drawContext.canvas.nativeCanvas.drawText(txt, px + ps * 0.8f, py + ph / 2f + ps * 0.35f, p)
                } else {
                    drawRoundRect(INK, Offset(px, py), Size(tw + ps * 1.6f, ph), r, style = Stroke(maxOf(1.5f, ps * 0.1f)))
                    label(txt, px + ps * 0.8f, py + ph / 2f + ps * 0.35f, ps, true, 0.95f)
                }
            }
            Platform.TIKTOK -> Unit
        }
        val barH = textSize * 0.42f
        val capW = w - x0 - rightW - w * 0.03f
        bar(x0, cap1 - barH, capW * 0.9f, barH, Color.White.copy(alpha = 0.7f))
        bar(x0, cap2 - barH, capW * 0.55f, barH, Color.White.copy(alpha = 0.7f))
        glyph(MockGlyphs.NOTE, x0 + textSize * 0.55f, soundY - textSize * 0.3f, textSize * 1.1f)
        label(t("סאונד מקורי") + " · \u2066@username\u2069", x0 + textSize * 1.4f, soundY, textSize * 0.85f, false, 0.9f)
        drawRect(Color.White.copy(alpha = 0.3f), Offset(0f, h - progH), Size(w, progH))
        drawRect(Color.White.copy(alpha = 0.9f), Offset(0f, h - progH), Size(w * 0.35f, progH))

        // The clear middle.
        drawRect(
            Color.White.copy(alpha = 0.55f), Offset(leftW, topH), Size(w - leftW - rightW, h - topH - botH),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }
}
