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
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one subtitle in a [SubtitleStyle] at a moment in time: the same code for the editor's
 * preview and for burning into the video, so what you see is what you get.
 *
 * Word timings come from speech recognition when known (one start time per word of a block);
 * otherwise they're spread over the subtitle in proportion to word length.
 */
class SubtitleRenderer private constructor(private val assets: AssetManager?, private val fontsDir: File?) {
    private val typefaces = HashMap<SubFont, Typeface>()
    private val custom = HashMap<String, Typeface?>()
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

    /** An imported font by file name (null if it's gone or doesn't load). */
    fun customTypeface(name: String): Typeface? = synchronized(custom) {
        custom.getOrPut(name) {
            val f = fontsDir?.let { File(it, name) }
            if (f == null || !f.exists()) {
                null
            } else {
                try {
                    // Bold for variable fonts; static ones ignore it.
                    Typeface.Builder(f).setFontVariationSettings("'wght' 700").build()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /** Forget a deleted / replaced imported font. */
    fun forget(name: String) = synchronized(custom) { custom.remove(name) }

    fun typeface(style: SubtitleStyle): Typeface =
        style.customFont?.let { customTypeface(it) } ?: typeface(style.font)

    /** Word start times of a block: the given ones, or spread by word length. */
    private class Timing(val starts: LongArray, val endUs: Long) {
        /** The word being said (-1 before the first one). */
        fun active(tUs: Long): Int {
            var a = -1
            for (i in starts.indices) if (starts[i] <= tUs) a = i else break
            return a
        }

        fun start(i: Int) = starts[i]
        fun end(i: Int) = if (i + 1 < starts.size) starts[i + 1] else endUs
    }

    private fun timing(text: String, times: LongArray?, startUs: Long, endUs: Long): Timing {
        val ws = words(text)
        if (times != null && times.size == ws.size) return Timing(times, endUs)
        return Timing(LongArray(ws.size) { wordStartUs(ws, it, startUs, endUs) }, endUs)
    }

    /** What changes between frames: the words in play and, while animating, the time. */
    fun frameKey(
        blocks: List<String>,
        startUs: Long,
        endUs: Long,
        tUs: Long,
        style: SubtitleStyle,
        times: List<LongArray?>? = null,
    ): Long {
        var k = 17L
        var anim = false
        val since = (tUs - startUs) / 1000f
        val until = (endUs - tUs) / 1000f
        blocks.forEachIndexed { bi, b ->
            val tm = timing(b, times?.getOrNull(bi), startUs, endUs)
            val a = tm.active(tUs)
            k = k * 31 + a
            if (style.effect == SubEffect.LETTERS) k = k * 31 + visibleLetters(b, tm, tUs)
            if (a >= 0) {
                val inWord = (tUs - tm.start(a)) / 1000f
                anim = anim || when (style.effect) {
                    SubEffect.ONE_WORD, SubEffect.WORD_POP -> inWord < WORD_POP_MS
                    SubEffect.WORD_FADE -> inWord < WORD_FADE_MS
                    SubEffect.BOUNCE -> inWord < BOUNCE_MS
                    SubEffect.SHAKE -> true
                    else -> false
                }
            }
        }
        anim = anim || when (style.effect) {
            SubEffect.FADE -> since < FADE_MS || until < FADE_MS
            SubEffect.POP -> since < POP_MS
            SubEffect.SLIDE_UP -> since < SLIDE_MS || until < FADE_MS
            SubEffect.ZOOM -> since < ZOOM_MS
            SubEffect.ROTATE -> since < ROTATE_MS
            SubEffect.GLITCH -> since < GLITCH_MS
            SubEffect.FLICKER -> since < FLICKER_MS
            else -> false
        }
        return if (anim) k * 1_000_003L + tUs / 1000 else k
    }

    /** How a word looks right now (relative to its place in the layout). */
    private class WordLook(
        var visible: Boolean = true,
        var alpha: Float = 1f,
        var scale: Float = 1f,
        var dy: Float = 0f,
        var rotate: Float = 0f,
        var accent: Boolean = false,
    ) {
        val plain get() = visible && alpha >= 0.999f && scale == 1f && dy == 0f && rotate == 0f && !accent
    }

    /**
     * Draws the subtitle made of [blocks] (one paragraph each, e.g. Hebrew then English) shown
     * from [startUs] to [endUs], at [tUs], into a [w]×[h] frame. [times]: per block, when each
     * word starts (µs), if known. Returns where the text is (for tapping it), or null if nothing
     * was drawn.
     */
    fun draw(
        canvas: Canvas,
        blocks: List<String>,
        startUs: Long,
        endUs: Long,
        tUs: Long,
        style: SubtitleStyle,
        w: Int,
        h: Int,
        times: List<LongArray?>? = null,
    ): RectF? {
        if (blocks.none { it.isNotBlank() } || w <= 0 || h <= 0) return null
        val effect = style.effect
        val oneWord = effect == SubEffect.ONE_WORD
        val base = min(w, h) * 0.055f * style.size * (if (oneWord) 1.25f else 1f)
        val maxW = (w * 0.86f).toInt()
        val tf = typeface(style)

        // Whole-subtitle motion.
        var alpha = 1f
        var scale = 1f
        var dy = 0f
        var rotate = 0f
        var glitch = 0f
        val since = (tUs - startUs) / 1000f
        val until = (endUs - tUs) / 1000f
        when (effect) {
            SubEffect.FADE -> alpha = (min(since / FADE_MS, until / FADE_MS)).coerceIn(0f, 1f)
            SubEffect.POP -> {
                scale = pop(since / POP_MS)
                alpha = (since / 70f).coerceIn(0f, 1f)
            }
            SubEffect.SLIDE_UP -> {
                val u = easeOut((since / SLIDE_MS).coerceIn(0f, 1f))
                dy = (1f - u) * base * 1.4f
                alpha = min(u, (until / FADE_MS).coerceIn(0f, 1f))
            }
            SubEffect.ZOOM -> {
                val u = easeOut((since / ZOOM_MS).coerceIn(0f, 1f))
                scale = 1.7f - 0.7f * u
                alpha = u
            }
            SubEffect.ROTATE -> {
                val x = (since / ROTATE_MS).coerceIn(0f, 1f)
                rotate = -16f * (1f - easeOut(x))
                scale = pop(x)
                alpha = (since / 80f).coerceIn(0f, 1f)
            }
            SubEffect.GLITCH -> if (since < GLITCH_MS) {
                val fade = 1f - since / GLITCH_MS
                glitch = base * 0.28f * fade * (if (((since / 45f).toInt() % 2) == 0) 1f else -1f)
            }
            SubEffect.FLICKER -> if (since < FLICKER_MS) {
                alpha = FLICKER[((since / 50f).toInt()).coerceIn(0, FLICKER.size - 1)]
            }
            else -> Unit
        }

        // Lay out each block (one word of each in one-word mode).
        class Block(
            val layout: StaticLayout,
            val paint: TextPaint,
            val text: String,
            val words: List<IntRange>,
            val active: Int,
            val size: Float,
            val looks: List<WordLook>,
            val visibleEnd: Int,
        )
        val laid = ArrayList<Block>()
        blocks.forEachIndexed { bi, full ->
            if (full.isBlank()) return@forEachIndexed
            val ws = words(full)
            val tm = timing(full, times?.getOrNull(bi), startUs, endUs)
            val active = tm.active(tUs)
            val size = if (laid.isEmpty()) base else base * 0.86f
            val text: String
            val wordRanges: List<IntRange>
            if (oneWord) {
                if (active < 0) return@forEachIndexed
                text = full.substring(ws[active].first, ws[active].last + 1)
                wordRanges = listOf(text.indices)
                scale = min(scale, pop((tUs - tm.start(active)) / 1000f / WORD_POP_MS))
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
            // Per word: hidden until said, popping / fading in, the one being said standing out.
            val looks = wordRanges.indices.map { i ->
                val look = WordLook()
                if (oneWord) return@map look
                val said = i <= active
                val inWord = if (said) (tUs - tm.start(i)) / 1000f else 0f
                when (effect) {
                    SubEffect.WORD_POP -> {
                        look.visible = said
                        if (said) look.scale = pop(inWord / WORD_POP_MS)
                    }
                    SubEffect.WORD_FADE -> {
                        look.visible = said
                        if (said) {
                            val u = (inWord / WORD_FADE_MS).coerceIn(0f, 1f)
                            look.alpha = u
                            look.dy = (1f - easeOut(u)) * size * 0.35f
                        }
                    }
                    SubEffect.ACTIVE_ZOOM -> if (i == active) {
                        // As big as fits into the spaces around it without covering its neighbors.
                        look.scale = 1.12f
                        look.accent = true
                    }
                    SubEffect.BOUNCE -> if (i == active) {
                        val u = (inWord / BOUNCE_MS).coerceIn(0f, 1f)
                        look.dy = -size * 0.3f * sin(PI.toFloat() * u)
                        look.accent = true
                    }
                    SubEffect.SHAKE -> if (i == active) {
                        look.rotate = 7f * sin(2f * PI.toFloat() * inWord / 160f)
                        look.scale = 1.04f
                        look.accent = true
                    }
                    else -> Unit
                }
                look
            }
            val visibleEnd = when {
                oneWord -> text.length
                effect == SubEffect.TYPEWRITER -> if (active < 0) 0 else wordRanges[active].last + 1
                effect == SubEffect.LETTERS -> visibleLetters(text, tm, tUs)
                else -> text.length
            }
            laid += Block(layout, tp, text, wordRanges, if (oneWord) 0 else active, size, looks, visibleEnd)
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
        canvas.translate(0f, dy)
        if (rotate != 0f) canvas.rotate(rotate, w / 2f, cy)
        if (scale != 1f) canvas.scale(scale, scale, w / 2f, cy)
        val layer = if (alpha < 0.999f) canvas.saveLayerAlpha(0f, -h.toFloat(), w.toFloat(), 2f * h, (alpha * 255).toInt()) else -1

        for (b in laid) {
            val layout = b.layout
            val size = b.size
            val visibleEnd = b.visibleEnd
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
                wordRect(layout, b.words[b.active], size, rect, 0.2f, 0.06f)
                boxPaint.color = style.accentColor
                canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, boxPaint)
            }

            // Text not shown yet keeps its place in the layout but is clipped away (with its
            // outline and shadow).
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

            val highlight = if (effect == SubEffect.HIGHLIGHT && b.active >= 0) b.words[b.active] else null
            if (glitch != 0f) {
                // Red and cyan copies on either side.
                canvas.save()
                canvas.translate(glitch, 0f)
                drawText(canvas, b.text, b.paint, maxW, style, size, null, overrideColor = 0xCCFF2D55.toInt())
                canvas.translate(-2f * glitch, 0f)
                drawText(canvas, b.text, b.paint, maxW, style, size, null, overrideColor = 0xCC3DF2FF.toInt())
                canvas.restore()
            }
            if (b.looks.all { it.plain }) {
                drawText(canvas, b.text, b.paint, maxW, style, size, highlight)
            } else {
                // The plain words together, then each animated word on its own, transformed
                // around its own center and clipped to itself.
                val plain = Path()
                b.looks.forEachIndexed { i, look ->
                    if (look.plain) {
                        wordRect(layout, b.words[i], size, rect, 0.12f, 0.12f)
                        plain.addRect(rect, Path.Direction.CW)
                    }
                }
                if (!plain.isEmpty) {
                    canvas.save()
                    canvas.clipPath(plain)
                    drawText(canvas, b.text, b.paint, maxW, style, size, highlight)
                    canvas.restore()
                }
                b.looks.forEachIndexed { i, look ->
                    if (look.plain || !look.visible || look.alpha <= 0.01f) return@forEachIndexed
                    val r = b.words[i]
                    wordRect(layout, r, size, rect, 0.14f, 0.2f)
                    val cx = rect.centerX()
                    val wcy = rect.centerY()
                    canvas.save()
                    canvas.translate(0f, look.dy)
                    if (look.rotate != 0f) canvas.rotate(look.rotate, cx, wcy)
                    if (look.scale != 1f) canvas.scale(look.scale, look.scale, cx, wcy)
                    canvas.clipRect(rect)
                    val l = if (look.alpha < 0.999f) canvas.saveLayerAlpha(rect, (look.alpha * 255).toInt()) else -1
                    drawText(canvas, b.text, b.paint, maxW, style, size, if (look.accent) r else highlight)
                    if (l >= 0) canvas.restoreToCount(l)
                    canvas.restore()
                }
            }

            canvas.restore()
            top += layout.height + gap
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restore()
        return bounds
    }

    /** Bounds of a word in its layout, padded by fractions of the text size. */
    private fun wordRect(layout: StaticLayout, r: IntRange, size: Float, out: RectF, padX: Float, padY: Float) {
        path.reset()
        layout.getSelectionPath(r.first, r.last + 1, path)
        path.computeBounds(out, true)
        val line = layout.getLineForOffset(r.first)
        out.top = min(out.top, layout.getLineTop(line).toFloat())
        out.bottom = max(out.bottom, layout.getLineBottom(line).toFloat())
        out.inset(-size * padX, -size * padY)
    }

    /**
     * Glow, outline + shadow and fill passes of a block's text; [accent] in the accent color.
     * [overrideColor]: one flat pass in that color (glitch copies).
     */
    private fun drawText(
        canvas: Canvas,
        text: String,
        tp: TextPaint,
        maxW: Int,
        style: SubtitleStyle,
        size: Float,
        accent: IntRange?,
        overrideColor: Int? = null,
    ) {
        fun pass(t: CharSequence) {
            StaticLayout.Builder.obtain(t, 0, t.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.02f)
                .build()
                .draw(canvas)
        }
        if (overrideColor != null) {
            tp.clearShadowLayer()
            tp.style = Paint.Style.FILL
            tp.color = overrideColor
            pass(text)
            return
        }
        if (style.glow) {
            tp.style = Paint.Style.FILL
            tp.color = style.textColor or 0xFF000000.toInt()
            tp.setShadowLayer(size * 0.5f, 0f, 0f, style.textColor or 0xFF000000.toInt())
            pass(text)
            pass(text)
            tp.clearShadowLayer()
        }
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
            pass(text)
            tp.clearShadowLayer()
        }
        tp.style = Paint.Style.FILL
        tp.color = style.textColor
        if (accent != null) {
            val sp = SpannableString(text)
            sp.setSpan(ForegroundColorSpan(style.accentColor), accent.first, accent.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pass(sp)
        } else {
            pass(text)
        }
    }

    private fun trimEnd(text: String, s: Int, e: Int): Int {
        var end = e
        while (end > s && text[end - 1].isWhitespace()) end--
        return end
    }

    /** Characters shown so far, letter by letter through each word as it's said. */
    private fun visibleLetters(text: String, tm: Timing, tUs: Long): Int {
        val ws = words(text)
        val a = tm.active(tUs)
        if (a < 0) return 0
        val r = ws[a]
        val len = r.last - r.first + 1
        // Each word is typed out over the first part of its time.
        val span = max(1L, ((tm.end(a) - tm.start(a)) * 0.7f).toLong())
        val f = ((tUs - tm.start(a)).toFloat() / span).coerceIn(0f, 1f)
        return r.first + max(1, (len * f).toInt().coerceAtMost(len))
    }

    companion object {
        private const val FADE_MS = 160f
        private const val POP_MS = 260f
        private const val WORD_POP_MS = 180f
        private const val WORD_FADE_MS = 200f
        private const val BOUNCE_MS = 260f
        private const val SLIDE_MS = 260f
        private const val ZOOM_MS = 240f
        private const val ROTATE_MS = 320f
        private const val GLITCH_MS = 380f
        private const val FLICKER_MS = 500f
        private val FLICKER = floatArrayOf(0.1f, 0.9f, 0.2f, 1f, 0.35f, 1f, 0.6f, 1f, 0.8f, 1f)

        /** Words are spread over this part of the subtitle (speech ends a little before it does). */
        private const val SPEECH_PART = 0.92f

        @Volatile
        private var instance: SubtitleRenderer? = null

        fun get(context: Context): SubtitleRenderer =
            instance ?: synchronized(this) {
                instance ?: context.applicationContext.let {
                    SubtitleRenderer(it.assets, File(it.filesDir, "fonts"))
                }.also { instance = it }
            }

        private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

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

        /** Index of the word being said at [tUs], spreading words by length (-1 before the subtitle). */
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
