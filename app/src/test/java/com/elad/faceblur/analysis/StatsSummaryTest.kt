package com.elad.faceblur.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsSummaryTest {
    private fun snap(acc: String = "gpu", noGpu: String? = null) = StatsSnapshot(
        atMs = 0, frames = 640, frameMs = 8_000, waitMs = 2_000, detections = 320, detectMs = 9_600,
        fineUs = 0, coarseUs = 0, gpuCallUs = 0, yunetUs = 0, cropUs = 0, crops = 0,
        accelerator = acc, precision = "fp16", gpuMs = 0f, gpuQueueMs = 0f, noGpu = noGpu,
        side = 1280, every = 2, lite = true, workers = 5, maxWorkers = 5,
    )

    @Test
    fun theLineSaysWhereDetectionRanAndHowItWent() {
        assertEquals("gpu fp16 · every 2, lite · 1280 px · 640 frames · 30 ms a detection · waiting 20% · 5/5 workers", snap().summary())
    }

    @Test
    fun withoutAGpuTheReasonIsThere() {
        assertTrue(snap("cpu", "no GPU models yet").summary().endsWith("no GPU: no GPU models yet"))
    }
}
