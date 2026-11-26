package com.elad.faceblur.edit

import kotlin.math.max
import kotlin.math.min

/**
 * Stretches taken out of the video (sorted, not overlapping, ms): what's left plays back to
 * back. Maps a time in the source to its time in the result.
 */
class CutMap(ranges: List<LongArray>) {
    private val a: LongArray
    private val b: LongArray
    /** Removed before range k starts (µs). */
    private val before: LongArray

    init {
        val r = ranges.filter { it.size == 2 && it[1] > it[0] }.sortedBy { it[0] }
        a = LongArray(r.size) { r[it][0] * 1000 }
        b = LongArray(r.size) { r[it][1] * 1000 }
        before = LongArray(r.size)
        var sum = 0L
        for (k in r.indices) {
            before[k] = sum
            sum += b[k] - a[k]
        }
    }

    val isEmpty get() = a.isEmpty()
    val count get() = a.size

    /** Everything taken out (µs). */
    val removedUs: Long get() = if (a.isEmpty()) 0L else before.last() + (b.last() - a.last())

    /** Last range starting at or before [us], or -1. */
    private fun at(us: Long): Int {
        var lo = 0
        var hi = a.size - 1
        var k = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (a[m] <= us) { k = m; lo = m + 1 } else hi = m - 1
        }
        return k
    }

    /** Whether [us] (source time) was taken out. */
    fun isCut(us: Long): Boolean {
        val k = at(us)
        return k >= 0 && us < b[k]
    }

    /** End of the cut stretch [us] is in (µs), or -1. */
    fun cutEndUs(us: Long): Long {
        val k = at(us)
        return if (k >= 0 && us < b[k]) b[k] else -1L
    }

    /** The result's time for source time [us], or -1 if it was cut. */
    fun outUs(us: Long): Long {
        val k = at(us)
        if (k < 0) return us
        if (us < b[k]) return -1L
        return us - before[k] - (b[k] - a[k])
    }

    /** Source frames [n] kept in [startUs, endUs) (µs). */
    fun keptUs(startUs: Long, endUs: Long): Long {
        var kept = endUs - startUs
        for (k in a.indices) kept -= max(0L, min(b[k], endUs) - max(a[k], startUs))
        return max(0L, kept)
    }

    /** [startMs, endMs) in the result; null if all of it was cut. */
    fun mapMs(startMs: Long, endMs: Long): Pair<Long, Long>? {
        val s = startMs * 1000
        val e = endMs * 1000
        if (keptUs(s, e) <= 0L) return null
        // The first kept moment at or after the start, and the length that's kept.
        var from = s
        val k = at(s)
        if (k >= 0 && s < b[k]) from = b[k]
        val out = outUs(from)
        return out / 1000 to (out + keptUs(from, e)) / 1000
    }

    /** Copies of [cues] in the result's time (the .srt files); a subtitle cut out entirely is left out. */
    fun mapCues(cues: List<Cue>): List<Cue> = if (isEmpty) cues else cues.mapNotNull { c ->
        mapMs(c.startMs, c.endMs)?.let { (s, e) -> c.copy().also { it.startMs = s; it.endMs = e } }
    }
}
