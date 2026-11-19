package com.elad.faceblur.analysis

import java.util.TreeMap
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the analysis pass's raw per-frame detections into face tracks, after the whole video
 * has been seen (so it can look both ways in time):
 *
 *  1. Positions are moved into camera-stabilized coordinates (cumulative camera motion is
 *     subtracted), so a shaky or panning camera doesn't break tracks. Hard cuts split shots.
 *  2. Detections are linked greedily into tracklets with a constant-velocity prediction.
 *  3. A tracklet is accepted as a face only with enough evidence: several confident
 *     detections, or detections confirmed on an upscaled crop. Single-frame clutter
 *     (knees, gloves, wall textures) rarely gathers that. Thresholds were tuned on hand-labeled
 *     real footage (shaky handheld camera, many people, hard cuts).
 *  4. Weak tracklets that directly continue an accepted one are absorbed into it, and
 *     tracklets of the same face split by a longer detector dropout are joined.
 *  5. Gaps are interpolated in stabilized space (so the blur moves with the camera while the
 *     detector misses the face), ends are held a little longer, and the result is smoothed.
 *
 * Coordinates are "width units": x and w as a fraction of the frame width, y and h scaled by
 * the frame aspect ratio so both axes have the same unit.
 */
class OfflineTracker(private val fps: Float, private val aspect: Float, private val sensitive: Boolean = false) {

    private class SDet(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val verify: Float)

    private class Candidate(val cost: Float, val di: Int, val t: Tracklet)

    private class Tracklet(val shotStart: Int, val shotEnd: Int) {
        val det = TreeMap<Int, SDet>()
        val first get() = det.firstKey()
        val last get() = det.lastKey()
    }

    /**
     * @param n number of frames.
     * @param dets detections of the frames the detector ran on (normalized upright frame).
     * @param motion camera motion per frame (dx / W, dy / H), 2 floats per frame.
     * @param cuts first frame of each new shot.
     * @return per track: frame index -> box (normalized upright frame).
     */
    fun run(n: Int, dets: Map<Int, List<Det>>, motion: FloatArray, cuts: List<Int>): List<TreeMap<Int, Box>> {
        if (n == 0) return emptyList()
        val (offX, offY) = cameraOffsets(n, motion, cuts, aspect)

        val bounds = ArrayList<Int>().apply {
            add(0)
            cuts.filter { it in 1 until n }.sorted().distinct().forEach { add(it) }
            add(n)
        }
        val tracklets = ArrayList<Tracklet>()
        for (s in 0 until bounds.size - 1) {
            tracklets += link(bounds[s], bounds[s + 1], dets, offX, offY)
        }
        val accepted = merge(tracklets.filter { accept(it) }.toMutableList(), tracklets)
        return accepted.map { smooth(fill(it, offX, offY)) }
    }

    private fun link(s0: Int, s1: Int, dets: Map<Int, List<Det>>, offX: FloatArray, offY: FloatArray): List<Tracklet> {
        val maxGap = (MAX_GAP_S * fps).roundToInt()
        val predCap = (0.3f * fps).toInt()
        val out = ArrayList<Tracklet>()
        val active = ArrayList<Tracklet>()
        for (i in s0 until s1) {
            val raw = dets[i] ?: continue
            val ds = raw.filter { it.score >= DET_THRESHOLD }.map {
                SDet(it.box.cx - offX[i], it.box.cy * aspect - offY[i], it.box.w, it.box.h * aspect, it.score, it.verify)
            }
            active.removeAll { i - it.last > maxGap }

            val pairs = ArrayList<Candidate>()
            ds.forEachIndexed { di, d ->
                for (t in active) {
                    val l = t.det[t.last]!!
                    var px = l.x
                    var py = l.y
                    if (t.det.size >= 2) {
                        val prevKey = t.det.lowerKey(t.last)
                        val a = t.det[prevKey]!!
                        val dt = t.last - prevKey
                        if (dt in 1..maxGap) {
                            val g = min(i - t.last, predCap)
                            px += (l.x - a.x) / dt * g
                            py += (l.y - a.y) / dt * g
                        }
                    }
                    val size = max(l.w, l.h)
                    val gap = (i - t.last) / fps
                    val dist = hypot(d.x - px, d.y - py)
                    val gate = size * (GATE + GATE_PER_S * gap)
                    val ratio = max(d.w, l.w) / max(1e-6f, min(d.w, l.w))
                    if (dist < gate && ratio < SIZE_RATIO) pairs += Candidate(dist / max(size, 1e-6f) + 0.3f * gap, di, t)
                }
            }
            val usedD = HashSet<Int>()
            val usedT = HashSet<Tracklet>()
            for (p in pairs.sortedBy { it.cost }) {
                if (p.di in usedD || p.t in usedT) continue
                usedD += p.di
                usedT += p.t
                p.t.det[i] = ds[p.di]
            }
            ds.forEachIndexed { di, d ->
                if (di in usedD || d.score < BIRTH_THRESHOLD) return@forEachIndexed
                val t = Tracklet(s0, s1)
                t.det[i] = d
                out += t
                active += t
            }
        }
        return out
    }

