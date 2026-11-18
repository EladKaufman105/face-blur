package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Kind { DETECTED, FLOW, BACKWARD, INTERPOLATED }

class TrackPoint(val box: Box, val kind: Kind)

class Track(val id: Int) {
    val points = TreeMap<Int, TrackPoint>()
    lateinit var last: Box
    var lastIdx = -1
    lateinit var lastDetected: Box
    /** Normalized 32x32 grayscale patch of the face at its last detection. */
    var anchor: FloatArray? = null
    /** Pixel aspect (h / w) of each detection. */
    val aspects = ArrayList<Float>()
    var lost = 0
    var hits = 0
    var strongHits = 0
    var maxScore = 0f
    var active = true
}

/**
 * Online multi-face tracker over the analysis frames.
 *
 * - Detections are matched to each track's median-flow prediction.
 * - When the detector misses a face (profile, blur, occlusion), the track coasts on optical
 *   flow for a short while, as long as the patch still looks like the face it was following.
 * - A new face is also followed backwards a little (face entering or turning).
 * - Hard cuts in edited videos end every track; nothing is carried across a cut.
 * - Tracks need several confident detections (or one very confident one) to count.
 */
class FaceTracker(private val fps: Float) {

    private val backFrames = (fps * BACKWARD_SECONDS).roundToInt()
    private val ring = ArrayDeque<Pair<Int, Mat>>()
    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private val cuts = CutDetector()
    /** Analysis frame indices where a hard cut starts a new shot. */
    val cutFrames = ArrayList<Int>()

    fun step(idx: Int, dets: List<Det>, detectionRan: Boolean, gray: Mat) {
        val gw = gray.cols().toFloat()
        val gh = gray.rows().toFloat()
        fun toPx(b: Box) = PxBox(b.cx * gw, b.cy * gh, b.w * gw, b.h * gh)
        fun fromPx(p: PxBox, angle: Float) = Box(p.cx / gw, p.cy / gh, p.w / gw, p.h / gh, angle)

        if (cuts.isCut(gray)) {
            cutFrames += idx
            for (t in tracks) t.active = false
            clearRing()
        }

        val prevGray = ring.peekLast()?.takeIf { it.first == idx - 1 }?.second
        val active = tracks.filter { it.active }

        // 1. Predict every active track into this frame with optical flow.
        val preds = HashMap<Int, Box?>()
        for (t in active) {
            val p = if (prevGray != null && t.lastIdx == idx - 1) MedianFlow.track(prevGray, gray, toPx(t.last)) else null
            preds[t.id] = p?.let { fromPx(it, t.last.angle) }
        }

        // 2. Greedy association of detections to predictions.
        class Candidate(val cost: Float, val di: Int, val t: Track)
        val pairs = ArrayList<Candidate>()
        for ((di, d) in dets.withIndex()) {
            for (t in active) {
                val ref = preds[t.id] ?: t.last
                val o = YuNetDetector.iou(d.box, ref)
                val dist = hypot((d.box.cx - ref.cx) * gw, (d.box.cy - ref.cy) * gh)
                val size = max(ref.w * gw, ref.h * gh)
                val ratio = max(d.box.w, ref.w) / max(1e-6f, min(d.box.w, ref.w))
                if ((o > 0.2f || dist < 0.5f * size) && ratio < 1.8f) {
                    pairs += Candidate(-(o + (1f - dist / max(size, 1f)) * 0.5f), di, t)
                }
            }
        }
        pairs.sortBy { it.cost }
        val usedD = HashSet<Int>()
        val usedT = HashSet<Int>()
        for (p in pairs) {
            if (p.di in usedD || p.t.id in usedT) continue
            usedD += p.di
            usedT += p.t.id
            val d = dets[p.di]
            val t = p.t
            t.points[idx] = TrackPoint(d.box, Kind.DETECTED)
            t.last = d.box
            t.lastIdx = idx
            t.lastDetected = d.box
            t.anchor = patch(gray, toPx(d.box))
            t.aspects += d.box.h * gh / (d.box.w * gw)
            t.lost = 0
            t.hits++
            if (d.score >= STRONG_SCORE) t.strongHits++
            t.maxScore = max(t.maxScore, d.score)
        }

        // 3. Unmatched tracks coast on optical flow, under tight checks.
        val detectedNow = active.filter { it.id in usedT }.map { it.points[idx]!!.box }
        for (t in active) {
            if (t.id in usedT) continue
            val p = preds[t.id]
            if (detectionRan) t.lost++
            var dead = p == null || t.lost > maxLost(t) || !onScreen(p)
            if (!dead && p != null) {
                val ld = t.lastDetected
                dead = detectedNow.any { YuNetDetector.overlap(p, it) > 0.3f } ||
                    hypot((p.cx - ld.cx) * gw, (p.cy - ld.cy) * gh) > 0.75f * max(ld.w * gw, ld.h * gh) ||
                    p.w / ld.w !in 0.75f..1.33f ||
                    !similar(t.anchor, patch(gray, toPx(p)))
            }
            if (dead || p == null) {
                t.active = false
                continue
            }
            t.points[idx] = TrackPoint(p, Kind.FLOW)
            t.last = p
            t.lastIdx = idx
        }

        ring.addLast(idx to gray)
        while (ring.size > backFrames + 2) ring.removeFirst().second.release()

        // 4. New faces, followed a little backwards in time.
        for ((di, d) in dets.withIndex()) {
            if (di in usedD || d.score < BIRTH_SCORE) continue
            if (tracks.any { t -> t.points[idx]?.let { YuNetDetector.overlap(d.box, it.box) > 0.3f } == true }) continue
            val t = Track(nextId++)
            t.points[idx] = TrackPoint(d.box, Kind.DETECTED)
            t.last = d.box
            t.lastIdx = idx
            t.lastDetected = d.box
            t.anchor = patch(gray, toPx(d.box))
            t.aspects += d.box.h * gh / (d.box.w * gw)
            t.hits = 1
            t.strongHits = if (d.score >= STRONG_SCORE) 1 else 0
            t.maxScore = d.score
            tracks += t
            extendBackward(t, idx, ::toPx, ::fromPx)
        }
    }

