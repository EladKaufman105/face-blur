package com.elad.faceblur.template

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

    companion object {
        /**
         * The safe-zone view aid is for a vertical (9:16) frame: the export's shape, or, with the
         * shape as shot, the video's own when it is vertical. Landscape and square frames have none.
         */
        fun appliesTo(export: com.elad.faceblur.edit.ExportAspect, sourceWidth: Int, sourceHeight: Int): Boolean = when (export) {
            com.elad.faceblur.edit.ExportAspect.VERTICAL -> true
            com.elad.faceblur.edit.ExportAspect.ORIGINAL -> sourceHeight > 0 && sourceWidth.toFloat() / sourceHeight <= 0.62f
            else -> false
        }

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
    }
}
