package com.elad.faceblur.edit

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Det
import com.elad.faceblur.analysis.OfflineTracker
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One blur in the editor.
 *
 * @param path base positions per frame (normalized upright frame). Outside its frames the base
 *   position is held and moves with the camera (within the shot).
 * @param start / end first and last frame the blur is shown (inclusive).
 * @param scale size multiplier set by the user.
 * @param keys user position corrections: frame -> (dx, dy) normalized, linearly interpolated
 *   between keyframes and held before the first / after the last.
 */
class EditTrack(
    val id: Int,
    val manual: Boolean,
    val path: TreeMap<Int, Box>,
    var start: Int,
    var end: Int,
    var scale: Float = 1f,
    val keys: TreeMap<Int, FloatArray> = TreeMap(),
) {
    fun copy() = EditTrack(
        id, manual, TreeMap(path), start, end, scale,
        TreeMap<Int, FloatArray>().also { m -> keys.forEach { (k, v) -> m[k] = v.copyOf() } },
    )
}

/** When a word is said (media time, ms). */
class TimedWord(val startMs: Long, val endMs: Long)

/** A new subtitle sentence from speech recognition. */
class NewCue(val startMs: Long, val endMs: Long, val he: String, val en: String, val src: String, val words: List<TimedWord>?)

/**
 * One subtitle: time range in milliseconds of media time, Hebrew and English text (either may
 * be empty).
 *
 * @param group subtitles split from one sentence share a group; they're merged back when the
 *   words per subtitle change.
 * @param src the spoken language of the text, "he" or "en" ("" if unknown).
 * @param words when each word of the spoken text is said, if known.
 */
class Cue(
    val id: Int,
    var startMs: Long,
    var endMs: Long,
    var he: String,
    var en: String,
    var group: Int = id,
    var src: String = "",
    var words: List<TimedWord>? = null,
) {
    fun copy() = Cue(id, startMs, endMs, he, en, group, src, words)

    /** The text in the spoken language (Hebrew if unknown). */
    val srcText get() = when (src) {
        "en" -> en
        "he" -> he
        else -> he.ifBlank { en }
    }

    fun text(lang: SubLang): String = when (lang) {
        SubLang.HE -> he
        SubLang.EN -> en
        SubLang.BOTH -> listOf(he, en).filter { it.isNotBlank() }.joinToString("\n")
        SubLang.NONE -> ""
    }

    /** Paragraphs to show for [lang] (Hebrew above English for both). */
    fun blocks(lang: SubLang): List<String> = when (lang) {
        SubLang.HE -> listOf(he)
        SubLang.EN -> listOf(en)
        SubLang.BOTH -> listOf(he, en)
        SubLang.NONE -> emptyList()
    }.filter { it.isNotBlank() }
}

/** Which subtitle text to show / burn in. */
enum class SubLang { NONE, EN, HE, BOTH }

/** A blur's shape in one frame: center and half-axes (rx in frame widths, ry in frame heights). */
class EditShape(val id: Int, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val manual: Boolean)

/**
 * The editable list of blurs over the analysis frames, with undo. Pure Kotlin: the UI wraps it
 * and observes [version].
 */
