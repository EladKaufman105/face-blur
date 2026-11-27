package com.elad.faceblur.pipeline

import android.media.MediaCodecList
import com.elad.faceblur.analysis.Series

/** What the sound goes through in the export (the progress screen's stats panel lists them in this order). */
enum class AudioStep { COPY, PITCH, VOICE, SFX, CUTS, MUTED }

/** What is known when the export starts. */
data class ExportFacts(
    val srcWidth: Int,
    val srcHeight: Int,
    /** The result's size (px). */
    val width: Int,
    val height: Int,
    /** The video codec's MIME type ("video/avc", "video/hevc"). */
    val mime: String,
    /** The encoder this device lists first for [mime] (what the export most likely uses), and whether it is hardware; null when none is listed. */
    val encoder: String?,
    val hardware: Boolean?,
    /** Bits a second asked of the encoder. */
    val bitrate: Int,
    /** The source's frame rate and the result's. */
    val fps: Float,
    val outFps: Float,
    /** The frames the blur step is given (the source's, an estimate). */
    val totalFrames: Int,
    val audio: List<AudioStep>,
)

/** The counters at one moment: totals since the export began (or since its last try, after an HDR fallback). */
data class ExportSnapshot(
    /** When it was read (SystemClock.elapsedRealtime). */
    val atMs: Long,
    /** Frames that came to the blur step (a frame that is cut out counts too: they are what the progress counts). */
    val frames: Int,
    /** The time they spent in it, ms (on the GL thread; the GPU may finish its part later). */
    val blurMs: Double,
    val facts: ExportFacts?,
)

/**
 * Counted by the export's effects while it runs (the GL thread writes, the preview callback reads),
 * the same idea as the analysis' counters. Frames come to the blur step one after another.
 */
class ExportCounters {
    @Volatile var facts: ExportFacts? = null
    @Volatile var frames = 0
        private set
    @Volatile private var blurNanos = 0L

    /** One frame went through the blur step, in [nanos]. */
    fun onFrame(nanos: Long) {
        frames++
        blurNanos += nanos
    }

    /** A new try of the export (the HDR fallback) counts from zero. */
    fun reset() {
        frames = 0
        blurNanos = 0L
    }

    fun snapshot(atMs: Long) = ExportSnapshot(atMs, frames, blurNanos / 1e6, facts)
}

/** What the panel shows: current values (over the last second or so, not the whole export) and the last minute of the two main ones. */
class ExportStats(
    val facts: ExportFacts,
    val latest: ExportSnapshot,
    /** Frames a second now and over the whole export so far. */
    val fps: Float,
    val fpsAvg: Float,
    /** ms a frame spends in the blur step, now. */
    val blurMs: Float,
    val fpsHistory: Series,
    val blurHistory: Series,
)

/**
 * Turns the snapshots of one export into [ExportStats]: about once a second each value is what
 * happened in that second (the time added over the frames added), or the value before when
 * nothing did; the last [keep] are kept. Not thread safe: fed from one thread.
 */
class ExportHistory(private val keep: Int = 60, private val stepMs: Long = 1000L) {
    private var first: ExportSnapshot? = null
    private var base: ExportSnapshot? = null
    private var fps = 0f
    private var blur = 0f
    private val fpsH = ArrayDeque<Float>()
    private val blurH = ArrayDeque<Float>()

    /** Null until the export's facts are known. */
    fun add(s: ExportSnapshot): ExportStats? {
        val facts = s.facts ?: return null
        val f = first ?: s.also { first = it }
        val b = base
        if (b == null || s.frames < b.frames) {
            // The first snapshot, or the export started over (the HDR fallback): from here on.
            base = s
            if (b != null) first = s
        } else if (s.atMs - b.atMs < stepMs) {
            // Until a whole second is in: the values since the start.
            if (fpsH.isEmpty()) rates(first!!, s)
        } else {
            rates(b, s)
            push(fpsH, fps)
            push(blurH, blur)
            base = s
        }
        val start = first!!
        val whole = s.atMs - start.atMs
        return ExportStats(
            facts = facts,
            latest = s,
            fps = fps,
            fpsAvg = if (whole > 0) (s.frames - start.frames) * 1000f / whole else 0f,
            blurMs = blur,
            fpsHistory = Series(fpsH.toFloatArray()),
            blurHistory = Series(blurH.toFloatArray()),
        )
    }

    private fun rates(b: ExportSnapshot, s: ExportSnapshot) {
        val dt = s.atMs - b.atMs
        val df = s.frames - b.frames
        if (dt > 0 && df >= 0) fps = df * 1000f / dt
        if (df > 0) blur = ((s.blurMs - b.blurMs) / df).toFloat().coerceAtLeast(0f)
    }

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > keep) q.removeFirst()
    }
}

/**
 * The encoder this device lists first for [mime] that can take a [width] x [height] frame at [bitrate] (hardware ones first), and
 * whether it is hardware: what the export most likely uses (the transformer picks the same way, but doesn't say which it did until
 * it is done). With none that fits, the first listed one, so the panel still names something.
 */
fun likelyEncoder(mime: String, width: Int, height: Int, bitrate: Int): Pair<String, Boolean>? = runCatching {
    val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
    fun fits(i: android.media.MediaCodecInfo): Boolean = runCatching {
        val v = i.getCapabilitiesForType(mime).videoCapabilities
        v.isSizeSupported(width, height) && v.bitrateRange.contains(bitrate)
    }.getOrDefault(false)
    fun hw(i: android.media.MediaCodecInfo) = i.isHardwareAccelerated && !i.isSoftwareOnly
    val pick = all.firstOrNull { hw(it) && fits(it) } ?: all.firstOrNull { fits(it) } ?: all.firstOrNull { hw(it) } ?: all.firstOrNull()
    pick?.let { it.name to hw(it) }
}.getOrNull()
