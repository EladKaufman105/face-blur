package com.elad.faceblur.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The export's stats panel numbers (the web app's tests/exportstats.cjs checks the same). */
class ExportStatsTest {
    private val facts = ExportFacts(
        srcWidth = 1920, srcHeight = 1080, width = 1080, height = 1920, mime = "video/avc", encoder = "c2.test.avc.encoder", hardware = true,
        bitrate = 8_000_000, fps = 30f, outFps = 30f, totalFrames = 300, audio = listOf(AudioStep.COPY),
    )

    private fun snap(atMs: Long, frames: Int, blurMs: Double, f: ExportFacts? = facts) = ExportSnapshot(atMs, frames, blurMs, f)

    @Test
    fun nothingUntilTheFactsAreKnown() {
        assertNull(ExportHistory().add(snap(0, 0, 0.0, null)))
    }

    @Test
    fun framesASecondAndBlurPerFrameAreTheCurrentOnes() {
        val h = ExportHistory()
        var last: ExportStats? = null
        // 30 frames a second at 4 ms of blur each, for 3 s (a sample every 250 ms).
        for (i in 0..12) last = h.add(snap(1000L + i * 250, (i * 7.5).toInt(), i * 7.5 * 4))
        val s = last!!
        assertEquals(30f, s.fps, 1.5f)
        assertEquals(4f, s.blurMs, 0.3f)
        assertEquals(30f, s.fpsAvg, 1.5f)
        assert(s.fpsHistory.values.size in 2..3)
    }

    @Test
    fun anExportThatStartsOverCountsFromZero() {
        val h = ExportHistory()
        h.add(snap(0, 0, 0.0))
        h.add(snap(1000, 100, 400.0))
        // The HDR fallback: the counters were reset.
        val s = h.add(snap(1500, 10, 40.0))!!
        assertNotNull(s)
        assertEquals(4f, s.blurMs, 0.3f)
    }

    @Test
    fun theHistoryKeepsTheLastSixty() {
        val h = ExportHistory()
        var s: ExportStats? = null
        for (i in 0..200) s = h.add(snap(i * 1000L, i * 30, i * 30.0))
        assertEquals(60, s!!.fpsHistory.values.size)
    }
}
