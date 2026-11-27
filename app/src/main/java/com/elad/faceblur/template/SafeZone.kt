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
         * Measured on a 1080x1920 frame from each platform's published templates and guides, re-verified 2026-11-27
         * (the same numbers as the web's lib/safezone.ts; sources and notes in docs/specs/safe-zones.md and docs/parity.md).
         * The values are in shared/catalog/safezones.json, shared with the web app (generated into SafeZones).
         * Px at 1080x1920 (top / bottom / left / right):
         *  - TikTok 240 / 660 / 120 / 300: TikTok's In-Feed Standard safe-zone template (720x1280, x1.5): 120 px at each side,
         *    and below y=840 the right side holds its 180 px button column, so 300 px in all (https://ads.tiktok.com/help/article/video-ads-specifications?lang=en;
         *    measured in https://www.manypixels.co/blog/social-media-design/tiktok-ad-specs and https://adkit.so/tools/safe-zones/tiktok).
         *    A long caption (the anchor templates) takes the bottom up to 1014 px.
         *  - Instagram Reels 269 (14%) / 672 (35%) / 65 (6%) / 65 (6%): Meta's Reels safe-zone guidance
         *    (https://www.facebook.com/business/ads-guide/update/video/facebook-facebook-reels, as quoted in https://billo.app/blog/meta-ads-safe-zones
         *    and https://www.hopperhq.com/blog/instagram-reel-size/). Its action rail, lower right, is wider than 6%: Meta asks 40% clear at the bottom there.
         *  - YouTube Shorts 288 / 672 / 48 / 192: Google's universal vertical safe zone, the rectangle x48..888, y288..1248
         *    (https://services.google.com/fh/files/misc/universalsafezones-youtube.pdf; read in https://adkit.so/tools/safe-zones/youtube
         *    and https://www.tella.com/help/editing/social-safe-zones.md).
         */
        fun of(p: Platform): SafeZone = when (p) {
            Platform.TIKTOK -> SafeZone(top = SafeZones.TIKTOK_TOP, bottom = SafeZones.TIKTOK_BOTTOM, left = SafeZones.TIKTOK_LEFT, right = SafeZones.TIKTOK_RIGHT)
            Platform.REELS -> SafeZone(top = SafeZones.REELS_TOP, bottom = SafeZones.REELS_BOTTOM, left = SafeZones.REELS_LEFT, right = SafeZones.REELS_RIGHT)
            Platform.SHORTS -> SafeZone(top = SafeZones.SHORTS_TOP, bottom = SafeZones.SHORTS_BOTTOM, left = SafeZones.SHORTS_LEFT, right = SafeZones.SHORTS_RIGHT)
        }
    }
}