    private fun extendBackward(
        t: Track,
        idx: Int,
        toPx: (Box) -> PxBox,
        fromPx: (PxBox, Float) -> Box,
    ) {
        val frames = ring.toList()
        val start = t.points[idx]!!.box
        var box = start
        var cur = frames.last().second
        var k = frames.size - 2
        var steps = 0
        while (k >= 0 && steps < backFrames) {
            val (fi, prev) = frames[k]
            if (fi != frames[k + 1].first - 1) break
            val p = MedianFlow.track(cur, prev, toPx(box)) ?: break
            val nb = fromPx(p, box.angle)
            if (!onScreen(nb)) break
            val clash = tracks.any { o -> o !== t && o.points[fi]?.let { YuNetDetector.overlap(nb, it.box) > 0.3f } == true }
            if (clash) break
            if (hypot((nb.cx - start.cx) / start.w, (nb.cy - start.cy) / start.h) > 1f) break
            if (nb.w / start.w !in 0.75f..1.33f) break
            if (!similar(t.anchor, patch(prev, toPx(nb)))) break
            t.points[fi] = TrackPoint(nb, Kind.BACKWARD)
            box = nb
            cur = prev
            k--
            steps++
        }
    }

    private fun maxLost(t: Track): Int = min(fps * 0.6f, max(fps * 0.2f, t.strongHits.toFloat())).toInt()