    private fun accept(t: Tracklet): Boolean {
        val strong = t.det.values.count { it.score >= STRONG }
        val verified = t.det.values.count { it.verify >= VERIFIED }
        // Sensitive mode takes weaker evidence: more faces covered, more needless blurs.
        if (sensitive) return strong >= 3 || verified >= 1
        // A very short tracklet needs two crisp crop confirmations: seats of trousers and
        // backs of soldiers walking away fool both detectors for a few frames, but don't
        // verify as cleanly as even tiny real faces.
        if (t.det.size < SHORT_N) return t.det.values.count { it.verify >= SHORT_VERIFIED } >= 2
        return strong >= 4 || verified >= 2 || (verified >= 1 && strong >= 2)
    }

    /**
     * Joins accepted tracklets of the same face split by a detector dropout. First, unaccepted
     * tracklets that directly continue an accepted one (the face's weak start or end) are
     * absorbed into it.
     */
    private fun merge(list: MutableList<Tracklet>, all: List<Tracklet>): List<Tracklet> {
        val gapMax = (LINK_GAP_S * fps).toInt()
        list.sortBy { it.first }
        absorb(list, all)
        var merged = true
        while (merged) {
            merged = false
            for (a in list) {
                val la = a.det[a.last]!!
                var best: Tracklet? = null
                for (b in list) {
                    if (b === a || b.shotStart != a.shotStart) continue
                    val gap = b.first - a.last
                    if (gap <= 0 || gap > gapMax) continue
                    val fb = b.det[b.first]!!
                    val size = max(la.w, la.h)
                    val r = fb.w / la.w
                    if (hypot(la.x - fb.x, la.y - fb.y) < 1.5f * size && r > 1f / 1.7f && r < 1.7f) {
                        if (best == null || b.first < best.first) best = b
                    }
                }
                if (best != null) {
                    a.det.putAll(best.det)
                    list.remove(best)
                    merged = true
                    break
                }
            }
        }
        return list
    }

