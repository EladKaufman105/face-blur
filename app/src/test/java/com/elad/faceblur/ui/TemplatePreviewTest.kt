package com.elad.faceblur.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatePreviewTest {
    @Test
    fun animationsAreOffOnlyWhenTheScaleIsZero() {
        assertTrue(animationsOff(0f))
        assertFalse(animationsOff(0.5f))
        assertFalse(animationsOff(1f))
    }

    @Test
    fun motionReducedShowsOneStillFrame() {
        assertEquals(previewTimeUs(0f, true), previewTimeUs(2000f, true))
        assertEquals(1_100_000L, previewTimeUs(0f, true))
    }

    @Test
    fun theLoopMovesThroughTheSampleSubtitle() {
        assertEquals(0L, previewTimeUs(0f, false))
        assertEquals(1_500_000L, previewTimeUs(1500f, false))
        assertTrue(previewTimeUs(PREVIEW_LOOP_MS.toFloat(), false) > 2_200_000L)
    }
}
