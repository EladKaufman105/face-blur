package com.elad.faceblur.edit

import java.util.NavigableMap

/**
 * Jumping over cut stretches while the editor plays. The player has no idea about cuts: the
 * editor watches the frames and seeks past each cut. These two pieces keep that from stuttering.
 */
object CutSkip {
    /**
     * Cuts closer than this (ms) are one: no frame fits between them, so playback shouldn't
     * seek twice (a second seek lands on the first cut's far side only to be thrown again).
     */
    const val JOIN_GAP_MS = 20L

    /**
     * Where the cut stretch that [ms] is in ends (ms), joined with the ones that follow it within
     * [gapMs]; -1 if [ms] isn't cut. [removed]: start to end, in ms.
     */
    fun skipEndMs(removed: NavigableMap<Long, Long>, ms: Long, gapMs: Long = JOIN_GAP_MS): Long {
        val k = removed.floorKey(ms) ?: return -1L
        var end = removed[k] ?: return -1L
        if (ms >= end) return -1L
        while (true) {
            // The next stretch starting after this one's start (they don't overlap, so it starts at or after [end]).
            val next = removed.higherEntry(removed.floorKey(end - 1) ?: break) ?: break
            if (next.key > end + gapMs || next.value <= end) break
            end = next.value
        }
        return end
    }
}

/** What to do about a frame shown inside a cut. */
sealed interface CutSkipStep {
    /** Nothing: a seek is on its way, or the end was already handled. */
    data object None : CutSkipStep

    /** Seek to this frame. */
    data class Seek(val frame: Int) : CutSkipStep

    /** The cut runs to the end of the video: stop there. */
    data object Finish : CutSkipStep
}

/**
 * Keeps one seek over a cut in flight at a time. Frames from before the seek lands keep arriving
 * and all land in the same cut: they must not start another seek (that restarts the player's
 * buffering and flickers), and a seek that never landed is retried after [retryMs] so playback
 * can't play through a cut or stall in it.
 */
class CutSkipGuard(private val retryMs: Long = RETRY_MS) {
    private var pendingTo = -1
    private var pendingAt = 0L
    private var finished = false

    /** A seek over a cut was made and hasn't been seen to land. */
    val pending: Boolean get() = pendingTo >= 0

    /** A frame outside any cut is shown (the seek landed, or there was none): nothing is pending. */
    fun cleared() {
        pendingTo = -1
        finished = false
    }

    /** A frame inside a cut is shown; playback should go on at [to] (>= [frameCount]: the cut runs to the end). */
    fun onCutFrame(to: Int, frameCount: Int, nowMs: Long): CutSkipStep {
        if (to >= frameCount) {
            if (finished) return CutSkipStep.None
            finished = true
            pendingTo = -1
            return CutSkipStep.Finish
        }
        finished = false
        if (pendingTo == to && nowMs - pendingAt < retryMs) return CutSkipStep.None
        pendingTo = to
        pendingAt = nowMs
        return CutSkipStep.Seek(to)
    }

    companion object {
        const val RETRY_MS = 400L
    }
}
