package com.elad.faceblur.track

import com.elad.faceblur.effects.FrameDetections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One detection: normalized center + size, top-left origin. */
class Det(val t: Long, val cx: Float, val cy: Float, val w: Float, val h: Float)

class Track(val dets: MutableList<Det>) {
    val first get() = dets.first()
    val last get() = dets.last()
}

/** A face region to cover in one frame: normalized ellipse, top-left origin. */
class FaceShape(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/**
 * Links per-frame detections into tracks, then answers "where are the faces at time t"
 * with interpolation across missed frames and a safety margin before/after each track,
 * so a face isn't exposed in the frames the detector missed.
 */
class FaceTimeline(private val tracks: List<Track>, private val padding: Float) {

    val trackCount get() = tracks.size

    fun facesAt(tUs: Long): List<FaceShape> {
        val out = ArrayList<FaceShape>()
        for (tr in tracks) {
            val first = tr.first.t
            val last = tr.last.t
            if (tUs < first - LEAD_US || tUs > last + TAIL_US) continue

            val d: Det
            val gap: Long
            when {
                tUs <= first -> { d = tr.first; gap = first - tUs }
                tUs >= last -> { d = tr.last; gap = tUs - last }
                else -> {
                    val i = floorIndex(tr.dets, tUs)
                    val a = tr.dets[i]
                    val b = tr.dets[i + 1]
                    val f = (tUs - a.t).toFloat() / max(1L, b.t - a.t)
                    d = Det(
                        tUs,
                        a.cx + (b.cx - a.cx) * f,
                        a.cy + (b.cy - a.cy) * f,
                        a.w + (b.w - a.w) * f,
                        a.h + (b.h - a.h) * f,
                    )
                    gap = min(tUs - a.t, b.t - tUs)
                }
            }
            // The further from a real detection, the less sure we are where the face is.
            val uncertainty = 1f + 0.6f * min(1f, gap / 500_000f)
            val w = d.w * padding * uncertainty
            val h = d.h * padding * uncertainty
            out += FaceShape(
                cx = d.cx,
                cy = d.cy - 0.08f * d.h, // shift up to cover forehead / hair
                rx = w / 2f,
                ry = h / 2f * 1.15f,
            )
        }
        return out
    }

    private fun floorIndex(list: List<Det>, t: Long): Int {
        var lo = 0
        var hi = list.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (list[mid].t <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        const val LEAD_US = 300_000L
        const val TAIL_US = 400_000L
        private const val MAX_GAP_US = 1_000_000L

        /**
         * Greedy nearest-neighbour association between consecutive analyzed frames.
         * [aspect] = frame width / height, so distances are measured in square units.
         */
        fun build(frames: List<FrameDetections>, aspect: Float, padding: Float): FaceTimeline {
            val all = ArrayList<Track>()
            val active = ArrayList<Track>()

            for (frame in frames) {
                active.removeAll { frame.timeUs - it.last.t > MAX_GAP_US }
                val dets = frame.boxes.map {
                    Det(frame.timeUs, it.centerX(), it.centerY(), it.width(), it.height())
                }
                val pairs = ArrayList<Triple<Int, Int, Float>>()
                for (di in dets.indices) for (ti in active.indices) {
                    val d = dets[di]
                    val l = active[ti].last
                    if (l.t == d.t) continue
                    val dx = (d.cx - l.cx) * aspect
                    val dy = d.cy - l.cy
                    val dist = sqrt(dx * dx + dy * dy)
                    val size = max(max(d.w * aspect, d.h), max(l.w * aspect, l.h))
                    val gapSec = (frame.timeUs - l.t) / 1_000_000f
                    val allowed = size * (0.8f + 2f * gapSec)
                    val ratio = max(d.w, l.w) / max(1e-6f, min(d.w, l.w))
                    if (dist < allowed && ratio < 2.2f) pairs += Triple(di, ti, dist / max(size, 1e-6f))
                }
                pairs.sortBy { it.third }
                val usedD = HashSet<Int>()
                val usedT = HashSet<Int>()
                for ((di, ti, _) in pairs) {
                    if (di in usedD || ti in usedT) continue
                    usedD += di
                    usedT += ti
                    active[ti].dets += dets[di]
                }
                for (di in dets.indices) {
                    if (di in usedD) continue
                    val t = Track(mutableListOf(dets[di]))
                    all += t
                    active += t
                }
            }
            return FaceTimeline(all.map(::smooth), padding)
        }

        /** Light smoothing: centers by moving average, sizes by moving max (never shrink-flicker). */
        private fun smooth(track: Track): Track {
            val src = track.dets
            if (src.size < 3) return track
            val out = ArrayList<Det>(src.size)
            for (i in src.indices) {
                val a = max(0, i - 1)
                val b = min(src.lastIndex, i + 1)
                var cx = 0f
                var cy = 0f
                for (j in a..b) {
                    cx += src[j].cx
                    cy += src[j].cy
                }
                val n = (b - a + 1).toFloat()
                var w = 0f
                var h = 0f
                for (j in max(0, i - 2)..min(src.lastIndex, i + 2)) {
                    w = max(w, src[j].w)
                    h = max(h, src[j].h)
                }
                // Keep fast motion responsive: blend toward the raw center when it moved a lot.
                val raw = src[i]
                val moved = abs(raw.cx - cx / n) + abs(raw.cy - cy / n)
                val k = if (moved > raw.w * 0.5f) 0.7f else 0f
                out += Det(
                    raw.t,
                    cx / n * (1 - k) + raw.cx * k,
                    cy / n * (1 - k) + raw.cy * k,
                    w,
                    h,
                )
            }
            return Track(out)
        }
    }
}
