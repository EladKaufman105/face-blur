package com.elad.faceblur.edit

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.DetectMode
import com.elad.faceblur.analysis.DetectTuning
import java.util.TreeMap
import kotlin.math.max

/** What "Run detection again" does with the blurs that are there. */
enum class RerunMode(val id: String) {
    /** Every blur stays as it is; only faces that are not one of them come in. Never fewer blurs. */
    ADD("add"),

    /** The automatic blurs nobody changed are taken out and the new run's come in; manual and edited blurs stay. */
    REPLACE("replace");

    companion object {
        /** The remembered choice's value (shared/prefs/choices.json), or null: ask. */
        fun of(id: String?): RerunMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The rules for a second detection of the same video, pure (the web's twin is lib/rerun.ts: same rules, same IoU,
 * [DetectTuning.SAME_FACE_IOU] on at least half the frames two blurs share).
 *
 *  - [RerunMode.ADD]: nothing there changes (manual, edited and turned-off blurs included); a found face is added
 *    only if it is not mostly the face of a blur there.
 *  - [RerunMode.REPLACE]: the automatic blurs the user never touched go; the new run's come in, except a face that is
 *    mostly one of the blurs that stay.
 *
 * The detection that is kept for the project (its notices) never gets weaker by a re-run: [detectAfter].
 */
object RerunMerge {
    /** The remembered-choice key (shared/prefs/choices.json). */
    const val CHOICE_KEY = "rerun.merge"

    /** A blur there: its id, whether the user made or changed it, and the boxes it covers. */
    class Existing(val id: Int, val edited: Boolean, val shown: TreeMap<Int, Box>)

    /** What to take out (by id) and which found faces come in. */
    class Plan(val removeIds: Set<Int>, val add: List<TreeMap<Int, Box>>)

    /**
     * A blur the user made or changed: manual, turned off, resized, moved by hand, or shortened, lengthened or split (its start and
     * end are no longer where the detector's path begins and ends). A re-run never takes it away.
     */
    fun isEdited(t: EditTrack): Boolean =
        t.manual || t.off || t.scale != 1f || t.keys.isNotEmpty() || t.path.isEmpty() ||
            t.start != t.path.firstKey() || t.end != t.path.lastKey()

    fun plan(existing: List<Existing>, found: List<TreeMap<Int, Box>>, mode: RerunMode): Plan {
        val keep = if (mode == RerunMode.ADD) existing else existing.filter { it.edited }
        val keepIds = keep.mapTo(HashSet()) { it.id }
        val remove = existing.filter { it.id !in keepIds }.mapTo(HashSet()) { it.id }
        val add = found.filter { f -> f.isNotEmpty() && keep.none { DetectTuning.sameFace(it.shown, f) } }
        return Plan(remove, add)
    }

    /**
     * The detection mode kept for the project after a run of [now] over one that was [before]. Replace: the new run's own.
     * Add: the stricter one for the notices: selfie, then fast (each says faces may be missed), else extended over normal.
     */
    fun detectAfter(before: DetectMode, now: DetectMode, mode: RerunMode): DetectMode {
        if (mode == RerunMode.REPLACE) return now
        fun rank(m: DetectMode) = when (m) {
            DetectMode.SELFIE -> 3
            DetectMode.FAST -> 2
            DetectMode.NORMAL -> 0
            DetectMode.EXTENDED -> 1
        }
        // A notice (selfie, fast) wins over no notice; between two without one, the stronger detection (extended).
        val noticed = listOf(before, now).filter { it == DetectMode.SELFIE || it == DetectMode.FAST }
        return if (noticed.isNotEmpty()) noticed.maxBy { rank(it) } else listOf(before, now).maxBy { rank(it) }
    }

    /** The sensitivity kept for the project: the new run's for Replace, the higher of the two for Add. */
    fun sensitivityAfter(before: Float, now: Float, mode: RerunMode): Float =
        if (mode == RerunMode.REPLACE) now else max(before, now)

    /**
     * A found face's frames moved to this model's frames, when the two runs' frame times differ (a re-run of one video
     * gives the same frames, and then [path] comes back as it is): each frame takes the box of the nearest found one, no gaps.
     */
    fun remap(path: TreeMap<Int, Box>, from: LongArray, to: LongArray): TreeMap<Int, Box> {
        if (from.contentEquals(to) || path.isEmpty() || from.isEmpty() || to.isEmpty()) return path
        val first = nearest(to, from[path.firstKey().coerceIn(0, from.size - 1)])
        val last = max(first, nearest(to, from[path.lastKey().coerceIn(0, from.size - 1)]))
        val out = TreeMap<Int, Box>()
        for (j in first..last) {
            val k = nearest(from, to[j])
            out[j] = (path.floorEntry(k) ?: path.firstEntry()).value
        }
        return out
    }

    private fun nearest(times: LongArray, us: Long): Int {
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] < us) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && us - times[lo - 1] <= times[lo] - us) lo - 1 else lo
    }
}
