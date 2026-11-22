package com.elad.faceblur.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Loudness as streaming sites measure it (ITU-R BS.1770: K-weighting, 400 ms blocks, gated),
 * and the gain that brings a video to their level.
 */
object Loudness {
    /** What TikTok, Instagram and YouTube aim for (LUFS). */
    const val TARGET_LUFS = -14f

    /**
     * Integrated loudness (LUFS) of [x] (mono at [rate]). A stereo source mixed down to mono
     * measures ~3 dB lower than the stereo original: pass [channels] to make up for it.
     */
    fun measure(x: FloatArray, rate: Int, channels: Int = 1): Float? {
        if (x.size < rate / 2) return null
        val k = kWeight(x, rate)
        val block = (0.4 * rate).toInt()
        val step = (0.1 * rate).toInt()
        val powers = ArrayList<Double>()
        var i = 0
        while (i + block <= k.size) {
            var s = 0.0
            for (j in i until i + block) s += k[j].toDouble() * k[j]
            powers += s / block
            i += step
        }
        if (powers.isEmpty()) return null
        val boost = if (channels >= 2) 2.0 else 1.0
        fun lufs(p: Double) = -0.691 + 10 * log10(max(p * boost, 1e-12))
        // Absolute gate at -70 LUFS, then relative gate 10 LU under the mean.
        val abs = powers.filter { lufs(it) > -70 }
        if (abs.isEmpty()) return null
        val rel = lufs(abs.average()) - 10
        val kept = abs.filter { lufs(it) > rel }
        if (kept.isEmpty()) return null
        return lufs(kept.average()).toFloat()
    }

    /** Gain (dB) from [lufs] to the target, kept reasonable. */
    fun gainDb(lufs: Float): Float = (TARGET_LUFS - lufs).coerceIn(-12f, 20f)

    /** The K-weighting: a high shelf (+4 dB above ~1.7 kHz) and a high-pass at ~38 Hz. */
    private fun kWeight(x: FloatArray, rate: Int): FloatArray {
        val shelf = highShelf(rate, 1681.974450955533, 3.999843853973347, 0.7071752369554196)
        val hp = highPass(rate, 38.13547087602444, 0.5003270373238773)
        return biquad(biquad(x, shelf), hp)
    }

    private fun biquad(x: FloatArray, c: DoubleArray): FloatArray {
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a1 = c[3]; val a2 = c[4]
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = x[i].toDouble()
            val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = o
            y[i] = o.toFloat()
        }
        return y
    }

    private fun highShelf(rate: Int, f0: Double, gainDb: Double, q: Double): DoubleArray {
        val k = tan(PI * f0 / rate)
        val vh = 10.0.pow(gainDb / 20)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1 + k / q + k * k
        return doubleArrayOf(
            (vh + vb * k / q + k * k) / a0,
            2 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2 * (k * k - 1) / a0,
            (1 - k / q + k * k) / a0,
        )
    }

    private fun highPass(rate: Int, f0: Double, q: Double): DoubleArray {
        val k = tan(PI * f0 / rate)
        val a0 = 1 + k / q + k * k
        return doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }
}

/**
 * Peak limiter for the louder sound: fast attack, smooth release, a ceiling just under full
 * scale. Linked across channels (one gain per frame).
 */
class Limiter(rate: Int, private val ceiling: Float = 0.94f) {
    private val attack = 1f - kotlin.math.exp(-1f / (0.0015f * rate))
    private val release = 1f - kotlin.math.exp(-1f / (0.12f * rate))
    private var g = 1f

    fun reset() { g = 1f }

    /** Gain for a frame whose loudest channel is [peak] (after the makeup gain). */
    fun gainFor(peak: Float): Float {
        val want = if (peak > ceiling) ceiling / peak else 1f
        g += (want - g) * (if (want < g) attack else release)
        return g
    }

    companion object {
        /** Soft ceiling for what the limiter's attack lets through. */
        fun soft(x: Float): Float {
            val a = kotlin.math.abs(x)
            if (a <= 0.9f) return x
            val over = a - 0.9f
            val y = 0.9f + 0.1f * (over / sqrt(over * over + 0.01f)) // never past 1.0
            return if (x < 0) -y else y
        }
    }
}
