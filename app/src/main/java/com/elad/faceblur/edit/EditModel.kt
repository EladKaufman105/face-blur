package com.elad.faceblur.edit

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Det
import com.elad.faceblur.analysis.OfflineTracker
import com.elad.faceblur.subs.SubLanguages
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
    /** Turned off by the user: the face stays in the list but is not blurred (not shown, not exported). */
    var off: Boolean = false,
) {
    fun copy() = EditTrack(
        id, manual, TreeMap(path), start, end, scale,
        TreeMap<Int, FloatArray>().also { m -> keys.forEach { (k, v) -> m[k] = v.copyOf() } },
        off,
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
 * @param src the spoken language of the text, "he", "ar", "fa" or "en" ("" if unknown). The spoken text of
 *   the first three is in [he], English in [en]; the other one is the translation, in any of the four languages.
 * @param words when each word of the spoken text is said, if known.
 * @param speaker who says it: 0 unknown (shown like speaker 1), 1.. [Speakers.MAX].
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
    var speaker: Int = 0,
) {
    fun copy() = Cue(id, startMs, endMs, he, en, group, src, words, speaker)

    /** The text in the spoken language (Hebrew if unknown). */
    val srcText get() = when (src) {
        "en" -> en
        "he", "ar", "fa" -> he
        else -> he.ifBlank { en }
    }

    /** The translation ("" if none). */
    val otherText: String get() {
        val o = when (src) {
            "en" -> he
            "he", "ar", "fa" -> en
            else -> if (he.isBlank()) "" else en
        }
        return if (o.trim() == srcText.trim()) "" else o
    }

    /** Sets the spoken-language text and / or the translation. */
    fun setTexts(spoken: String, translation: String) {
        if (src == "en" || (src == "" && he.isBlank() && en.isNotBlank())) {
            en = spoken
            he = translation
        } else {
            he = spoken
            en = translation
        }
    }

    /** Start times (µs) of the spoken text's words, if known and still matching the text. */
    fun wordStartsUs(): LongArray? {
        val w = words ?: return null
        if (w.size != Subtitles.words(srcText).size) return null
        return LongArray(w.size) { w[it].startMs * 1000 }
    }

    /** [blocks] with each one's word start times (only the spoken text has them). */
    fun blockTimes(lang: SubLang): List<LongArray?> {
        val src = srcText
        val other = otherText
        val pairs = when (lang) {
            SubLang.ORIGINAL -> listOf(src to true)
            SubLang.TRANSLATED -> listOf(if (other.isBlank()) src to true else other to false)
            SubLang.BOTH -> listOf(src to true, other to false)
            SubLang.NONE -> emptyList()
        }.filter { it.first.isNotBlank() }
        val starts = wordStartsUs()
        return pairs.map { (_, spoken) -> if (spoken) starts else null }
    }

    /**
     * Paragraphs to show for [lang] (the spoken language above the translation for both).
     * Spoken words at [hide] (indices) are masked, e.g. ones bleeped out of the sound.
     */
    fun blocks(lang: SubLang, hide: Set<Int> = emptySet()): List<String> {
        val spoken = if (hide.isEmpty()) srcText else Subtitles.mask(srcText, hide)
        return when (lang) {
            SubLang.ORIGINAL -> listOf(spoken)
            SubLang.TRANSLATED -> listOf(otherText.ifBlank { spoken })
            SubLang.BOTH -> listOf(spoken, otherText)
            SubLang.NONE -> emptyList()
        }.filter { it.isNotBlank() }
    }
}

/** Speakers of subtitles. */
object Speakers {
    const val MAX = 6
}

/**
 * A sound effect placed on the video.
 * @param sound its id in the sound library ("builtin:whoosh", "user:file.wav").
 * @param durMs how long it plays.
 */
class SfxClip(val id: Int, val sound: String, var startMs: Long, val durMs: Long, var gain: Float = 1f) {
    fun copy() = SfxClip(id, sound, startMs, durMs, gain)
    val endMs get() = startMs + durMs
}

/** Everything the user edited, copied out to be saved (see [EditModel.exportState]). */
class EditState(
    val tracks: List<EditTrack>,
    val cues: List<Cue>,
    /** Bleeped stretches of the sound: start, end (ms). */
    val bleeps: List<LongArray>,
    val nextId: Int,
    val nextCueId: Int,
    val sfx: List<SfxClip> = emptyList(),
    /** Stretches cut out of the video: start, end (ms). */
    val removed: List<LongArray> = emptyList(),
)

/** Which subtitle text to show / burn in: as spoken, its translation, both, or none. */
enum class SubLang { NONE, ORIGINAL, TRANSLATED, BOTH }

/**
 * How sentences are cut into subtitles: at most [words] spoken words, or at most [chars]
 * characters, each; neither: whole sentences.
 */
data class SplitRule(val words: Int = 0, val chars: Int = 0) {
    val isSentence get() = words <= 0 && chars <= 0
}

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
    /** Stretches of sound to bleep: start ms -> end ms. */
    val bleeps = TreeMap<Long, Long>()
    /** Sound effects, by start. */
    val sfx = ArrayList<SfxClip>()
    private var nextSfxId = 1
    /** Stretches cut out of the video: start ms -> end ms (see [CutMap]). */
    val removed = TreeMap<Long, Long>()
    private class Snap(val tracks: List<EditTrack>, val cues: List<Cue>, val bleeps: Map<Long, Long>, val sfx: List<SfxClip>, val removed: Map<Long, Long>)
    private val undo = ArrayDeque<Snap>()
    /** Undone changes, to redo (cleared by any new change). */
    private val redo = ArrayDeque<Snap>()

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
    val canRedo get() = redo.isNotEmpty()

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
        if (t.off || i < t.start || i > t.end) return null
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

    /**
     * Crop centers per frame for a vertical / square export that follows the faces
     * ([cropW] x [cropH] as fractions of the frame).
     */
    fun reframePath(padding: Float, cropW: Float, cropH: Float): Pair<FloatArray, FloatArray> {
        val faces = ArrayList<List<FloatArray>>(frameCount)
        val ids = ArrayList<List<Int>>(frameCount)
        for (i in 0 until frameCount) {
            val sh = shapesAt(i, padding)
            faces += sh.map { floatArrayOf(it.cx, it.cy, it.rx * it.ry) }
            ids += sh.map { it.id }
        }
        return Reframer.path(faces, ids, shotStarts, fps, cropW, cropH)
    }

    /** Final boxes of every blur, frame by frame, for the render pass. */
    fun renderTracks(): List<TreeMap<Int, Box>> = tracks.mapNotNull { t ->
        val m = TreeMap<Int, Box>()
        for (i in max(0, t.start)..min(frameCount - 1, t.end)) boxAt(t, i)?.let { m[i] = it }
        m.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ edits

    private fun current() = Snap(tracks.map { it.copy() }, cues.map { it.copy() }, TreeMap(bleeps), sfx.map { it.copy() }, TreeMap(removed))

    private fun snapshot() {
        undo.addLast(current())
        while (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
    }

    /** A copy of everything edited, for saving. */
    fun exportState() = EditState(
        tracks.map { it.copy() }, cues.map { it.copy() },
        bleeps.map { (a, b) -> longArrayOf(a, b) }, nextId, nextCueId, sfx.map { it.copy() },
        removed.map { (a, b) -> longArrayOf(a, b) },
    )

    /** Puts back a saved state (no undo history). */
    fun restore(st: EditState) {
        tracks.clear()
        tracks += st.tracks
        cues.clear()
        cues += st.cues.sortedBy { it.startMs }
        bleeps.clear()
        for (b in st.bleeps) if (b.size == 2 && b[1] > b[0]) bleeps[b[0]] = b[1]
        nextId = max(st.nextId, (tracks.maxOfOrNull { it.id } ?: 0) + 1)
        nextCueId = max(st.nextCueId, (cues.maxOfOrNull { max(it.id, it.group) } ?: 0) + 1)
        sfx.clear()
        sfx += st.sfx.sortedBy { it.startMs }
        nextSfxId = (sfx.maxOfOrNull { it.id } ?: 0) + 1
        removed.clear()
        for (r in st.removed) if (r.size == 2 && r[1] > r[0]) removed[r[0]] = r[1]
        undo.clear()
        redo.clear()
        changed()
    }

    private fun changed() {
        version++
    }

    fun undo(): Boolean {
        val u = undo.removeLastOrNull() ?: return false
        redo.addLast(current())
        put(u)
        return true
    }

    /** Puts back the last change undone. */
    fun redo(): Boolean {
        val r = redo.removeLastOrNull() ?: return false
        undo.addLast(current())
        put(r)
        return true
    }

    private fun put(u: Snap) {
        tracks.clear()
        tracks += u.tracks
        cues.clear()
        cues += u.cues
        bleeps.clear()
        bleeps.putAll(u.bleeps)
        sfx.clear()
        sfx += u.sfx
        removed.clear()
        removed.putAll(u.removed)
        changed()
    }

    // ------------------------------------------------------------------ sound effects

    fun sfxClip(id: Int): SfxClip? = sfx.firstOrNull { it.id == id }

    /** A sound effect at [ms]; returns its id. */
    fun addSfx(sound: String, ms: Long, durMs: Long): Int {
        snapshot()
        val c = SfxClip(nextSfxId++, sound, max(0L, ms), max(1L, durMs))
        sfx += c
        sfx.sortBy { it.startMs }
        changed()
        return c.id
    }

    /** Moves a sound effect (one undo step). */
    fun placeSfx(id: Int, ms: Long) {
        val c = sfxClip(id) ?: return
        snapshot()
        c.startMs = max(0L, ms)
        sfx.sortBy { it.startMs }
        changed()
    }

    /** Moves a sound effect while dragging; no undo snapshot (see [beginGesture]). */
    fun dragSfx(id: Int, ms: Long) {
        val c = sfxClip(id) ?: return
        c.startMs = max(0L, ms)
        sfx.sortBy { it.startMs }
        changed()
    }

    fun setSfxGain(id: Int, gain: Float) {
        val c = sfxClip(id) ?: return
        snapshot()
        c.gain = gain.coerceIn(0f, 2f)
        changed()
    }

    fun deleteSfx(id: Int) {
        val c = sfxClip(id) ?: return
        snapshot()
        sfx.remove(c)
        changed()
    }

    // ------------------------------------------------------------------ speakers, bleeps

    /** Sets who says a subtitle; [wholeSentence]: every subtitle split from the same sentence. */
    fun setSpeaker(id: Int, speaker: Int, wholeSentence: Boolean = false) {
        val c = cue(id) ?: return
        val s = speaker.coerceIn(0, Speakers.MAX)
        val targets = if (wholeSentence) cues.filter { it.group == c.group } else listOf(c)
        if (targets.all { it.speaker == s }) return
        snapshot()
        for (t in targets) t.speaker = s
        changed()
    }

    /** Speakers for many subtitles at once (automatic detection): id -> speaker. One undo step. */
    fun setSpeakers(map: Map<Int, Int>) {
        if (map.isEmpty()) return
        snapshot()
        for (c in cues) map[c.id]?.let { c.speaker = it.coerceIn(0, Speakers.MAX) }
        changed()
    }

    /** Spoken words of [c] that are bleeped (indices). */
    fun bleepedWords(c: Cue): Set<Int> {
        if (bleeps.isEmpty()) return emptySet()
        val n = Subtitles.words(c.srcText).size
        val out = HashSet<Int>()
        for (i in 0 until n) {
            val (a, b) = Subtitles.wordSpanMs(c, i)
            if (bleepCovers(a, b)) out += i
        }
        return out
    }

    /** Whether most of [a, b) is bleeped. */
    private fun bleepCovers(a: Long, b: Long): Boolean = Subtitles.bleepCovers(bleeps, a, b)

    /** Bleeps spoken word [i] of subtitle [id] out of the sound, or puts it back. Returns whether it's bleeped now. */
    fun toggleBleep(id: Int, i: Int): Boolean {
        val c = cue(id) ?: return false
        val (a, b) = Subtitles.wordSpanMs(c, i)
        if (b <= a) return false
        snapshot()
        val on = !bleepCovers(a, b)
        if (on) {
            // Merge with stretches it touches (or nearly: one beep over neighbouring words).
            var s = a
            var e = b
            val hit = bleeps.entries.filter { (x, y) -> x <= e + BLEEP_JOIN_MS && y >= s - BLEEP_JOIN_MS }
            for ((x, y) in hit) {
                s = min(s, x)
                e = max(e, y)
                bleeps.remove(x)
            }
            bleeps[s] = e
        } else {
            // Cut [a, b) out of every stretch.
            val hit = bleeps.entries.filter { (x, y) -> x < b && y > a }.map { it.key to it.value }
            for ((x, y) in hit) {
                bleeps.remove(x)
                if (x < a) bleeps[x] = a
                if (y > b) bleeps[b] = y
            }
        }
        changed()
        return on
    }

    /** Copies of the subtitles to export; with [mask], bleeped words are hidden (****). */
    fun exportCues(mask: Boolean): List<Cue> = cues.sortedBy { it.startMs }.map { c ->
        val hide = if (mask) bleepedWords(c) else emptySet()
        val gone = cutWords(c)
        c.copy().also { m ->
            if (hide.isNotEmpty() || gone.isNotEmpty()) {
                var spoken = Subtitles.mask(c.srcText, hide - gone)
                // Words cut out of the video leave the subtitle too.
                if (gone.isNotEmpty()) spoken = Subtitles.words(spoken).filterIndexed { i, _ -> i !in gone }.joinToString(" ")
                if (c.src == "en" || (c.src == "" && c.he.isBlank())) m.en = spoken else m.he = spoken
            }
        }
    }

    /** Removes every bleep (one undo step). */
    fun clearBleeps() {
        if (bleeps.isEmpty()) return
        snapshot()
        bleeps.clear()
        changed()
    }

    // ------------------------------------------------------------------ cuts

    /** The cut stretches, for playback and export. */
    fun cutMap() = CutMap(removed.map { (a, b) -> longArrayOf(a, b) })

    /** Whether most of [a, b) is cut out. */
    private fun cutCovers(a: Long, b: Long): Boolean {
        if (b <= a) return false
        var covered = 0L
        for ((s, e) in removed.subMap(removed.floorKey(a) ?: a, true, b, false)) covered += max(0L, min(e, b) - max(s, a))
        return covered * 2 >= b - a
    }

    /** Adds [a, b) to the cut stretches, merged with ones it touches (or nearly does). */
    private fun addCut(a: Long, b: Long, join: Long) {
        if (b <= a) return
        var s = a
        var e = b
        val hit = removed.entries.filter { (x, y) -> x <= e + join && y >= s - join }.map { it.key to it.value }
        for ((x, y) in hit) {
            s = min(s, x)
            e = max(e, y)
            removed.remove(x)
        }
        removed[s] = e
    }

    /** Takes [a, b) out of the cut stretches (it plays again). */
    private fun uncut(a: Long, b: Long) {
        val hit = removed.entries.filter { (x, y) -> x < b && y > a }.map { it.key to it.value }
        for ((x, y) in hit) {
            removed.remove(x)
            if (x < a) removed[x] = a
            if (y > b) removed[b] = y
        }
    }

    /** Spoken words of [c] cut out of the video (indices). */
    fun cutWords(c: Cue): Set<Int> {
        if (removed.isEmpty()) return emptySet()
        val n = Subtitles.words(c.srcText).size
        val out = HashSet<Int>()
        for (i in 0 until n) {
            val (a, b) = Subtitles.wordSpanMs(c, i)
            if (cutCovers(a, b)) out += i
        }
        return out
    }

    /** Cuts spoken word [i] of subtitle [id] out of the video, or puts it back. Returns whether it's cut now. */
    fun toggleCutWord(id: Int, i: Int): Boolean {
        val c = cue(id) ?: return false
        val (a, b) = Subtitles.wordSpanMs(c, i)
        if (b <= a) return false
        snapshot()
        val on = !cutCovers(a, b)
        if (on) addCut(a, b, CUT_JOIN_MS) else uncut(a, b)
        changed()
        return on
    }

    /** Cuts the whole of subtitle [id] (from its first word to its last), or puts it back. */
    fun toggleCutCue(id: Int): Boolean {
        val c = cue(id) ?: return false
        val n = Subtitles.words(c.srcText).size
        val a = if (n > 0) Subtitles.wordSpanMs(c, 0).first else c.startMs
        val b = if (n > 0) max(Subtitles.wordSpanMs(c, n - 1).second, a + 1) else c.endMs
        snapshot()
        val on = !cutCovers(a, b)
        if (on) addCut(a, b, CUT_JOIN_MS) else uncut(a, b)
        changed()
        return on
    }

    /** Cuts many stretches at once (silences, filler words): one undo step. Returns how many were new. */
    fun addCuts(ranges: List<LongArray>): Int {
        val fresh = ranges.filter { it[1] > it[0] && !cutCovers(it[0], it[1]) }
        if (fresh.isEmpty()) return 0
        snapshot()
        for (r in fresh) addCut(r[0], r[1], 0L)
        changed()
        return fresh.size
    }

    /** Cuts [a, b) (by hand). */
    fun cut(a: Long, b: Long) {
        if (b - a < 40) return
        snapshot()
        addCut(a, b, 0L)
        changed()
    }

    /** The cut stretch around [ms], if any. */
    fun cutAt(ms: Long): LongArray? {
        val k = removed.floorKey(ms) ?: return null
        val e = removed[k] ?: return null
        return if (ms < e) longArrayOf(k, e) else null
    }

    /** Puts back the cut stretch around [ms]. */
    fun restoreCutAt(ms: Long): Boolean {
        val r = cutAt(ms) ?: return false
        snapshot()
        removed.remove(r[0])
        changed()
        return true
    }

    /** Puts back everything cut (one undo step). */
    fun clearCuts() {
        if (removed.isEmpty()) return
        snapshot()
        removed.clear()
        changed()
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
     * Replaces all subtitles with ones read from a file (an SRT), as one undo step. Bleeps go
     * with the old subtitles (they were set on their words, which the file doesn't time), as in the
     * web app; undo brings both back.
     */
    fun importCues(list: List<NewCue>) {
        snapshot()
        cues.clear()
        bleeps.clear()
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
    fun regroup(rule: SplitRule, undoable: Boolean = true) {
        if (cues.isEmpty()) return
        if (undoable) snapshot()
        val groups = LinkedHashMap<Int, MutableList<Cue>>()
        for (c in cues.sortedBy { it.startMs }) groups.getOrPut(c.group) { ArrayList() } += c
        val out = ArrayList<Cue>()
        for ((g, cs) in groups) {
            // A subtitle never mixes speakers: each speaker's run is cut on its own.
            var a = 0
            while (a < cs.size) {
                var b = a + 1
                while (b < cs.size && cs[b].speaker == cs[a].speaker) b++
                out += Subtitles.split(Subtitles.merge(cs.subList(a, b)), rule, g) { nextCueId++ }
                a = b
            }
        }
        cues.clear()
        cues += out.sortedBy { it.startMs }
        changed()
    }

    /**
     * Splits a subtitle so spoken word [k] starts a new one, at that word's time. The
     * translation is split in proportion. One undo step; returns the second part's id or -1.
     */
    fun splitCueAtWord(id: Int, k: Int): Int {
        val c = cue(id) ?: return -1
        val n = Subtitles.words(c.srcText).size
        if (k <= 0 || k >= n) return -1
        snapshot()
        var first = true
        val parts = Subtitles.splitAt(c, intArrayOf(0, k, n), c.group) { if (first) { first = false; c.id } else nextCueId++ }
        // Splitting by hand ends the sentence there: what follows becomes a sentence of its own
        // (it keeps its own words per subtitle, and can get another speaker).
        val second = parts.last()
        for (o in cues) if (o !== c && o.group == c.group && o.startMs >= c.endMs) o.group = second.id
        second.group = second.id
        cues.remove(c)
        cues += parts
        cues.sortBy { it.startMs }
        changed()
        return parts.last().id
    }

    /** Splits at the word boundary nearest to [ms]. */
    fun splitCueAtTime(id: Int, ms: Long): Int {
        val c = cue(id) ?: return -1
        return splitCueAtWord(id, Subtitles.nearestBoundary(c, ms))
    }

    /** Splits at a position in the spoken text (e.g. the cursor); -1 offset: in the middle. */
    fun splitCueAtChar(id: Int, offset: Int): Int {
        val c = cue(id) ?: return -1
        return splitCueAtWord(id, Subtitles.boundaryAtChar(c.srcText, offset))
    }

    /** Joins a subtitle with the one before it. */
    fun mergeWithPrevious(id: Int): Boolean {
        val sorted = cues.sortedBy { it.startMs }
        val i = sorted.indexOfFirst { it.id == id }
        if (i <= 0) return false
        return mergeWithNext(sorted[i - 1].id)
    }

    /**
     * A new empty subtitle at [ms], fitted into the free time there: after the subtitle under
     * [ms] if any, up to the next one (at most 2.5 s). -1 if there's no room (< 0.4 s).
     */
    fun addCueInGap(ms: Long, maxMs: Long): Int {
        val inside = cues.firstOrNull { ms >= it.startMs && ms < it.endMs }
        val start = inside?.endMs ?: ms
        val next = cues.filter { it !== inside && it.startMs >= start }.minOfOrNull { it.startMs } ?: maxMs
        val end = min(next, start + 2500)
        if (end - start < 400) return -1
        snapshot()
        val c = Cue(nextCueId++, start, end, "", "")
        cues += c
        cues.sortBy { it.startMs }
        changed()
        return c.id
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
        cs.forEachIndexed { i, c ->
            // A manual subtitle (no language) gets one from the side it's written on.
            if (c.src == "") c.src = if (intoEnglish) SubLanguages.scriptLang(c.he).takeIf { it != "en" && it != "" } ?: "he" else "en"
            if (intoEnglish) c.en = parts[i] else c.he = parts[i]
        }
        changed()
    }

    fun editCue(id: Int, he: String, en: String) {
        val c = cue(id) ?: return
        snapshot()
        c.he = he
        c.en = en
        changed()
    }

    /** Spoken-language text and translation of a subtitle (one undo step). */
    fun editCueTexts(id: Int, spoken: String, translation: String) {
        val c = cue(id) ?: return
        snapshot()
        c.setTexts(spoken, translation)
        changed()
    }

    /** Same without an undo step (typing; see [beginGesture]). */
    fun setCueTexts(id: Int, spoken: String, translation: String) {
        val c = cue(id) ?: return
        c.setTexts(spoken, translation)
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
        val b = t.copy().let { EditTrack(nextId++, it.manual, it.path, frame, it.end, it.scale, it.keys, it.off) }
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

    /** How many blurs are on (not turned off). */
    val blursOn get() = tracks.count { !it.off }

    /** Turns one blur off or back on (one undo step). */
    fun toggleOff(id: Int) {
        val t = track(id) ?: return
        snapshot()
        t.off = !t.off
        changed()
    }

    /** Turns every blur on ([off] false) or off (true), as one undo step; nothing changes if they already are. */
    fun setAllOff(off: Boolean) {
        if (tracks.all { it.off == off }) return
        snapshot()
        for (t in tracks) t.off = off
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

    /** Makes [version] at least [v] (a model that takes over from another must never repeat its numbers). */
    fun raiseVersionTo(v: Int) {
        if (version < v) version = v
    }

    companion object {
        /**
         * The model of a new detection ([fresh], from the found analysis) carrying over everything
         * [old] had that doesn't depend on the analysis: subtitles, bleeps, sounds and cuts (all in
         * milliseconds), and the blurs placed by hand (moved to the same moments in the new frames,
         * and numbered after the new ones). The undo history starts again; the version goes on
         * above the old one so nothing watching it misses the swap.
         */
        fun withDetection(old: EditModel, fresh: EditModel): EditModel {
            val kept = old.exportState()
            val blurs = fresh.exportState()
            var next = blurs.nextId
            val manual = kept.tracks.filter { it.manual }.map { remapTrack(it, old.times, fresh.times, next++) }
            fresh.restore(EditState(blurs.tracks + manual, kept.cues, kept.bleeps, next, kept.nextCueId, kept.sfx, kept.removed))
            fresh.raiseVersionTo(old.version + 1)
            return fresh
        }

        private fun nearest(times: LongArray, us: Long): Int {
            if (times.isEmpty()) return 0
            var lo = 0
            var hi = times.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (times[mid] < us) lo = mid + 1 else hi = mid
            }
            return if (lo > 0 && us - times[lo - 1] <= times[lo] - us) lo - 1 else lo
        }

        /** [t] with its frames moved from [from]'s times to [to]'s (the same frames if they are the same times), as a new blur [id]. */
        fun remapTrack(t: EditTrack, from: LongArray, to: LongArray, id: Int): EditTrack {
            fun f(i: Int): Int = if (from.contentEquals(to)) i else nearest(to, from[i.coerceIn(0, from.size - 1)])
            val path = TreeMap<Int, Box>()
            for ((k, b) in t.path) path[f(k)] = b
            val keys = TreeMap<Int, FloatArray>()
            for ((k, v) in t.keys) keys[f(k)] = v.copyOf()
            val last = max(0, to.size - 1)
            return EditTrack(id, true, path, f(t.start).coerceIn(0, last), f(t.end).coerceIn(0, last), t.scale, keys, t.off)
        }

        /** Bleeped words closer than this sound as one beep. */
        const val BLEEP_JOIN_MS = 250L
        /** Cut words closer than this are cut as one stretch (no blip of sound between them). */
        const val CUT_JOIN_MS = 160L
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 3f
        private const val MAX_UNDO = 60
    }
}
