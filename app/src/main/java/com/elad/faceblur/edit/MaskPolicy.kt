package com.elad.faceblur.edit

import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BgKey

/** Pure decisions about the people's masks (kept per frame), so they can be tested without a device. */
object MaskPolicy {
    /** Masks are per frame: they stay valid when the new detection has exactly the same frame times. */
    fun keepMasks(oldTimes: LongArray, newTimes: LongArray): Boolean = oldTimes.contentEquals(newTimes)

    /** A background that cuts out the people needs masks to be drawn at all. */
    fun needsMasks(bg: Background): Boolean = bg.active && bg.key == BgKey.PERSON

    /** The masks were lost to new frame times while the background needs them: they must be found again. */
    fun rerunPeople(bg: Background, masksKept: Boolean): Boolean = !masksKept && needsMasks(bg)

    /** An export would silently leave out the background (privacy): refuse it. */
    fun exportBlocked(bg: Background, masksReady: Boolean): Boolean = needsMasks(bg) && !masksReady
}
