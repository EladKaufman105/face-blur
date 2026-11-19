package com.elad.faceblur.edit

import com.elad.faceblur.track.FaceShape
import com.elad.faceblur.track.FaceSource
import com.elad.faceblur.track.FaceTimeline

/**
 * Feeds the editor player's live blur effect. The player's effect pipeline may stamp frames
 * on a different clock than the analysis pass, so the offset is learned from the first frame
 * it processes (always the first frame of the video: the player starts there).
 * Read on the GL thread, written on the main thread.
 */
class PreviewSource(private val firstTimeUs: Long) : FaceSource {
    @Volatile var timeline: FaceTimeline? = null
    @Volatile var enabled = true
    @Volatile private var offsetUs: Long? = null

    override fun facesAt(tUs: Long): List<FaceShape> {
        val off = offsetUs ?: (tUs - firstTimeUs).also { offsetUs = it }
        val tl = timeline
        if (!enabled || tl == null) return emptyList()
        return tl.facesAt(tUs - off)
    }
}