    /**
     * Tracks worth blurring: one very confident detection, or several confident ones that
     * don't look like a hand / fist (square box) or sit under a confident face (hands,
     * chest, gear: YuNet sometimes scores those 0.6-0.75).
     */
    fun finish(): List<Track> {
        val sure = tracks.filter { it.maxScore >= SURE_SCORE }
        val keep = ArrayList(sure)
        for (t in tracks) {
            if (t.maxScore >= SURE_SCORE || t.strongHits < 3) continue
            if (median(t.aspects) < 1f) continue
            val detFrames = t.points.filterValues { it.kind == Kind.DETECTED }.keys
            var below = 0
            for (i in detFrames) {
                val b = t.points[i]!!.box
                val underFace = sure.any { o ->
                    val q = o.points[i]
                    if (q == null || q.kind != Kind.DETECTED) false
                    else {
                        val ob = q.box
                        val dy = (b.cy - ob.cy) / ob.h
                        dy in 0.8f..4f && kotlin.math.abs(b.cx - ob.cx) < 1.2f * ob.w && b.w / ob.w in 0.5f..2f
                    }
                }
                if (underFace) below++
            }
            if (detFrames.isNotEmpty() && below * 2 >= detFrames.size) continue
            keep += t
        }
        return keep
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    private fun clearRing() {
        while (ring.isNotEmpty()) ring.removeFirst().second.release()
    }

    fun release() = clearRing()

    companion object {
        const val BIRTH_SCORE = 0.7f
        const val STRONG_SCORE = 0.6f
        const val SURE_SCORE = 0.85f
        private const val BACKWARD_SECONDS = 0.6f
        private const val PATCH = 32
        private const val SIMILARITY = 0.45f

        fun onScreen(b: Box): Boolean =
            b.cx + b.w / 2 > 0f && b.cx - b.w / 2 < 1f && b.cy + b.h / 2 > 0f && b.cy - b.h / 2 < 1f

        /** Zero-mean, unit-norm 32x32 patch of a box; null if it's mostly off-frame. */
        fun patch(gray: Mat, b: PxBox): FloatArray? {
            val x0 = (b.cx - b.w / 2).toInt()
            val y0 = (b.cy - b.h / 2).toInt()
            val x1 = (b.cx + b.w / 2).toInt()
            val y1 = (b.cy + b.h / 2).toInt()
            if (x1 - x0 < 4 || y1 - y0 < 4) return null
            val cx0 = max(0, x0)
            val cy0 = max(0, y0)
            val cx1 = min(gray.cols(), x1)
            val cy1 = min(gray.rows(), y1)
            if (cx1 <= cx0 || cy1 <= cy0) return null
            if ((cx1 - cx0) * (cy1 - cy0) < 0.6f * (x1 - x0) * (y1 - y0)) return null
            val sub = gray.submat(Rect(cx0, cy0, cx1 - cx0, cy1 - cy0))
            val small = Mat()
            Imgproc.resize(sub, small, Size(PATCH.toDouble(), PATCH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val bytes = ByteArray(PATCH * PATCH)
            small.get(0, 0, bytes)
            small.release()
            sub.release()
            val v = FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF).toFloat() }
            val mean = v.average().toFloat()
            var norm = 0f
            for (i in v.indices) {
                v[i] -= mean
                norm += v[i] * v[i]
            }
            norm = sqrt(norm)
            if (norm < 1e-3f) return null
            for (i in v.indices) v[i] /= norm
            return v
        }

        fun similar(a: FloatArray?, b: FloatArray?): Boolean {
            if (a == null || b == null) return false
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot >= SIMILARITY
        }
    }
}

/**
 * Hard-cut detector for edited videos: a cut is when a tiny thumbnail of the frame changes a
 * lot AND its brightness histogram changes a lot (fast pans change the first, not the second).
 */
class CutDetector {
    private var prevThumb: FloatArray? = null
    private var prevHist: FloatArray? = null
    private val thumb = Mat()

    fun isCut(gray: Mat): Boolean {
        Imgproc.resize(gray, thumb, Size(32.0, 18.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val tb = ByteArray(32 * 18)
        thumb.get(0, 0, tb)
        val th = FloatArray(tb.size) { (tb[it].toInt() and 0xFF).toFloat() }

        val n = gray.cols() * gray.rows()
        val all = ByteArray(n)
        gray.get(0, 0, all)
        val hist = FloatArray(32)
        for (b in all) hist[(b.toInt() and 0xFF) shr 3] += 1f
        for (i in hist.indices) hist[i] /= n.toFloat()

        val pt = prevThumb
        val ph = prevHist
        prevThumb = th
        prevHist = hist
        if (pt == null || ph == null) return false

        var diff = 0f
        for (i in th.indices) diff += kotlin.math.abs(th[i] - pt[i])
        diff /= th.size
        val corr = correlation(hist, ph)
        return (diff > 25f && corr < 0.75f) || diff > 45f
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den < 1e-12f) 1f else num / den
    }
}
