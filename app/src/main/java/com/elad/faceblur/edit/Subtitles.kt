package com.elad.faceblur.edit

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Splitting and joining subtitles (pure; used by [EditModel]). */
object Subtitles {
    /** Pause kept on screen after a chunk's last word before the next chunk starts. */
    private const val HOLD_MS = 600L

    fun words(s: String): List<String> = s.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

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
    fun split(c: Cue, rule: SplitRule, group: Int, newId: () -> Int): List<Cue> {
        val src = words(c.srcText)
        val bounds = bounds(src, rule)
        val k = bounds.size - 1
        if (k <= 1) return listOf(Cue(newId(), c.startMs, c.endMs, c.he, c.en, group, c.src, c.words))
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
            out += Cue(newId(), start, end, he, en, group, c.src, wt?.subList(a, e)?.toList())
        }
        return out
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
