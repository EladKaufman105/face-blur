package com.elad.faceblur.audio

import com.elad.faceblur.gen.BleepConst
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How loud each bleep's beep is: as loud as the speech it replaces (RMS over the bleep and a short
 * window either side, times [BleepConst.BLEEP_SPEECH_GAIN], kept between [BleepConst.BLEEP_MIN_LEVEL]
 * and [BleepConst.BLEEP_MAX_LEVEL]), so a beep never jumps out of a quiet video. The web app's
 * `lib/voice.ts` (`bleepLevel`, `speechRms`) is the same formula; shared/vectors/bleep/level.json checks both.
 */
object BleepLevel {
    /** The beep's peak for speech of RMS [rms]. */
    fun level(rms: Float): Float {
        val v = rms * BleepConst.BLEEP_SPEECH_GAIN
        return if (v.isNaN() || v.isInfinite()) BleepConst.BLEEP_MIN_LEVEL
        else min(BleepConst.BLEEP_MAX_LEVEL, max(BleepConst.BLEEP_MIN_LEVEL, v))
    }

    /** The RMS of [x] (mono at [rate]) over [startMs]..[endMs] and the window either side (0 for no samples). */
    fun speechRms(x: FloatArray, rate: Int, startMs: Long, endMs: Long): Float {
        val w = BleepConst.BLEEP_WINDOW_S.toDouble()
        val i0 = max(0, floor((startMs / 1000.0 - w) * rate).toInt())
        val i1 = min(x.size, ceil((endMs / 1000.0 + w) * rate).toInt())
        if (i1 <= i0) return 0f
        var e = 0.0
        for (i in i0 until i1) e += x[i].toDouble() * x[i]
        return sqrt(e / (i1 - i0)).toFloat()
    }

    /**
     * The level of each of [bleeps] (start and end in ms) from [x], the speech as it came, scaled by
     * [gain] (the makeup gain the sound gets before the bleep, 1 for none).
     */
    fun levels(x: FloatArray, rate: Int, bleeps: List<LongArray>, gain: Float = 1f): FloatArray =
        FloatArray(bleeps.size) { level(speechRms(x, rate, bleeps[it][0], bleeps[it][1]) * gain) }
}
