package com.elad.faceblur.edit

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pauses in speech, from the sound's level, and filler sounds, from the words. */
object SilenceFinder {
    private const val STEP_MS = 20
    /** Kept at each side of a cut pause: a breath, so the cut doesn't sound chopped. */
    private const val KEEP_MS = 140L

    /**
     * Stretches to cut (ms): quiet runs longer than [minPauseMs], less [KEEP_MS] at each end,
     * never over a spoken [words] stretch. [x] is mono at [rate].
     */
    fun find(x: FloatArray, rate: Int, minPauseMs: Long, words: List<LongArray>): List<LongArray> {
        val step = rate * STEP_MS / 1000
        val n = x.size / step
        if (n < 10) return emptyList()
        val db = FloatArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (j in i * step until (i + 1) * step) s += x[j].toDouble() * x[j]
            db[i] = (10 * log10(max(s / step, 1e-10))).toFloat()
        }
        // Quiet = well under the speech level, judged from this video's own levels.
        val sorted = db.sorted()
        val floor = sorted[(n * 0.10).toInt()]
        val speech = sorted[min(n - 1, (n * 0.90).toInt())]
        val thr = max(floor + 6f, floor + (speech - floor) * 0.35f).coerceAtMost(speech - 6f)
        // Smooth a little so a click doesn't split a pause.
        val quiet = BooleanArray(n) { i ->
            var q = 0
            var t = 0
            for (k in max(0, i - 1)..min(n - 1, i + 1)) { t++; if (db[k] < thr) q++ }
            q * 2 > t
        }
        val out = ArrayList<LongArray>()
        var i = 0
        while (i < n) {
            if (!quiet[i]) { i++; continue }
            var j = i
            while (j < n && quiet[j]) j++
            val a = i.toLong() * STEP_MS
            val b = j.toLong() * STEP_MS
            if (b - a >= minPauseMs) {
                val ca = if (i == 0) 0L else a + KEEP_MS
                val cb = if (j >= n) b else b - KEEP_MS
                if (cb - ca >= 120) out += longArrayOf(ca, cb)
            }
            i = j
        }
        return subtract(out, words)
    }

    /** [ranges] with the [words]' stretches taken out (a word is never cut by a pause). */
    private fun subtract(ranges: List<LongArray>, words: List<LongArray>): List<LongArray> {
        if (words.isEmpty()) return ranges
        val ws = words.filter { it[1] > it[0] }.sortedBy { it[0] }
        val out = ArrayList<LongArray>()
        for (r in ranges) {
            var pieces = listOf(r)
            for (w in ws) {
                if (w[0] >= r[1]) break
                if (w[1] <= r[0]) continue
                pieces = pieces.flatMap { p ->
                    if (w[1] <= p[0] || w[0] >= p[1]) listOf(p)
                    else listOfNotNull(
                        longArrayOf(p[0], w[0] - 60).takeIf { it[1] - it[0] >= 200 },
                        longArrayOf(w[1] + 60, p[1]).takeIf { it[1] - it[0] >= 200 },
                    )
                }
            }
            out += pieces
        }
        return out
    }

    private val FILLERS = setOf(
        "אה", "אהה", "אההה", "אמ", "אממ", "אמממ", "אממממ", "אהם", "אהמ", "המ", "הממ", "הממם", "אמם", "אהממ",
        "um", "umm", "uh", "uhh", "uhm", "erm", "er", "hmm", "hm", "mm", "mmm", "ah", "eh",
        "اه", "امم", "اممم", "ااا",
    )

    /** Whether [word] (punctuation and vowel marks aside) is a filler sound. */
    fun isFiller(word: String): Boolean {
        val w = word.lowercase().filter { it.isLetter() && it !in '֑'..'ׇ' }
        return w in FILLERS
    }
}