    private fun absorb(accepted: List<Tracklet>, all: List<Tracklet>) {
        val acceptedSet = accepted.toHashSet()
        val extra = all.filter { it !in acceptedSet }.toMutableList()
        val gapMax = (ABSORB_GAP_S * fps).toInt()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (a in accepted) {
                for (b in extra) {
                    if (b.shotStart != a.shotStart) continue
                    for ((x, y) in listOf(a to b, b to a)) {
                        val g = y.first - x.last
                        if (g <= 0 || g > gapMax) continue
                        val lx = x.det[x.last]!!
                        val fy = y.det[y.first]!!
                        val size = max(lx.w, lx.h)
                        val r = fy.w / lx.w
                        if (hypot(lx.x - fy.x, lx.y - fy.y) < ABSORB_DIST * size && r > 1f / 1.5f && r < 1.5f) {
                            a.det.putAll(b.det)
                            extra.remove(b)
                            changed = true
                            break@loop
                        }
                    }
                }
            }
        }
    }

    private fun unstab(x: Float, y: Float, w: Float, h: Float, i: Int, offX: FloatArray, offY: FloatArray) =
        floatArrayOf(x + offX[i], (y + offY[i]) / aspect, w, h / aspect)

    private fun fill(t: Tracklet, offX: FloatArray, offY: FloatArray): TreeMap<Int, FloatArray> {
        val out = TreeMap<Int, FloatArray>()
        val keys = t.det.keys.toIntArray()
        for (k in 0 until keys.size - 1) {
            val a = keys[k]
            val b = keys[k + 1]
            val da = t.det[a]!!
            val db = t.det[b]!!
            for (i in a until b) {
                val f = (i - a).toFloat() / (b - a)
                out[i] = unstab(
                    da.x + (db.x - da.x) * f, da.y + (db.y - da.y) * f,
                    da.w + (db.w - da.w) * f, da.h + (db.h - da.h) * f, i, offX, offY,
                )
            }
        }
        val ext = (EXTEND_S * fps).roundToInt()
        val d0 = t.det[keys.first()]!!
        val dl = t.det[keys.last()]!!
        out[keys.last()] = unstab(dl.x, dl.y, dl.w, dl.h, keys.last(), offX, offY)
        // Hold the stabilized position (it moves with the camera) a little past both ends.
        for (i in max(t.shotStart, keys.first() - ext) until keys.first()) out[i] = unstab(d0.x, d0.y, d0.w, d0.h, i, offX, offY)
        for (i in keys.last() + 1 until min(t.shotEnd, keys.last() + ext + 1)) out[i] = unstab(dl.x, dl.y, dl.w, dl.h, i, offX, offY)
        return out
    }

    private fun smooth(src: TreeMap<Int, FloatArray>): TreeMap<Int, Box> = smoothBoxes(src, fps)

    companion object {
        /**
         * Cumulative camera offset per frame in width units (x: fraction of width, y: in the
         * same unit), reset to 0 at every cut. Subtracting it gives stabilized coordinates.
         */
        fun cameraOffsets(n: Int, motion: FloatArray, cuts: Collection<Int>, aspect: Float): Pair<FloatArray, FloatArray> {
            val cutSet = cuts.toHashSet()
            val offX = FloatArray(n)
            val offY = FloatArray(n)
            for (i in 1 until n) {
                if (i in cutSet) continue
                offX[i] = offX[i - 1] + motion.getOrElse(2 * i) { 0f }
                offY[i] = offY[i - 1] + motion.getOrElse(2 * i + 1) { 0f } * aspect
            }
            return offX to offY
        }

        /**
         * Centered Gaussian over time of contiguous per-frame boxes (cx, cy, w, h): position
         * barely (shaky cameras), size more (no breathing).
         */
        fun smoothBoxes(src: TreeMap<Int, FloatArray>, fps: Float): TreeMap<Int, Box> {
            val keys = src.keys.toIntArray()
            val vals = keys.map { src[it]!! }
            val sig = floatArrayOf(SIGMA_POS_S, SIGMA_POS_S, SIGMA_SIZE_S, SIGMA_SIZE_S).map { it * fps }
            val out = TreeMap<Int, Box>()
            for (i in keys.indices) {
                val r = FloatArray(4)
                for (c in 0 until 4) {
                    val reach = 3 * sig[c] + 0.5f
                    val s = max(sig[c], 0.5f)
                    var acc = 0f
                    var tw = 0f
                    var j = i
                    while (j >= 0 && keys[i] - keys[j] <= reach) {
                        val d = (keys[i] - keys[j]) / s
                        val wt = exp(-0.5f * d * d)
                        acc += vals[j][c] * wt; tw += wt; j--
                    }
                    j = i + 1
                    while (j < keys.size && keys[j] - keys[i] <= reach) {
                        val d = (keys[j] - keys[i]) / s
                        val wt = exp(-0.5f * d * d)
                        acc += vals[j][c] * wt; tw += wt; j++
                    }
                    r[c] = acc / tw
                }
                out[keys[i]] = Box(r[0], r[1], r[2], r[3])
            }
            return out
        }

        private const val DET_THRESHOLD = 0.4f
        private const val BIRTH_THRESHOLD = 0.45f
        private const val STRONG = 0.6f
        private const val VERIFIED = 0.5f
        private const val SHORT_N = 4
        private const val SHORT_VERIFIED = 0.9f
        private const val MAX_GAP_S = 0.7f
        private const val GATE = 0.6f
        private const val GATE_PER_S = 1.5f
        private const val SIZE_RATIO = 1.7f
        private const val LINK_GAP_S = 1.2f
        private const val ABSORB_GAP_S = 0.7f
        private const val ABSORB_DIST = 1.0f
        private const val EXTEND_S = 0.25f
        private const val SIGMA_POS_S = 0.025f
        private const val SIGMA_SIZE_S = 0.2f
    }
}
