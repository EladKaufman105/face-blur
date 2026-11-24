package com.elad.faceblur.batch

import com.elad.faceblur.batch.BatchMath.ItemTiming
import com.elad.faceblur.batch.BatchMath.Phase
import com.elad.faceblur.batch.BatchMath.Stage
import com.elad.faceblur.template.TemplateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchEtaTest {
    private val speeds = BatchMath.Speeds(analysisRatio = 1f, renderRatio = 0.8f, subsSpeed = 0.6f)
    private val s = TemplateSettings()
    private val one = BatchMath.videoMs(60_000, s, speeds)

    @Test
    fun nothingStartedIsTheSumOfTheEstimates() {
        val items = List(3) { ItemTiming(60_000, 0f, Phase.WAITING, 0) }
        assertEquals(3 * one, BatchMath.remainingMs(items, s, speeds))
    }

    @Test
    fun finishedVideosCostNothing() {
        val items = listOf(ItemTiming(60_000, 1f, Phase.DONE, one, saved = true), ItemTiming(60_000, 0f, Phase.WAITING, 0))
        assertEquals(one.toDouble(), BatchMath.remainingMs(items, s, speeds).toDouble(), 1.0)
    }

    @Test
    fun aSlowerPhoneThanEstimatedStretchesTheVideosStillToCome() {
        // The first video took twice the estimate: the waiting one is expected to as well.
        val items = listOf(ItemTiming(60_000, 1f, Phase.DONE, 2 * one, saved = true), ItemTiming(60_000, 0f, Phase.WAITING, 0))
        assertEquals(2.0 * one, BatchMath.remainingMs(items, s, speeds).toDouble(), 1.0)
    }

    @Test
    fun theRunningVideoFollowsItsOwnPaceOnceItIsWellAlong() {
        // Half way through the export after 100 s in it (the estimate said 48 s for all of it): about 100 s to go.
        val start = BatchMath.ANALYSIS_SHARE + BatchMath.PROCESSING_SHARE
        val half = start + (1 - start) / 2
        val items = listOf(ItemTiming(60_000, half, Phase.RUNNING, 200_000, stage = Stage.EXPORTING, stageElapsedMs = 100_000))
        assertEquals(100_000.0, BatchMath.remainingMs(items, s, speeds).toDouble(), 5_000.0)
    }

    @Test
    fun theRunningVideoJustStartedLeansOnTheEstimate() {
        val items = listOf(ItemTiming(60_000, 0f, Phase.RUNNING, 500))
        assertEquals(one.toDouble(), BatchMath.remainingMs(items, s, speeds).toDouble(), 1.0)
    }

    @Test
    fun skippedVideosAreNotCounted() {
        val items = listOf(ItemTiming(60_000, 0f, Phase.DONE, 0), ItemTiming(60_000, 0f, Phase.WAITING, 0))
        assertEquals(one, BatchMath.remainingMs(items, s, speeds))
    }

    @Test
    fun theEstimateNeverGoesNegative() {
        val items = listOf(ItemTiming(60_000, 1f, Phase.RUNNING, 10_000))
        assertTrue(BatchMath.remainingMs(items, s, speeds) >= 0)
    }

    @Test
    fun onlySavedVideosTeachTheScale() {
        // A video that failed early (it worked 1 s) or was skipped must not make the rest look fast.
        val items = listOf(
            ItemTiming(60_000, 0f, Phase.DONE, 1_000, saved = false),
            ItemTiming(60_000, 0f, Phase.DONE, 500, saved = false),
            ItemTiming(60_000, 0f, Phase.WAITING, 0),
        )
        assertEquals(one, BatchMath.remainingMs(items, s, speeds))
        // The same time on a saved video does scale them.
        val saved = listOf(ItemTiming(60_000, 1f, Phase.DONE, 2 * one, saved = true), ItemTiming(60_000, 0f, Phase.WAITING, 0))
        assertEquals(2.0 * one, BatchMath.remainingMs(saved, s, speeds).toDouble(), 1.0)
    }

    @Test
    fun timeLeftDoesNotClimbWhileSubtitlesAreCreated() {
        val subs = TemplateSettings(createSubs = true)
        val share = BatchMath.ANALYSIS_SHARE
        // The bar stands still at the end of the analysis while the speech is recognized.
        var last = Long.MAX_VALUE
        for (sec in 0..180 step 10) {
            val left = BatchMath.remainingMs(
                listOf(ItemTiming(60_000, share, Phase.RUNNING, 60_000L + sec * 1000L, stage = Stage.PROCESSING, stageElapsedMs = sec * 1000L)),
                subs, speeds,
            )
            assertTrue("$sec s: $left should not be more than $last", left <= last)
            last = left
        }
        // And it still counts the export that comes after.
        assertTrue(last >= BatchMath.stages(60_000, subs, speeds).render * 0.9)
    }

    @Test
    fun theAnalysisFollowsItsOwnShareOfTheBar() {
        // Half of the analysis done after 30 s: about 30 s of analysis left, then the rest as estimated.
        val st = BatchMath.stages(60_000, s, speeds)
        val items = listOf(ItemTiming(60_000, BatchMath.ANALYSIS_SHARE / 2, Phase.RUNNING, 30_000, stage = Stage.ANALYZING, stageElapsedMs = 30_000))
        val left = BatchMath.remainingMs(items, s, speeds).toDouble()
        assertEquals(30_000.0 + st.processing + st.render, left, 8_000.0)
    }

    @Test
    fun withoutFaceBlurTheAnalysisGetsOnlyASliverOfTheBar() {
        val off = TemplateSettings(blurOff = true)
        assertEquals(BatchMath.FRAMES_ONLY_SHARE, BatchMath.analysisShare(off), 0f)
        assertEquals(BatchMath.ANALYSIS_SHARE, BatchMath.analysisShare(s), 0f)
        assertTrue(BatchMath.analysisShare(off) < BatchMath.ANALYSIS_SHARE / 4)
    }

    @Test
    fun withoutFaceBlurAVideoPastItsFrameReadIsNotCountedAsStillAnalyzing() {
        val off = TemplateSettings(blurOff = true)
        val st = BatchMath.stages(60_000, off, speeds)
        // The analysis stage is the quick frame read, not a face pass.
        assertTrue(st.analysis < 10_000)
        // Frame read done (the whole of its share) after 1 s: what is left is the render, not 40% of a bar's worth of analysis.
        val items = listOf(ItemTiming(60_000, BatchMath.FRAMES_ONLY_SHARE, Phase.RUNNING, 1_000, stage = Stage.ANALYZING, stageElapsedMs = 1_000))
        val left = BatchMath.remainingMs(items, off, speeds).toDouble()
        assertEquals(st.processing + st.render, left, 3_000.0)
    }
}
