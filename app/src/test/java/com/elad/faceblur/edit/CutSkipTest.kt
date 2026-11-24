package com.elad.faceblur.edit

import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutSkipTest {
    private fun cuts(vararg r: Pair<Long, Long>) = TreeMap<Long, Long>().apply { r.forEach { put(it.first, it.second) } }

    @Test
    fun notInACutIsMinusOne() {
        val c = cuts(1000L to 2000L)
        assertEquals(-1L, CutSkip.skipEndMs(c, 999))
        assertEquals(-1L, CutSkip.skipEndMs(c, 2000))
        assertEquals(-1L, CutSkip.skipEndMs(TreeMap(), 500))
    }

    @Test
    fun insideACutIsItsEnd() {
        val c = cuts(1000L to 2000L, 5000L to 6000L)
        assertEquals(2000L, CutSkip.skipEndMs(c, 1000))
        assertEquals(2000L, CutSkip.skipEndMs(c, 1999))
        assertEquals(6000L, CutSkip.skipEndMs(c, 5500))
    }

    @Test
    fun cutsThatTouchOrAreLessThanAFrameApartAreOneJump() {
        val c = cuts(1000L to 2000L, 2000L to 2500L, 2510L to 3000L, 4000L to 4500L)
        assertEquals(3000L, CutSkip.skipEndMs(c, 1500))
        assertEquals(3000L, CutSkip.skipEndMs(c, 2200))
        // A real gap stays a gap.
        assertEquals(4500L, CutSkip.skipEndMs(c, 4100))
        assertEquals(-1L, CutSkip.skipEndMs(c, 3500))
    }

    @Test
    fun aCutToTheEndOfTheVideoHasItsEnd() {
        val c = cuts(9000L to 10_000L)
        assertEquals(10_000L, CutSkip.skipEndMs(c, 9500))
    }

    @Test
    fun framesStillArrivingFromTheCutDoNotStartAnotherSeek() {
        val g = CutSkipGuard(retryMs = 400)
        assertEquals(CutSkipStep.Seek(50), g.onCutFrame(50, 100, 0))
        assertTrue(g.pending)
        // The next frames, before the seek lands, are still in the cut.
        assertEquals(CutSkipStep.None, g.onCutFrame(50, 100, 16))
        assertEquals(CutSkipStep.None, g.onCutFrame(50, 100, 33))
        // The seek lands outside the cut.
        g.cleared()
        assertFalse(g.pending)
    }

    @Test
    fun aSeekThatNeverLandedIsRetried() {
        val g = CutSkipGuard(retryMs = 400)
        assertEquals(CutSkipStep.Seek(50), g.onCutFrame(50, 100, 0))
        assertEquals(CutSkipStep.None, g.onCutFrame(50, 100, 399))
        assertEquals(CutSkipStep.Seek(50), g.onCutFrame(50, 100, 400))
    }

    @Test
    fun aNewDestinationSeeksAtOnce() {
        // The seek landed in the next cut, which ends elsewhere.
        val g = CutSkipGuard()
        assertEquals(CutSkipStep.Seek(50), g.onCutFrame(50, 100, 0))
        assertEquals(CutSkipStep.Seek(70), g.onCutFrame(70, 100, 10))
    }

    @Test
    fun aCutToTheEndStopsAndLeavesNothingPending() {
        val g = CutSkipGuard()
        assertEquals(CutSkipStep.Finish, g.onCutFrame(100, 100, 0))
        assertFalse(g.pending)
        // Playing again from the start into the same end stops again.
        g.cleared()
        assertEquals(CutSkipStep.Finish, g.onCutFrame(100, 100, 5000))
    }

    @Test
    fun cancellingAJumpMeansNothingIsPendingAndALaterCutSeeksAgain() {
        val g = CutSkipGuard()
        assertEquals(CutSkipStep.Seek(50), g.onCutFrame(50, 100, 0))
        g.cleared()
        assertFalse(g.pending)
        assertEquals(CutSkipStep.Seek(50), g.onCutFrame(50, 100, 10))
    }
}
