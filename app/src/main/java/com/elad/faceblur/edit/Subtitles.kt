package com.elad.faceblur.edit

import java.util.TreeMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Splitting and joining subtitles (pure; used by [EditModel]). */
object Subtitles {
    /** Pause kept on screen after a chunk's last word before the next chunk starts. */
    private const val HOLD_MS = 600L

    fun words(s: String): List<String> = s.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /** Whether most of [a, b) is in [bleeps] (start ms -> end ms). */
    fun bleepCovers(bleeps: TreeMap<Long, Long>, a: Long, b: Long): Boolean {
        if (b <= a) return false
        var covered = 0L
        for ((s, e) in bleeps.subMap(bleeps.floorKey(a) ?: a, true, b, false)) covered += max(0L, min(e, b) - max(s, a))
        return covered * 2 >= b - a
    }

    /**
     * Words bleeped, for the projects' list: the spoken words of [cues] mostly under [bleeps]
     * (start, end ms), as the editor counts them, plus one for each stretch that's under none of
     * them (its subtitle edited or deleted since).
     */
    fun bleepedWordCount(cues: List<Cue>, bleeps: List<LongArray>): Int {
        if (bleeps.isEmpty()) return 0
        val map = TreeMap<Long, Long>()
        for (b in bleeps) if (b.size == 2 && b[1] > b[0]) map[b[0]] = maxOf(b[1], map[b[0]] ?: b[1])
        if (map.isEmpty()) return 0
        val heard = HashSet<Long>()
        var n = 0
        for (c in cues) {
            for (i in words(c.srcText).indices) {
                val (a, b) = wordSpanMs(c, i)
                if (!bleepCovers(map, a, b)) continue
                n++
                for ((s, e) in map.subMap(map.floorKey(a) ?: a, true, b, false)) if (e > a) heard += s
            }
        }
        return n + map.keys.count { it !in heard }
    }

    /** One subtitle from several (texts joined; word times kept if every part has them). */
    fun merge(cs: List<Cue>): Cue {
        val f = cs.first()
        if (cs.size == 1) return f.copy()
        val src = cs.map { it.src }.distinct().singleOrNull() ?: ""
        val timed = cs.all { c -> c.words != null && c.words!!.size == words(c.srcText).size }
        return Cue(
            f.id,
            cs.minOf { it.startMs },
            cs.maxOf { it.endMs },
            cs.map { it.he.trim() }.filter { it.isNotEmpty() }.joinToString(" "),
            cs.map { it.en.trim() }.filter { it.isNotEmpty() }.joinToString(" "),
            f.group,
            src,
            if (timed) cs.flatMap { it.words!! } else null,
            f.speaker,
        )
    }

    /** Word index bounds of the pieces of [ws] under [rule]. */
    fun bounds(ws: List<String>, rule: SplitRule): IntArray {
        if (rule.words > 0) {
            if (ws.size <= rule.words) return intArrayOf(0, ws.size)
            // Balanced, so there's no lone last word.
            val k = ceil(ws.size / rule.words.toDouble()).toInt()
            val base = ws.size / k
            val extra = ws.size % k
            val b = IntArray(k + 1)
            for (j in 0 until k) b[j + 1] = b[j] + base + if (j < extra) 1 else 0
            return b
        }
        if (rule.chars > 0) {
            val out = arrayListOf(0)
            var len = 0
            ws.forEachIndexed { i, w ->
                val add = if (len == 0) w.length else len + 1 + w.length
                if (len > 0 && add > rule.chars) {
                    out += i
                    len = w.length
                } else {
                    len = add
                }
            }
            out += ws.size
            return out.toIntArray()
        }
        return intArrayOf(0, ws.size)
    }

    /**
     * Splits [c] into pieces by [rule] (in spoken words), in group [group]; [newId] gives ids.
     * The translation is spread over the pieces in proportion.
     */
    fun split(c: Cue, rule: SplitRule, group: Int, newId: () -> Int): List<Cue> =
        splitAt(c, bounds(words(c.srcText), rule), group, newId)

