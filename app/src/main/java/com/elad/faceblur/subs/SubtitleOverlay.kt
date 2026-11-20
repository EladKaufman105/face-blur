package com.elad.faceblur.subs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay

/**
 * One subtitle to burn in: media-time range (µs), its paragraphs (e.g. Hebrew, English) and
 * each paragraph's word start times if known.
 */
class BurnCue(val startUs: Long, val endUs: Long, val blocks: List<String>, val times: List<LongArray?>? = null, val speaker: Int = 0)

/**
 * Burns subtitles into the video with [SubtitleRenderer]. A frame is redrawn only when its look
 * changes (next word, an animation running); two bitmaps take turns, because the overlay
 * uploads a bitmap again only when it gets a different one.
 */
class SubtitleOverlay(
    private val cues: List<BurnCue>,
    private val style: SubtitleStyle,
    private val renderer: SubtitleRenderer,
) : BitmapOverlay() {
    private var frameW = 0
    private var frameH = 0
    private var lastKey: Long? = null
    private var lastCue: BurnCue? = null
    private var lastStyle: SubtitleStyle = style
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var current = 0
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize.width != frameW || videoSize.height != frameH) {
            frameW = videoSize.width
            frameH = videoSize.height
            bitmaps.fill(null)
        }
        lastKey = null
        lastCue = null
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val cue = cues.firstOrNull { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
        if (cue == null || frameW <= 0) {
            lastCue = null
            return empty
        }
        val style = if (cue === lastCue) lastStyle else this.style.forSpeaker(cue.speaker)
        lastStyle = style
        val key = renderer.frameKey(cue.blocks, cue.startUs, cue.endUs, presentationTimeUs, style, cue.times)
        val shown = bitmaps[current]
        if (shown != null && cue === lastCue && key == lastKey) return shown
        current = 1 - current
        val bmp = bitmaps[current] ?: Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888).also { bitmaps[current] = it }
        bmp.eraseColor(Color.TRANSPARENT)
        renderer.draw(Canvas(bmp), cue.blocks, cue.startUs, cue.endUs, presentationTimeUs, style, frameW, frameH, cue.times)
        lastCue = cue
        lastKey = key
        return bmp
    }
}
