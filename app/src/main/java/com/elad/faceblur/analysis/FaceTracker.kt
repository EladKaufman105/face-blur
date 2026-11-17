package com.elad.faceblur.analysis

import org.opencv.core.Mat
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Kind { DETECTED, FLOW, BACKWARD, INTERPOLATED }

class TrackPoint(val box: Box, val kind: Kind)

class Track(val id: Int) {
    val points = TreeMap<Int, TrackPoint>()
    lateinit var last: Box
    var lastIdx = -1
    var lost = 0
    var hits = 0
    var maxScore = 0f
    var active = true
}

/**
 * Online multi-face tracker over the analysis frames.
 *
 * - Detections are matched to each track's median-flow prediction.
 * - When the detector misses a face (profile, blur, occlusion), the track keeps going on
 *   optical flow alone for a while.
 * - When a new face is found, it is followed backwards through the last ~2 s of frames, so
 *   the frames before the first detection (face entering, turning) get covered too.
 */
class FaceTracker(private val fps: Float) {

    private val ringCapacity = (fps * BACKWARD_SECONDS).roundToInt() + 2
    private val ring = ArrayDeque<Pair<Int, Mat>>()
    private val tracks = ArrayList<Track>()
    private var nextId = 1

    fun step(idx: Int, dets: List<Det>, detectionRan: Boolean, gray: Mat) {
        val gw = gray.cols().toFloat()
        val gh = gray.rows().toFloat()
        fun toPx(b: Box) = PxBox(b.cx * gw, b.cy * gh, b.w * gw, b.h * gh)
        fun fromPx(p: PxBox, angle: Float) = Box(p.cx / gw, p.cy / gh, p.w / gw, p.h / gh, angle)

        val prevGray = ring.peekLast()?.takeIf { it.first == idx - 1 }?.second
        val active = tracks.filter { it.active }

        // 1. Predict every active track into this frame with optical flow.
        val preds = HashMap<Int, Box?>()
        for (t in active) {
            val p = if (prevGray != null && t.lastIdx == idx - 1) MedianFlow.track(prevGray, gray, toPx(t.last)) else null
            preds[t.id] = p?.let { fromPx(it, t.last.angle) }
        }

        // 2. Greedy association of detections to predictions.
        class Pair3(val cost: Float, val di: Int, val t: Track)
        val pairs = ArrayList<Pair3>()
        for ((di, d) in dets.withIndex()) {
            for (t in active) {
                val ref = preds[t.id] ?: t.last
                val o = YuNetDetector.iou(d.box, ref)
                val dist = hypot((d.box.cx - ref.cx) * gw, (d.box.cy - ref.cy) * gh)
                val size = max(ref.w * gw, ref.h * gh)
                val ratio = max(d.box.w, ref.w) / max(1e-6f, min(d.box.w, ref.w))
                if ((o > 0.2f || dist < 0.5f * size) && ratio < 1.8f) {
                    pairs += Pair3(-(o + (1f - dist / max(size, 1f)) * 0.5f), di, t)
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
            p.t.points[idx] = TrackPoint(d.box, Kind.DETECTED)
            p.t.last = d.box
            p.t.lastIdx = idx
            p.t.lost = 0
            p.t.hits++
            p.t.maxScore = max(p.t.maxScore, d.score)
        }

        // 3. Unmatched tracks coast on optical flow.
        for (t in active) {
            if (t.id in usedT) continue
            val p = preds[t.id]
            if (detectionRan) t.lost++
            if (p == null || t.lost > maxLost(t) || !onScreen(p)) {
                t.active = false
                continue
            }
            t.points[idx] = TrackPoint(p, Kind.FLOW)
            t.last = p
            t.lastIdx = idx
        }

        ring.addLast(idx to gray)
        while (ring.size > ringCapacity) ring.removeFirst().second.release()

        // 4. New faces, followed backwards in time.
        for ((di, d) in dets.withIndex()) {
            if (di in usedD || d.score < BIRTH_SCORE) continue
            if (tracks.any { t -> t.points[idx]?.let { YuNetDetector.iou(d.box, it.box) > 0.3f } == true }) continue
            val t = Track(nextId++)
            t.points[idx] = TrackPoint(d.box, Kind.DETECTED)
            t.last = d.box
            t.lastIdx = idx
            t.hits = 1
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
        var box = t.points[idx]!!.box
        var cur = frames.last().second
        val limit = (fps * BACKWARD_SECONDS).roundToInt()
        var k = frames.size - 2
        var steps = 0
        while (k >= 0 && steps < limit) {
            val (fi, prev) = frames[k]
            if (fi != frames[k + 1].first - 1) break
            val p = MedianFlow.track(cur, prev, toPx(box)) ?: break
            val nb = fromPx(p, box.angle)
            if (!onScreen(nb)) break
            val clash = tracks.any { o -> o !== t && o.points[fi]?.let { YuNetDetector.iou(nb, it.box) > 0.5f } == true }
            if (clash) break
            t.points[fi] = TrackPoint(nb, Kind.BACKWARD)
            box = nb
            cur = prev
            k--
            steps++
        }
    }

    private fun maxLost(t: Track): Int = min(fps * 2f, max(fps * 0.5f, t.hits * 2f)).toInt()

    /** Tracks worth blurring (drops one-frame low-confidence blips). */
    fun finish(): List<Track> = tracks.filter { it.hits >= 2 || it.maxScore >= 0.75f }

    fun release() {
        while (ring.isNotEmpty()) ring.removeFirst().second.release()
    }

    companion object {
        const val BIRTH_SCORE = 0.6f
        private const val BACKWARD_SECONDS = 2f

        fun onScreen(b: Box): Boolean =
            b.cx + b.w / 2 > 0f && b.cx - b.w / 2 < 1f && b.cy + b.h / 2 > 0f && b.cy - b.h / 2 < 1f
    }
}