class EditModel(
    val times: LongArray,
    val cuts: IntArray,
    motion: FloatArray,
    /** frame height / width */
    val aspect: Float,
    val dets: Map<Int, List<Det>>,
    val fps: Float,
    autoTracks: List<TreeMap<Int, Box>>,
) {
    val frameCount = times.size
    private val offX: FloatArray
    private val offY: FloatArray
    private val shot = IntArray(frameCount)
    private val shotStarts: IntArray

    val tracks = ArrayList<EditTrack>()
    /** Subtitles, sorted by start. */
    val cues = ArrayList<Cue>()
    private var nextId = 1
    private var nextCueId = 1
    private val undo = ArrayDeque<Pair<List<EditTrack>, List<Cue>>>()

    /** Bumped on every change. */
    var version = 0
        private set

    init {
        val (x, y) = OfflineTracker.cameraOffsets(frameCount, motion, cuts.toList(), aspect)
        offX = x
        offY = y
        val starts = (listOf(0) + cuts.filter { it in 1 until frameCount }.sorted().distinct())
        shotStarts = starts.toIntArray()
        var s = 0
        for (i in 0 until frameCount) {
            while (s + 1 < shotStarts.size && shotStarts[s + 1] <= i) s++
            shot[i] = s
        }
        for (t in autoTracks) {
            if (t.isEmpty()) continue
            tracks += EditTrack(nextId++, false, TreeMap(t), t.firstKey(), t.lastKey())
        }
    }

    val canUndo get() = undo.isNotEmpty()

    fun track(id: Int): EditTrack? = tracks.firstOrNull { it.id == id }

    /** Frames of the shot containing [frame]. */
    fun shotRange(frame: Int): IntRange {
        val s = shot[frame.coerceIn(0, frameCount - 1)]
        val a = shotStarts[s]
        val b = if (s + 1 < shotStarts.size) shotStarts[s + 1] - 1 else frameCount - 1
        return a..b
    }

    // ------------------------------------------------------------------ geometry

    private fun hold(k: Int, b: Box, i: Int): Box =
        if (shot[k] != shot[i]) b
        else Box(b.cx + offX[i] - offX[k], b.cy + (offY[i] - offY[k]) / aspect, b.w, b.h)

    private fun base(t: EditTrack, i: Int): Box? {
        val p = t.path
        p[i]?.let { return it }
        val lo = p.floorEntry(i)
        val hi = p.ceilingEntry(i)
        return when {
            lo == null && hi == null -> null
            lo == null -> hold(hi!!.key, hi.value, i)
            hi == null -> hold(lo.key, lo.value, i)
            shot[lo.key] == shot[i] && shot[hi.key] == shot[i] -> {
                // Interpolate in stabilized coordinates, so the gap moves with the camera.
                val f = (i - lo.key).toFloat() / (hi.key - lo.key)
                val a = lo.value
                val c = hi.value
                val ax = a.cx - offX[lo.key]; val ay = a.cy - offY[lo.key] / aspect
                val cx = c.cx - offX[hi.key]; val cy = c.cy - offY[hi.key] / aspect
                Box(
                    ax + (cx - ax) * f + offX[i], ay + (cy - ay) * f + offY[i] / aspect,
                    a.w + (c.w - a.w) * f, a.h + (c.h - a.h) * f,
                )
            }
            i - lo.key <= hi.key - i -> hold(lo.key, lo.value, i)
            else -> hold(hi.key, hi.value, i)
        }
    }

    private fun correction(t: EditTrack, i: Int): FloatArray? {
        val k = t.keys
        if (k.isEmpty()) return null
        val lo = k.floorEntry(i)
        val hi = k.ceilingEntry(i)
        return when {
            lo == null -> hi!!.value
            hi == null || lo.key == hi.key -> lo.value
            else -> {
                val f = (i - lo.key).toFloat() / (hi.key - lo.key)
                floatArrayOf(lo.value[0] + (hi.value[0] - lo.value[0]) * f, lo.value[1] + (hi.value[1] - lo.value[1]) * f)
            }
        }
    }

    /** Position without the user's size factor (any frame, even outside start..end). */
    fun rawBox(t: EditTrack, i: Int): Box? {
        val b = base(t, i) ?: return null
        val c = correction(t, i) ?: return b
        return Box(b.cx + c[0], b.cy + c[1], b.w, b.h)
    }

    /** Final box of [t] at frame [i], or null if the blur isn't shown then. */
    fun boxAt(t: EditTrack, i: Int): Box? {
        if (i < t.start || i > t.end) return null
        val b = rawBox(t, i) ?: return null
        return Box(b.cx, b.cy, b.w * t.scale, b.h * t.scale)
    }

    /** Shapes as rendered (same geometry as the render pass), for the editor overlay. */
    fun shapesAt(i: Int, padding: Float): List<EditShape> = tracks.mapNotNull { t ->
        val b = boxAt(t, i) ?: return@mapNotNull null
        EditShape(t.id, b.cx, b.cy - 0.06f * b.h, b.w / 2f * padding, b.h / 2f * padding * 1.18f, t.manual)
    }

    /**
     * Blur under the point (normalized), smallest first; [minR] is the minimum hit radius in
     * frame widths so tiny blurs stay tappable.
     */
    fun hitTest(i: Int, x: Float, y: Float, padding: Float, minR: Float): Int? =
        shapesAt(i, padding)
            .filter { s ->
                val rx = max(s.rx, minR)
                val ry = max(s.ry, minR / aspect)
                hypot((x - s.cx) / rx, (y - s.cy) / ry) <= 1f
            }
            .minByOrNull { it.rx * it.ry }?.id

    /** A raw detection under the point near frame [i] (any score), to size a new blur. */
    fun detectionAt(i: Int, x: Float, y: Float): Box? {
        for (d in 0..3) for (j in intArrayOf(i - d, i + d)) {
            val hit = dets[j]?.filter { it.score >= 0.2f }
                ?.filter { abs(x - it.box.cx) <= it.box.w * 0.6f && abs(y - it.box.cy) <= it.box.h * 0.6f }
                ?.maxByOrNull { it.score }
            if (hit != null) return Box(hit.box.cx, hit.box.cy, hit.box.w, hit.box.h)
        }
        return null
    }

    /** Final boxes of every blur, frame by frame, for the render pass. */
    fun renderTracks(): List<TreeMap<Int, Box>> = tracks.mapNotNull { t ->
        val m = TreeMap<Int, Box>()
        for (i in max(0, t.start)..min(frameCount - 1, t.end)) boxAt(t, i)?.let { m[i] = it }
        m.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ edits

    private fun snapshot() {
        undo.addLast(tracks.map { it.copy() } to cues.map { it.copy() })
        while (undo.size > MAX_UNDO) undo.removeFirst()
    }

    private fun changed() {
        version++
    }

    fun undo(): Boolean {
        val (t, c) = undo.removeLastOrNull() ?: return false
        tracks.clear()
        tracks += t
        cues.clear()
        cues += c
        changed()
        return true
    }

    // ------------------------------------------------------------------ subtitles

    fun cue(id: Int): Cue? = cues.firstOrNull { it.id == id }

    /** The subtitle shown at [ms] (media time), if any. */
    fun cueAt(ms: Long): Cue? = cues.firstOrNull { ms >= it.startMs && ms < it.endMs }

    /** Replaces all subtitles (one undo step). */
    fun setCues(list: List<Triple<Long, Long, Pair<String, String>>>) {
        snapshot()
        cues.clear()
        for ((a, b, t) in list.sortedBy { it.first }) cues += Cue(nextCueId++, a, b, t.first, t.second)
        changed()
    }

    /** Replaces all subtitles with recognized sentences, each its own group (one undo step). */
    fun setSentences(list: List<NewCue>) {
        snapshot()
        cues.clear()
        for (n in list.sortedBy { it.startMs }) {
            val id = nextCueId++
            cues += Cue(id, n.startMs, n.endMs, n.he, n.en, id, n.src, n.words)
        }
        changed()
    }

    /**
     * Re-splits every sentence into subtitles of up to [n] words of the spoken language (0: whole
     * sentences), keeping the other language alongside. One undo step.
     */
    fun regroup(n: Int, undoable: Boolean = true) {
        if (cues.isEmpty()) return
        if (undoable) snapshot()
        val groups = LinkedHashMap<Int, MutableList<Cue>>()
        for (c in cues.sortedBy { it.startMs }) groups.getOrPut(c.group) { ArrayList() } += c
        val out = ArrayList<Cue>()
        for ((g, cs) in groups) out += Subtitles.split(Subtitles.merge(cs), n, g) { nextCueId++ }
        cues.clear()
        cues += out.sortedBy { it.startMs }
        changed()
    }

    /** Joins a subtitle with the next one. */
    fun mergeWithNext(id: Int): Boolean {
        val sorted = cues.sortedBy { it.startMs }
        val i = sorted.indexOfFirst { it.id == id }
        if (i < 0 || i + 1 >= sorted.size) return false
        snapshot()
        val a = sorted[i]
        val b = sorted[i + 1]
        val m = Subtitles.merge(listOf(a, b))
        a.endMs = m.endMs
        a.he = m.he
        a.en = m.en
        a.src = m.src
        a.words = m.words
        cues.remove(b)
        changed()
        return true
    }

    /** Replaces [find] with [replace] in every subtitle (both languages); returns how many. */
    fun replaceAll(find: String, replace: String, ignoreCase: Boolean = true): Int {
        if (find.isEmpty()) return 0
        var n = 0
        for (c in cues) {
            n += Regex.escape(find).toRegex(if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).findAll(c.he + "\n" + c.en).count()
        }
        if (n == 0) return 0
        snapshot()
        for (c in cues) {
            c.he = c.he.replace(find, replace, ignoreCase)
            c.en = c.en.replace(find, replace, ignoreCase)
        }
        changed()
        return n
    }

    /**
     * A translation of a whole group's sentence arriving: spread its words over the group's
     * subtitles in proportion to their spoken words. No undo step.
     */
    fun fillGroup(group: Int, translated: String, intoEnglish: Boolean) {
        val cs = cues.filter { it.group == group }.sortedBy { it.startMs }
        if (cs.isEmpty()) return
        val parts = Subtitles.spread(Subtitles.words(translated), cs.map { Subtitles.words(it.srcText).size })
        cs.forEachIndexed { i, c -> if (intoEnglish) c.en = parts[i] else c.he = parts[i] }
        changed()
    }

    fun editCue(id: Int, he: String, en: String) {
        val c = cue(id) ?: return
        snapshot()
        c.he = he
        c.en = en
        changed()
    }

    /** Fills in text without an undo step (a translation arriving). */
    fun setCueText(id: Int, he: String, en: String) {
        val c = cue(id) ?: return
        c.he = he
        c.en = en
        changed()
    }

    fun deleteCue(id: Int) {
        val c = cue(id) ?: return
        snapshot()
        cues.remove(c)
        changed()
    }

    /** New subtitle at [ms] lasting up to 2 s (until the next one); returns its id. */
    fun addCue(ms: Long, maxMs: Long): Int {
        snapshot()
        val next = cues.filter { it.startMs > ms }.minOfOrNull { it.startMs } ?: maxMs
        val c = Cue(nextCueId++, ms, min(next, ms + 2000).coerceAtLeast(ms + 300), "", "")
        cues += c
        cues.sortBy { it.startMs }
        changed()
        return c.id
    }

    /** Moves a subtitle edge; no undo snapshot (see [beginGesture]). */
    fun setCueEdge(id: Int, start: Boolean, ms: Long) {
        val c = cue(id) ?: return
        if (start) c.startMs = ms.coerceIn(0, c.endMs - 100) else c.endMs = ms.coerceAtLeast(c.startMs + 100)
        cues.sortBy { it.startMs }
        changed()
    }

    /** Call once at the start of a drag / pinch / slider move; the moves themselves don't snapshot. */
    fun beginGesture() = snapshot()

    fun delete(id: Int) {
        val t = track(id) ?: return
        snapshot()
        tracks.remove(t)
        changed()
    }

    /** Splits at [frame]: the original keeps start..frame-1, a new blur gets frame..end. */
    fun split(id: Int, frame: Int): Int? {
        val t = track(id) ?: return null
        if (frame <= t.start || frame > t.end) return null
        snapshot()
        val b = t.copy().let { EditTrack(nextId++, it.manual, it.path, frame, it.end, it.scale, it.keys) }
        t.end = frame - 1
        tracks.add(tracks.indexOf(t) + 1, b)
        changed()
        return b.id
    }

    fun setStart(id: Int, frame: Int, undoable: Boolean = true) {
        val t = track(id) ?: return
        if (undoable) snapshot()
        t.start = frame.coerceIn(0, t.end)
        changed()
    }

    fun setEnd(id: Int, frame: Int, undoable: Boolean = true) {
        val t = track(id) ?: return
        if (undoable) snapshot()
        t.end = frame.coerceIn(t.start, frameCount - 1)
        changed()
    }

    /** Moves the blur at [frame] (a keyframe there); no undo snapshot (see [beginGesture]). */
    fun moveBy(id: Int, frame: Int, dx: Float, dy: Float) {
        val t = track(id) ?: return
        if (frame < t.start || frame > t.end) return
        val c = correction(t, frame) ?: floatArrayOf(0f, 0f)
        t.keys[frame] = floatArrayOf(c[0] + dx, c[1] + dy)
        changed()
    }

    /** No undo snapshot (see [beginGesture]). */
    fun setScale(id: Int, scale: Float) {
        val t = track(id) ?: return
        t.scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        changed()
    }

    /** New blur at [frame], shown for a second (until tracked); returns its id. */
    fun add(frame: Int, cx: Float, cy: Float, w: Float, h: Float): Int {
        snapshot()
        val shotEnd = shotRange(frame).last
        val t = EditTrack(
            nextId++, true, TreeMap(mapOf(frame to Box(cx, cy, w, h))),
            frame, min(shotEnd, frame + fps.roundToInt()),
        )
        tracks += t
        changed()
        return t.id
    }

    /**
     * Puts tracked positions into [id]'s path. Earlier corrections are baked in first. A fresh
     * blur takes the tracked range; otherwise the range only grows.
     */
    fun applyTracking(id: Int, result: TreeMap<Int, Box>, fresh: Boolean, undoable: Boolean = true) {
        val t = track(id) ?: return
        if (result.isEmpty()) return
        if (undoable) snapshot()
        bake(t)
        t.path.putAll(result)
        if (fresh) {
            t.start = result.firstKey()
            t.end = result.lastKey()
        } else {
            t.start = min(t.start, result.firstKey())
            t.end = max(t.end, result.lastKey())
        }
        changed()
    }

    private fun bake(t: EditTrack) {
        if (t.keys.isEmpty()) return
        val m = TreeMap<Int, Box>()
        for (i in t.start..t.end) rawBox(t, i)?.let { m[i] = it }
        t.keys.clear()
        t.path.putAll(m)
    }

    companion object {
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 3f
        private const val MAX_UNDO = 60
    }
}
