package com.elad.faceblur.batch

import com.elad.faceblur.batch.BatchMath.ItemTiming
import com.elad.faceblur.batch.BatchMath.Phase
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
        val items = listOf(ItemTiming(60_000, 1f, Phase.DONE, one), ItemTiming(60_000, 0f, Phase.WAITING, 0))
        assertEquals(one.toDouble(), BatchMath.remainingMs(items, s, speeds).toDouble(), 1.0)
    }

    @Test
    fun aSlowerPhoneThanEstimatedStretchesTheVideosStillToCome() {
        // The first video took twice the estimate: the waiting one is expected to as well.
        val items = listOf(ItemTiming(60_000, 1f, Phase.DONE, 2 * one), ItemTiming(60_000, 0f, Phase.WAITING, 0))
        assertEquals(2.0 * one, BatchMath.remainingMs(items, s, speeds).toDouble(), 1.0)
    }

    @Test
    fun theRunningVideoFollowsItsOwnPaceOnceItIsWellAlong() {
        // Half way after 100 s (the estimate said about 56 s for half): about 100 s to go.
        val items = listOf(ItemTiming(60_000, 0.5f, Phase.RUNNING, 100_000))
        assertEquals(100_000.0, BatchMath.remainingMs(items, s, speeds).toDouble(), 2_000.0)
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
}
