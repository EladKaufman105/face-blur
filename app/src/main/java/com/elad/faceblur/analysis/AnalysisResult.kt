package com.elad.faceblur.analysis

import java.io.Closeable
import java.util.TreeMap

/**
 * Output of the analysis pass, in analysis frame indices (presentation order).
 *
 * @param times presentation time of each frame (µs, the pipeline's timeline).
 * @param cuts first frame of each new shot (hard cuts).
 * @param motion camera motion per frame: (dx / W, dy / H) from the previous frame.
 * @param dets raw detections on the frames the detector ran on (normalized upright frame).
 * @param tracks the automatic face tracks (normalized upright frame).
 * @param frames small grayscale copies of every frame, for tracking by hand (may be null).
 */
class AnalysisResult(
    val times: LongArray,
    val cuts: IntArray,
    val motion: FloatArray,
    val dets: Map<Int, List<Det>>,
    val tracks: List<TreeMap<Int, Box>>,
    val uprightW: Int,
    val uprightH: Int,
    val rotation: Int,
    val fps: Float,
    val frames: FrameStore?,
) : Closeable {
    val frameCount get() = times.size
    val aspect get() = if (uprightW > 0) uprightH.toFloat() / uprightW else 1f

    override fun close() {
        frames?.close()
    }
}
