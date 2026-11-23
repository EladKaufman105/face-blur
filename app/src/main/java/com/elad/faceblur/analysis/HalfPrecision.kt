package com.elad.faceblur.analysis

import java.util.concurrent.atomic.AtomicInteger

/**
 * Face detection on OpenCV's fp16 CPU kernels (ARMv8.2 and newer; elsewhere OpenCV quietly stays
 * in fp32): about a third faster. On the test videos (1,031 faces, on an ARM server) it found
 * exactly the faces fp32 did, scores within 0.02. Still, on each phone the first frames with
 * faces are run both ways and compared ([RetinaFaceDetector]); two frames that disagree and the
 * rest of the pass is fp32. Shared by the detection workers.
 */
class HalfPrecision {
    private val agreed = AtomicInteger()
    private val strikes = AtomicInteger()
    @Volatile private var off = false

    /** Still in use (not turned off by a disagreement). */
    val active get() = !off

    /** Enough frames agreed with fp32 to use fp16 alone. */
    val trusted get() = !off && agreed.get() >= CHECK_FRAMES

    /** A frame with faces where fp16 and fp32 agreed. */
    fun vouch() {
        agreed.incrementAndGet()
    }

    /** fp32 for the rest of this pass. */
    fun disable() {
        off = true
    }

    /** A frame where fp16 and fp32 differed; the second turns fp16 off. */
    fun strike() {
        if (strikes.incrementAndGet() >= GpuFaceNet.MAX_STRIKES) off = true
    }

    /** For the self-test's report: "fp16", "fp16-checking" or "fp32". */
    val state get() = when {
        off -> "fp32"
        trusted -> "fp16"
        else -> "fp16-checking"
    }

    companion object {
        /** Frames with faces checked against fp32 before fp16 is used alone. */
        const val CHECK_FRAMES = 3
    }
}
