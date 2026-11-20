package com.elad.faceblur.subs

import kotlin.math.abs
import kotlin.math.sqrt

/** Grouping voice embeddings into speakers (pure; see [SpeakerDetector]). */
object SpeakerClusters {
    /** Cosine similarity above which two voices are taken as one speaker (average linkage). */
    const val THRESHOLD = 0.4f

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * Speaker of each segment, numbered 1.. in order of first appearance. Segments are in time
     * order; null embeddings (too short to tell) take the speaker of the nearest segment before
     * (or after) them.
     *
     * @param count the number of speakers if known (else found by [threshold]).
     * @param max at most this many speakers.
     */
    fun assign(embs: List<FloatArray?>, count: Int? = null, max: Int = 6, threshold: Float = THRESHOLD): IntArray {
        val idx = embs.indices.filter { embs[it] != null }
        val out = IntArray(embs.size)
        if (idx.isEmpty()) return out.also { it.fill(1) }
        val n = idx.size
        val e = idx.map { normalize(embs[it]!!) }
        // Average linkage, merging the closest pair until they're no longer alike.
        val sim = Array(n) { i -> FloatArray(n) { j -> if (i == j) 0f else dot(e[i], e[j]) } }
        val size = IntArray(n) { 1 }
        val alive = BooleanArray(n) { true }
        val parent = IntArray(n) { it }
        var clusters = n
        val target = count?.coerceIn(1, max)
        while (clusters > 1) {
            var best = -2f
            var ba = -1
            var bb = -1
            for (a in 0 until n) {
                if (!alive[a]) continue
                val row = sim[a]
                for (b in a + 1 until n) {
                    if (alive[b] && row[b] > best) {
                        best = row[b]; ba = a; bb = b
                    }
                }
            }
            val enough = if (target != null) clusters <= target else (best < threshold && clusters <= max)
            if (enough || ba < 0) break
            // Merge bb into ba (Lance-Williams update for average linkage).
            for (k in 0 until n) {
                if (!alive[k] || k == ba || k == bb) continue
                val s = (size[ba] * sim[ba][k] + size[bb] * sim[bb][k]) / (size[ba] + size[bb])
                sim[ba][k] = s
                sim[k][ba] = s
            }
            size[ba] += size[bb]
            alive[bb] = false
            for (i in 0 until n) if (parent[i] == bb) parent[i] = ba
            parent[bb] = ba
            clusters--
        }
        // Number clusters by first appearance.
        val number = HashMap<Int, Int>()
        val label = IntArray(n)
        for (i in 0 until n) {
            var r = i
            while (parent[r] != r) r = parent[r]
            label[i] = number.getOrPut(r) { number.size + 1 }
        }
        for ((k, i) in idx.withIndex()) out[i] = label[k]
        // Too-short segments: like their nearest neighbor in time.
        for (i in out.indices) {
            if (out[i] != 0) continue
            val before = (i - 1 downTo 0).firstOrNull { embs[it] != null }
            val after = (i + 1 until out.size).firstOrNull { embs[it] != null }
            out[i] = when {
                before == null -> out[after!!]
                after == null -> out[before]
                abs(i - before) <= abs(after - i) -> out[before]
                else -> out[after]
            }
        }
        return out
    }
}
