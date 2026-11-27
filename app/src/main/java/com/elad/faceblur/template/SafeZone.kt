package com.elad.faceblur.template

import com.elad.faceblur.gen.SafeZones

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
            // The video as it is: only a 9:16 portrait one (give or take a little), as the platforms show it.
            com.elad.faceblur.edit.ExportAspect.ORIGINAL -> sourceHeight > 0 && sourceWidth.toFloat() / sourceHeight in 0.53f..0.60f
            else -> false
        }

        /**
         * Measured on a 1080x1920 frame from each platform's published templates and guides
         * (sources in docs/specs/safe-zones.md). The values are in shared/catalog/safezones.json,
         * shared with the web app (generated into [SafeZones]).
         */
        fun of(p: Platform): SafeZone = when (p) {
            Platform.TIKTOK -> SafeZone(top = SafeZones.TIKTOK_TOP, bottom = SafeZones.TIKTOK_BOTTOM, left = SafeZones.TIKTOK_LEFT, right = SafeZones.TIKTOK_RIGHT)
            Platform.REELS -> SafeZone(top = SafeZones.REELS_TOP, bottom = SafeZones.REELS_BOTTOM, left = SafeZones.REELS_LEFT, right = SafeZones.REELS_RIGHT)
            Platform.SHORTS -> SafeZone(top = SafeZones.SHORTS_TOP, bottom = SafeZones.SHORTS_BOTTOM, left = SafeZones.SHORTS_LEFT, right = SafeZones.SHORTS_RIGHT)
        }
    }
}
