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

    /** The shares of an item's progress bar: the analysis, then the template's processing, then the export. */
    const val ANALYSIS_SHARE = 0.4f
    const val PROCESSING_SHARE = 0.15f

    /** Estimated time of each stage of one video, in ms. */
    class Stages(val analysis: Double, val processing: Double, val render: Double) {
        val total: Double get() = analysis + processing + render
    }

    fun stages(durationMs: Long, s: TemplateSettings, speeds: Speeds): Stages {
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
        return Stages(first.toDouble() + OVERHEAD_MS, extra.toDouble(), render.toDouble())
    }

    /** Estimated working time of one video of [durationMs], in ms. */
    fun videoMs(durationMs: Long, s: TemplateSettings, speeds: Speeds): Long = stages(durationMs, s, speeds).total.toLong()

    enum class Phase { WAITING, RUNNING, DONE }

    /** Where a running video is: reading the faces, the template's processing, or writing the file. */
    enum class Stage { ANALYZING, PROCESSING, EXPORTING }

    /**
     * One video's state for the time left: its length, how far it is (0..1 over the whole video),
     * and how long it has worked (or worked in all, when done). [saved]: it ended with a saved
     * video (only those teach the estimate's scale: a failed or skipped video stopped early).
     * [stage] and [stageElapsedMs]: for a running video, which stage it is in and for how long.
     */
    class ItemTiming(
        val durationMs: Long,
        val progress: Float,
        val phase: Phase,
        val elapsedMs: Long,
        val saved: Boolean = false,
        val stage: Stage = Stage.ANALYZING,
        val stageElapsedMs: Long = elapsedMs,
    )

    /**
     * Time left for the whole batch, in ms. A video not started takes its estimate from the
     * learned speeds, scaled by how far off those estimates were on the videos already saved. The
     * one running adds up the stages still to come; the stage it is in follows its own pace in
     * that stage once it is well along, and the estimate before. While the template's processing
     * runs there is no measure of how far it is, so it counts down the estimate for that stage
     * (never climbing as time passes).
     */
    fun remainingMs(items: List<ItemTiming>, s: TemplateSettings, speeds: Speeds): Long {
        var est = 0.0
        var actual = 0.0
        for (i in items) if (i.phase == Phase.DONE && i.saved && i.elapsedMs > 0) {
            est += videoMs(i.durationMs, s, speeds)
            actual += i.elapsedMs
        }
        val scale = if (est > 0) (actual / est).coerceIn(0.4, 2.5) else 1.0
        var total = 0.0
        for (i in items) when (i.phase) {
            Phase.WAITING -> total += videoMs(i.durationMs, s, speeds) * scale
            Phase.RUNNING -> total += runningMs(i, stages(i.durationMs, s, speeds), scale)
            Phase.DONE -> {}
        }
        return total.toLong().coerceAtLeast(0L)
    }

    /** What is left of a stage that is [p] done after [elapsedMs], from its [estimateMs] and its own pace. */
    private fun stageLeft(estimateMs: Double, p: Double, elapsedMs: Long): Double {
        val byEstimate = estimateMs * (1 - p)
        val byPace = if (p > 0.02) elapsedMs * (1 - p) / p else byEstimate
        val w = (p / 0.3).coerceIn(0.0, 1.0)
        return w * byPace + (1 - w) * byEstimate
    }

    private fun runningMs(i: ItemTiming, st: Stages, scale: Double): Double {
        val a = st.analysis * scale
        val pr = st.processing * scale
        val r = st.render * scale
        val p = i.progress.coerceIn(0f, 1f).toDouble()
        return when (i.stage) {
            Stage.ANALYZING -> stageLeft(a, (p / ANALYSIS_SHARE).coerceIn(0.0, 1.0), i.stageElapsedMs) + pr + r
            // No progress inside this stage: count down its estimate, keeping a little for what may still be going on.
            Stage.PROCESSING -> max(pr - i.stageElapsedMs, pr * 0.15) + r
            Stage.EXPORTING -> {
                val start = ANALYSIS_SHARE + PROCESSING_SHARE
                stageLeft(r, ((p - start) / (1 - start)).coerceIn(0.0, 1.0), i.stageElapsedMs)
            }
        }
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
