package com.elad.faceblur.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Enhance voice", for one channel: rumble below the voice cut, the boxy low-mids eased, the
 * presence that makes words clear lifted, a little air on top. (The even level comes from
 * [Compressor], shared by the channels.)
 */
class VoiceEnhancer(rate: Int) {
    private val filters = arrayOf(
        Biquad.highPass(rate, 90.0, 0.707),
        Biquad.peak(rate, 300.0, 1.0, -2.5),
        Biquad.peak(rate, 3200.0, 0.9, 3.5),
        Biquad.highShelf(rate, 9000.0, 2.0),
    )

    fun reset() = filters.forEach { it.reset() }

    fun process(x: Float): Float {
        var y = x.toDouble()
        for (f in filters) y = f.process(y)
        return y.toFloat()
    }
}

/** A biquad filter (RBJ's cookbook), direct form I. */
class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun highPass(rate: Int, f: Double, q: Double): Biquad {
            val w = 2 * PI * f / rate
            val c = cos(w)
            val al = sin(w) / (2 * q)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun peak(rate: Int, f: Double, q: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40)
            val w = 2 * PI * f / rate
            val c = cos(w)
            val al = sin(w) / (2 * q)
            return make(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }

        fun highShelf(rate: Int, f: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40)
            val w = 2 * PI * f.coerceAtMost(rate * 0.45) / rate
            val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0)
            val sa = 2 * sqrt(a) * al
            return make(
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa,
            )
        }
    }
}

/**
 * Evens out the voice's level (quiet words up, loud ones down): 3:1 above -22 dBFS, linked
 * across channels, with makeup gain so speech ends up about as loud as before at its peaks.
 */
class Compressor(rate: Int) {
    private val attack = 1f - exp(-1f / (0.005f * rate))
    private val release = 1f - exp(-1f / (0.15f * rate))
    private var env = 0f

    fun reset() { env = 0f }

    /** Gain for a frame whose loudest channel is [peak]. */
    fun gainFor(peak: Float): Float {
        env += (peak - env) * (if (peak > env) attack else release)
        val db = 20 * log10(maxOf(env, 1e-6f))
        val over = db - THRESHOLD_DB
        val reduce = if (over > 0) over * (1 - 1 / RATIO) else 0f
        return 10f.pow((MAKEUP_DB - reduce) / 20f)
    }

    companion object {
        private const val THRESHOLD_DB = -22f
        private const val RATIO = 3f
        private const val MAKEUP_DB = 4f
    }
}
