package com.elad.faceblur.subs

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one subtitle in a [SubtitleStyle] at a moment in time: the same code for the editor's
 * preview and for burning into the video, so what you see is what you get.
 *
 * Word timings are spread over the subtitle in proportion to word length (good enough for the
 * short lines speech recognition makes, and they follow the text when it's edited).
 */
class SubtitleRenderer private constructor(private val assets: AssetManager?) {
    private val typefaces = HashMap<SubFont, Typeface>()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun typeface(f: SubFont): Typeface = synchronized(typefaces) {
        typefaces.getOrPut(f) {
            val asset = f.asset
            if (asset == null || assets == null) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                try {
                    val b = Typeface.Builder(assets, asset)
                    if (f.weight > 0) b.setFontVariationSettings("'wght' ${f.weight}")
                    b.build() ?: Typeface.DEFAULT_BOLD
                } catch (e: Exception) {
                    Typeface.DEFAULT_BOLD
                }
            }
        }
    }

    /** What changes between frames: the words in play and, while animating, the time. */
    fun frameKey(blocks: List<String>, startUs: Long, endUs: Long, tUs: Long, style: SubtitleStyle): Long {
        var k = 17L
        for (b in blocks) k = k * 31 + activeWord(words(b), startUs, endUs, tUs)
        val anim = animating(blocks, startUs, endUs, tUs, style)
        return if (anim) k * 1_000_003L + tUs / 1000 else k
    }

    /**
     * Draws the subtitle made of [blocks] (one paragraph each, e.g. Hebrew then English) shown
     * from [startUs] to [endUs], at [tUs], into a [w]×[h] frame. Returns where the text is
     * (for tapping it), or null if nothing was drawn.
     */
    fun draw(canvas: Canvas, blocks: List<String>, startUs: Long, endUs: Long, tUs: Long, style: SubtitleStyle, w: Int, h: Int): RectF? {
        val texts = blocks.filter { it.isNotBlank() }
        if (texts.isEmpty() || w <= 0 || h <= 0) return null
        val effect = style.effect
        val oneWord = effect == SubEffect.ONE_WORD
        val base = min(w, h) * 0.055f * style.size * (if (oneWord) 1.25f else 1f)
        val maxW = (w * 0.86f).toInt()
        val tf = typeface(style.font)

        var alpha = 1f
        var scale = 1f
        val since = (tUs - startUs) / 1000f
        val until = (endUs - tUs) / 1000f
        when (effect) {
            SubEffect.FADE -> alpha = (min(since / FADE_MS, until / FADE_MS)).coerceIn(0f, 1f)
            SubEffect.POP -> {
                scale = pop(since / POP_MS)
                alpha = (since / 70f).coerceIn(0f, 1f)
            }
            else -> Unit
        }

        // Lay out each block (one word of each in one-word mode).
        class Block(val layout: StaticLayout, val paint: TextPaint, val text: String, val words: List<IntRange>, val active: Int, val size: Float)
        val laid = ArrayList<Block>()
        texts.forEachIndexed { bi, full ->
            val ws = words(full)
            val active = activeWord(ws, startUs, endUs, tUs)
            val size = if (bi == 0) base else base * 0.86f
            val text: String
            val wordRanges: List<IntRange>
            if (oneWord) {
                if (active < 0) return@forEachIndexed
                text = full.substring(ws[active].first, ws[active].last + 1)
                wordRanges = listOf(text.indices)
                val wStart = wordStartUs(ws, active, startUs, endUs)
                scale = min(scale, pop((tUs - wStart) / 1000f / WORD_POP_MS))
            } else {
                text = full
                wordRanges = ws
            }
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = size
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.02f)
                .build()
            laid += Block(layout, tp, text, wordRanges, if (oneWord) 0 else active, size)
        }
        if (laid.isEmpty()) return null
        val gap = base * 0.3f
        val totalH = laid.sumOf { it.layout.height } + gap * (laid.size - 1)
        val margin = h * 0.04f
        var top = (h * style.position - totalH / 2f).coerceIn(margin, max(margin, h - margin - totalH))
        val cy = top + totalH / 2f
        val widest = laid.maxOf { b -> (0 until b.layout.lineCount).maxOfOrNull { b.layout.getLineWidth(it) } ?: 0f }
        val bounds = RectF((w - widest) / 2f, top, (w + widest) / 2f, top + totalH)

        canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, w / 2f, cy)
        val layer = if (alpha < 0.999f) canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (alpha * 255).toInt()) else -1

        for (b in laid) {
            val layout = b.layout
            val size = b.size
            // Words not shown yet (typewriter).
            val visibleEnd = if (effect == SubEffect.TYPEWRITER && !oneWord) {
                if (b.active < 0) 0 else b.words[b.active].last + 1
            } else {
                b.text.length
            }
            canvas.save()
            canvas.translate((w - maxW) / 2f, top)

            if (style.box && visibleEnd > 0) {
                boxPaint.color = style.boxColor
                val hx = size * 0.32f
                val vy = size * 0.08f
                for (line in 0 until layout.lineCount) {
                    val s = layout.getLineStart(line)
                    val e = min(layout.getLineEnd(line), visibleEnd)
                    if (e <= s || b.text.substring(s, e).isBlank()) continue
                    path.reset()
                    layout.getSelectionPath(s, trimEnd(b.text, s, e), path)
                    path.computeBounds(rect, true)
                    rect.top = layout.getLineTop(line).toFloat()
                    rect.bottom = layout.getLineBottom(line).toFloat()
                    rect.inset(-hx, -vy)
                    canvas.drawRoundRect(rect, size * 0.3f, size * 0.3f, boxPaint)
                }
            }
            if (effect == SubEffect.WORD_BOX && b.active >= 0) {
                val r = b.words[b.active]
                path.reset()
                layout.getSelectionPath(r.first, r.last + 1, path)
                path.computeBounds(rect, true)
                rect.inset(-size * 0.2f, -size * 0.06f)
                boxPaint.color = style.accentColor
                canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, boxPaint)
            }

            val tp = b.paint
            // Words not said yet keep their place in the layout but are clipped away (with
            // their outline and shadow).
            if (visibleEnd < b.text.length) {
                val clip = Path()
                for (line in 0 until layout.lineCount) {
                    val s = layout.getLineStart(line)
                    val e = min(layout.getLineEnd(line), visibleEnd)
                    if (e <= s || b.text.substring(s, e).isBlank()) continue
                    path.reset()
                    layout.getSelectionPath(s, trimEnd(b.text, s, e), path)
                    path.computeBounds(rect, true)
                    rect.top = layout.getLineTop(line) - size * 0.2f
                    rect.bottom = layout.getLineBottom(line) + size * 0.3f
                    rect.left -= size * 0.12f
                    rect.right += size * 0.12f
                    clip.addRect(rect, Path.Direction.CW)
                }
                canvas.clipPath(clip)
            }
            fun spans(fill: Boolean): CharSequence {
                val sp = SpannableString(b.text)
                if (fill && effect == SubEffect.HIGHLIGHT && b.active >= 0) {
                    val r = b.words[b.active]
                    sp.setSpan(ForegroundColorSpan(style.accentColor), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                return sp
            }
            fun pass(text: CharSequence) {
                StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.02f)
                    .build()
                    .draw(canvas)
            }
            // Glow (neon).
            if (style.glow) {
                tp.style = Paint.Style.FILL
                tp.color = style.textColor or 0xFF000000.toInt()
                tp.setShadowLayer(size * 0.5f, 0f, 0f, style.textColor or 0xFF000000.toInt())
                pass(spans(false))
                pass(spans(false))
                tp.clearShadowLayer()
            }
            // Outline and drop shadow.
            if (style.outline > 0f || style.shadow) {
                tp.color = style.outlineColor or 0xFF000000.toInt()
                if (style.outline > 0f) {
                    tp.style = Paint.Style.STROKE
                    tp.strokeWidth = size * style.outline
                    tp.strokeJoin = Paint.Join.ROUND
                } else {
                    tp.style = Paint.Style.FILL
                }
                if (style.shadow) tp.setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.BLACK) else tp.clearShadowLayer()
                pass(spans(false))
                tp.clearShadowLayer()
            }
            tp.style = Paint.Style.FILL
            tp.color = style.textColor
            pass(spans(true))

            canvas.restore()
            top += layout.height + gap
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restore()
        return bounds
    }

    private fun trimEnd(text: String, s: Int, e: Int): Int {
        var end = e
        while (end > s && text[end - 1].isWhitespace()) end--
        return end
    }

    private fun animating(blocks: List<String>, startUs: Long, endUs: Long, tUs: Long, style: SubtitleStyle): Boolean {
        val since = (tUs - startUs) / 1000f
        val until = (endUs - tUs) / 1000f
        return when (style.effect) {
            SubEffect.FADE -> since < FADE_MS || until < FADE_MS
            SubEffect.POP -> since < POP_MS
            SubEffect.ONE_WORD -> blocks.any { b ->
                val ws = words(b)
                val a = activeWord(ws, startUs, endUs, tUs)
                a >= 0 && (tUs - wordStartUs(ws, a, startUs, endUs)) / 1000f < WORD_POP_MS
            }
            else -> false
        }
    }

    companion object {
        private const val FADE_MS = 160f
        private const val POP_MS = 260f
        private const val WORD_POP_MS = 180f
        /** Words are spread over this part of the subtitle (speech ends a little before it does). */
        private const val SPEECH_PART = 0.92f

        @Volatile
        private var instance: SubtitleRenderer? = null

        fun get(context: Context): SubtitleRenderer =
            instance ?: synchronized(this) { instance ?: SubtitleRenderer(context.applicationContext.assets).also { instance = it } }

        /** Overshoot then settle: 0.55 -> 1.1 -> 1.0. */
        fun pop(x: Float): Float = when {
            x >= 1f -> 1f
            x <= 0f -> 0.55f
            x < 0.6f -> {
                val u = x / 0.6f
                0.55f + 0.55f * (1f - (1f - u) * (1f - u))
            }
            else -> 1.1f - 0.1f * ((x - 0.6f) / 0.4f)
        }

        /** Character ranges of the words. */
        fun words(text: String): List<IntRange> =
            Regex("""\S+""").findAll(text).map { it.range }.toList()

        private fun weights(ws: List<IntRange>) = ws.map { (it.last - it.first + 2).toFloat() }

        /** Index of the word being said at [tUs] (-1 before the subtitle or with no words). */
        fun activeWord(ws: List<IntRange>, startUs: Long, endUs: Long, tUs: Long): Int {
            if (ws.isEmpty() || tUs < startUs) return -1
            val span = max(1L, endUs - startUs) * SPEECH_PART
            val f = ((tUs - startUs) / span).coerceIn(0f, 1f)
            val wt = weights(ws)
            val total = wt.sum()
            var acc = 0f
            for (i in ws.indices) {
                acc += wt[i] / total
                if (f < acc) return i
            }
            return ws.size - 1
        }

        fun wordStartUs(ws: List<IntRange>, i: Int, startUs: Long, endUs: Long): Long {
            val wt = weights(ws)
            val total = wt.sum()
            val before = (0 until i).sumOf { wt[it].toDouble() }.toFloat()
            return startUs + (max(1L, endUs - startUs) * SPEECH_PART * before / total).toLong()
        }
    }
}
