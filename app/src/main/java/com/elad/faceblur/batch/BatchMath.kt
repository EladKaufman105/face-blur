package com.elad.faceblur.batch

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Det
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.template.TemplateSettings
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max

/** Time and space a batch needs, from what the app has learned about this phone's speed. */
object BatchMath {
    /** The most videos in one batch. */
    const val MAX_VIDEOS = 10

    /**
     * Learned speeds: [analysisRatio] analysis time per second of video, [renderRatio] render time
     * per unit of analysis time, [subsSpeed] seconds of work per second of audio (the speech model's).
     */
    class Speeds(val analysisRatio: Float, val renderRatio: Float, val subsSpeed: Float)

    /** Estimated working time of one video of [durationMs], in ms. */
    fun videoMs(durationMs: Long, s: TemplateSettings, speeds: Speeds): Long {
        val analysis = durationMs * speeds.analysisRatio.coerceIn(0.1f, 20f)
        // Without face blur the analysis is a quick read of the frame times only.
        val first = if (s.blurOff) durationMs * 0.02f else analysis
        val render = analysis * speeds.renderRatio.coerceIn(0.2f, 5f)
        var extra = 0f
        if (s.createSubs) extra += durationMs * speeds.subsSpeed.coerceIn(0.05f, 10f) * (if (s.translate) 1.15f else 1f)
        if (s.audio.loud) extra += durationMs * 0.03f
        if (s.cutSilences) extra += durationMs * 0.03f
        // The people are found in every frame: about half an analysis pass.
        if (s.background.active && s.background.key == com.elad.faceblur.effects.BgKey.PERSON) extra += analysis * 0.5f
        // A pass over the video for the camera's motion, when face blur didn't measure it.
        if (s.stabilize > 0 && s.blurOff) extra += analysis * 0.4f
        return (first + render + extra).toLong() + OVERHEAD_MS
    }

    enum class Phase { WAITING, RUNNING, DONE }

    /** One video's state for the time left: its length, how far it is (0..1), and how long it has worked (or worked in all, when done). */
    class ItemTiming(val durationMs: Long, val progress: Float, val phase: Phase, val elapsedMs: Long)

    /**
     * Time left for the whole batch, in ms. A video not started takes its estimate from the
     * learned speeds, scaled by how far off those estimates were on the videos already done. The
     * one running mixes its own pace so far (elapsed over progress) with that estimate, trusting
     * the pace more the further along it is.
     */
    fun remainingMs(items: List<ItemTiming>, s: TemplateSettings, speeds: Speeds): Long {
        var est = 0.0
        var actual = 0.0
        for (i in items) if (i.phase == Phase.DONE && i.elapsedMs > 0) {
            est += videoMs(i.durationMs, s, speeds)
            actual += i.elapsedMs
        }
        val scale = if (est > 0) (actual / est).coerceIn(0.4, 2.5) else 1.0
        var total = 0.0
        for (i in items) when (i.phase) {
            Phase.WAITING -> total += videoMs(i.durationMs, s, speeds) * scale
            Phase.RUNNING -> {
                val p = i.progress.coerceIn(0f, 1f)
                val byEstimate = videoMs(i.durationMs, s, speeds) * scale * (1 - p)
                val byPace = if (p > 0.02f) i.elapsedMs * (1 - p) / p else byEstimate
                val w = (p / 0.3f).coerceIn(0f, 1f)
                total += w * byPace + (1 - w) * byEstimate
            }
            Phase.DONE -> {}
        }
        return total.toLong()
    }

    fun totalMs(durationsMs: List<Long>, s: TemplateSettings, speeds: Speeds): Long = durationsMs.sumOf { videoMs(it, s, speeds) }

    /**
     * Space the outputs need: each saved to the gallery, plus the same again while it is being
     * written, the project's data (about a quarter), with a margin.
     */
    fun neededBytes(infos: List<VideoInfo>, export: ExportOptions): Long {
        val outputs = infos.sumOf { export.estimatedBytes(it) }
        val biggest = infos.maxOfOrNull { export.estimatedBytes(it) } ?: 0L
        return ((outputs + biggest) * 1.25 + infos.size * 8_000_000L).toLong()
    }

    private const val OVERHEAD_MS = 4_000L

    /**
     * How many automatic face tracks have no clear detection behind them (the best detection
     * inside the track's box, or its re-check, scored under [clear]): faces to look at again.
     */
    fun lowConfidenceTracks(tracks: List<TreeMap<Int, Box>>, dets: Map<Int, List<Det>>, clear: Float = 0.8f): Int {
        var low = 0
        for (t in tracks) {
            var best = 0f
            for ((frame, box) in t) {
                val ds = dets[frame] ?: continue
                for (d in ds) {
                    val inside = abs(d.box.cx - box.cx) <= max(box.w, d.box.w) * 0.75f && abs(d.box.cy - box.cy) <= max(box.h, d.box.h) * 0.75f
                    if (inside) best = max(best, max(d.score, d.verify))
                }
            }
            if (best < clear) low++
        }
        return low
    }
}