    /** Splits [c] at the spoken-word [bounds] (0, ..., word count). */
    fun splitAt(c: Cue, bounds: IntArray, group: Int, newId: () -> Int): List<Cue> {
        val src = words(c.srcText)
        val k = bounds.size - 1
        if (k <= 1) return listOf(Cue(newId(), c.startMs, c.endMs, c.he, c.en, group, c.src, c.words, c.speaker))
        val srcIsEnglish = c.src == "en" || (c.src == "" && c.he.isBlank())
        val other = words(c.otherText)
        val otherParts = spread(other, (0 until k).map { bounds[it + 1] - bounds[it] })
        val wt = c.words?.takeIf { it.size == src.size }
        val chars = src.map { it.length + 1 }
        val totalChars = chars.sum().toDouble()
        fun timeAt(i: Int): Long {
            if (wt != null) return wt[i].startMs
            val before = chars.take(i).sum()
            return c.startMs + ((c.endMs - c.startMs) * before / totalChars).toLong()
        }
        val out = ArrayList<Cue>()
        for (j in 0 until k) {
            val a = bounds[j]
            val e = bounds[j + 1]
            val start = if (j == 0) c.startMs else timeAt(a)
            var end = if (j == k - 1) c.endMs else timeAt(e)
            if (wt != null && j < k - 1) end = min(end, wt[e - 1].endMs + HOLD_MS)
            end = max(end, start + 100)
            val srcText = src.subList(a, e).joinToString(" ")
            val otherText = otherParts[j]
            val he = if (srcIsEnglish) otherText else srcText
            val en = if (srcIsEnglish) srcText else otherText
            out += Cue(newId(), start, end, he, en, group, c.src, wt?.subList(a, e)?.toList(), c.speaker)
        }
        return out
    }

    /** When spoken word [i] of [c] is said: its recognized times, or spread by length. */
    fun wordSpanMs(c: Cue, i: Int): Pair<Long, Long> {
        val src = words(c.srcText)
        if (i !in src.indices) return 0L to 0L
        c.words?.takeIf { it.size == src.size }?.let { return it[i].startMs to max(it[i].endMs, it[i].startMs + 80) }
        val end = if (i + 1 < src.size) wordStartMs(c, i + 1) else c.endMs
        return wordStartMs(c, i) to end
    }

    /** [s] with the words at [hide] masked (punctuation around them kept). */
    fun mask(s: String, hide: Set<Int>): String {
        var i = -1
        return Regex("""\S+""").replace(s) { m ->
            i++
            if (i !in hide) m.value
            else {
                val w = m.value
                val a = w.indexOfFirst { it.isLetterOrDigit() }
                val b = w.indexOfLast { it.isLetterOrDigit() }
                // Always 4 stars: the length shouldn't hint at the word.
                if (a < 0) w else w.substring(0, a) + "****" + w.substring(b + 1)
            }
        }
    }

    /** When spoken word [i] of [c] starts: its recognized time, or spread by length. */
    fun wordStartMs(c: Cue, i: Int): Long {
        val src = words(c.srcText)
        c.words?.takeIf { it.size == src.size }?.let { return it[i].startMs }
        val chars = src.map { it.length + 1 }
        val total = chars.sum().toDouble()
        return c.startMs + ((c.endMs - c.startMs) * chars.take(i).sum() / total).toLong()
    }

    /** The word boundary (1 until word count) nearest to [ms]; -1 if there's one word. */
    fun nearestBoundary(c: Cue, ms: Long): Int {
        val n = words(c.srcText).size
        if (n < 2) return -1
        return (1 until n).minBy { kotlin.math.abs(wordStartMs(c, it) - ms) }
    }

    /** The word boundary at a text position: words that start before [offset] stay first. */
    fun boundaryAtChar(text: String, offset: Int): Int {
        val ranges = Regex("""\S+""").findAll(text).map { it.range }.toList()
        if (ranges.size < 2) return -1
        if (offset < 0) return ranges.size / 2
        return ranges.count { it.first < offset }.coerceIn(1, ranges.size - 1)
    }

    /** [ws] split into parts with sizes in proportion to [weights]. */
    fun spread(ws: List<String>, weights: List<Int>): List<String> {
        val total = weights.sum()
        if (weights.isEmpty()) return emptyList()
        if (total <= 0) return weights.mapIndexed { i, _ -> if (i == 0) ws.joinToString(" ") else "" }
        val out = ArrayList<String>()
        var cum = 0
        for (w in weights) {
            val a = (ws.size * cum / total.toDouble()).roundToInt()
            cum += w
            val b = (ws.size * cum / total.toDouble()).roundToInt()
            out += ws.subList(a, b).joinToString(" ")
        }
        return out
    }
}
