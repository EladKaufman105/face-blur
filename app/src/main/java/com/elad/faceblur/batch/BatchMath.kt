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
