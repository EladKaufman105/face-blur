package com.elad.faceblur.template

import com.elad.faceblur.subs.SubtitleStyle
import kotlin.math.max
import kotlin.math.min

/** The short-video platforms that cover part of the picture with their own screens. */
enum class Platform { TIKTOK, REELS, SHORTS }

/**
 * The part of a vertical frame a platform's own interface covers, as fractions of the frame
 * (measured on a 1080x1920 frame): [top] the status bar and tabs, [bottom] the account name,
 * caption and navigation, [left] and [right] the margins (the right one holds the like / share
 * column). What is left in the middle is where subtitles and a watermark stay readable.
 */
data class SafeZone(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    /** The safe area as fractions of the frame: left, top, right, bottom edges. */
    val safeLeft get() = left
    val safeTop get() = top
    val safeRight get() = 1f - right
    val safeBottom get() = 1f - bottom

    /** The height of the subtitle text block as a fraction of a frame [aspect] (w/h) wide, for [lines] lines of size [size]. */
    private fun blockHeight(size: Float, lines: Int, aspect: Float): Float {
        // The renderer's text is 5.5% of the short side; a line takes about 1.35 of that.
        val shortOverHeight = min(aspect, 1f)
        return lines * 1.35f * SUBTITLE_BASE * size * shortOverHeight
    }

    /**
     * The subtitle position (the center of its block, as a fraction of the frame's height) nearest
     * [position] at which the block stays inside the safe area.
     */
    fun fitSubtitlePosition(position: Float, size: Float, lines: Int = 2, aspect: Float = 9f / 16f): Float {
        val half = blockHeight(size, lines, aspect) / 2f
        val lo = safeTop + half
        val hi = safeBottom - half
        return if (lo > hi) (safeTop + safeBottom) / 2f else position.coerceIn(lo, hi)
    }

    /** [style] with its position moved inside the safe area (not moved if it's already inside). */
    fun fitSubtitles(style: SubtitleStyle, lines: Int = 2, aspect: Float = 9f / 16f): SubtitleStyle =
        style.copy(position = fitSubtitlePosition(style.position, style.size, lines, aspect))

    /** The watermark's margins in pixels for a [width]x[height] frame: at least [base], and the safe area's. */
    fun watermarkMargins(width: Float, height: Float, base: Float) = Margins(
        left = max(base, left * width),
        top = max(base, top * height),
        right = max(base, right * width),
        bottom = max(base, bottom * height),
    )

    /** Margins in pixels. */
    data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

    companion object {
        /** Subtitle text height as a fraction of the short side, the renderer's value. */
        private const val SUBTITLE_BASE = 0.055f

        /**
         * Measured on a 1080x1920 frame from each platform's published templates and guides
         * (sources in docs/specs/safe-zones.md). TikTok: top 240 px, bottom 660 px, left 120 px, right
         * 180 px (its button column). Reels: 14% top, 35% bottom, 6% at the sides. Shorts: top 288 px
         * (15%), bottom 672 px (35%), left 48 px, right 192 px.
         */
        fun of(p: Platform): SafeZone = when (p) {
            Platform.TIKTOK -> SafeZone(top = 240f / 1920f, bottom = 660f / 1920f, left = 120f / 1080f, right = 180f / 1080f)
            Platform.REELS -> SafeZone(top = 0.14f, bottom = 0.35f, left = 0.06f, right = 0.06f)
            Platform.SHORTS -> SafeZone(top = 288f / 1920f, bottom = 672f / 1920f, left = 48f / 1080f, right = 192f / 1080f)
        }

        /** Left (or top) edge of something [len] long in grid cell [cell] (0 start, 1 middle, 2 end) of [total], inside the margins. */
        fun place(cell: Int, len: Float, total: Float, startMargin: Float, endMargin: Float): Float = when (cell) {
            0 -> startMargin
            1 -> startMargin + (total - startMargin - endMargin - len) / 2f
            else -> total - endMargin - len
        }
    }
}
