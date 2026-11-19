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

    /**
     * Splits [c] into subtitles of at most [n] spoken words (balanced, so there's no lone last
     * word), in group [group]; [newId] gives ids. The other language is spread in proportion.
     */
    fun split(c: Cue, n: Int, group: Int, newId: () -> Int): List<Cue> {
        val src = words(c.srcText)
        if (n <= 0 || src.size <= n) return listOf(Cue(newId(), c.startMs, c.endMs, c.he, c.en, group, c.src, c.words))
        val k = ceil(src.size / n.toDouble()).toInt()
        val base = src.size / k
        val extra = src.size % k
        val bounds = IntArray(k + 1)
        for (j in 0 until k) bounds[j + 1] = bounds[j] + base + if (j < extra) 1 else 0
        val srcIsEnglish = c.src == "en"
        val same = c.he.trim() == c.en.trim()
        val other = if (same) src else words(if (srcIsEnglish) c.he else c.en)
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
            val otherText = if (same) srcText else otherParts[j]
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
