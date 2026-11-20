package com.elad.faceblur.subs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Kaldi-style log mel filterbank features (as WeSpeaker computes them): 16 kHz, 25 ms frames
 * every 10 ms, Hamming window, pre-emphasis 0.97, DC removed, 512-point FFT, [BINS] mel bins
 * from 20 Hz to 8 kHz, no dither, frames only where they fit whole ("snip edges").
 */
object Fbank {
    const val BINS = 80
    private const val RATE = 16_000
    private const val LEN = 400
    const val HOP = 160
    private const val N = 512
    private const val EPS = 1.1920929e-7f

    private val window = FloatArray(LEN) { (0.54 - 0.46 * cos(2 * PI * it / (LEN - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(-2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(-2 * PI * it / N).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Mel filter weights: for bin m, the first FFT bin it covers and the weights from there. */
    private val melStart = IntArray(BINS)
    private val melW = arrayOfNulls<FloatArray>(BINS)

    init {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val lo = mel(20.0)
        val hi = mel(RATE / 2.0)
        val delta = (hi - lo) / (BINS + 1)
        val binHz = RATE.toDouble() / N
        for (m in 0 until BINS) {
            val left = lo + m * delta
            val center = left + delta
            val right = center + delta
            var first = -1
            val w = ArrayList<Float>()
            for (k in 0 until N / 2) {
                val mk = mel(binHz * k)
                if (mk > left && mk < right) {
                    if (first < 0) first = k
                    w += (if (mk <= center) (mk - left) / (center - left) else (right - mk) / (right - center)).toFloat()
                } else if (first >= 0) {
                    break
                }
            }
            melStart[m] = max(first, 0)
            melW[m] = w.toFloatArray()
        }
    }

    /** Number of frames for [n] samples. */
    fun frames(n: Int) = if (n < LEN) 0 else 1 + (n - LEN) / HOP

    /**
     * Features of samples [from, to) of [pcm] (-1..1): frames x [BINS], row by row.
     */
    fun compute(pcm: FloatArray, from: Int = 0, to: Int = pcm.size): FloatArray {
        val n = frames(to - from)
        val out = FloatArray(n * BINS)
        val re = FloatArray(N)
        val im = FloatArray(N)
        val frame = FloatArray(LEN)
        val power = FloatArray(N / 2 + 1)
        for (f in 0 until n) {
            val at = from + f * HOP
            var mean = 0.0
            for (i in 0 until LEN) {
                frame[i] = pcm[at + i] * 32768f
                mean += frame[i]
            }
            val m = (mean / LEN).toFloat()
            for (i in 0 until LEN) frame[i] -= m
            for (i in LEN - 1 downTo 1) frame[i] -= 0.97f * frame[i - 1]
            frame[0] -= 0.97f * frame[0]
            for (i in 0 until N) {
                val j = rev[i]
                re[i] = if (j < LEN) frame[j] * window[j] else 0f
                im[i] = 0f
            }
            fft(re, im)
            for (k in 0..N / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until BINS) {
                val w = melW[b]!!
                val s = melStart[b]
                var e = 0f
                for (i in w.indices) e += w[i] * power[s + i]
                out[f * BINS + b] = ln(max(e, EPS))
            }
        }
        return out
    }

    /** In-place radix-2 FFT of size [N]; input already in bit-reversed order. */
    private fun fft(re: FloatArray, im: FloatArray) {
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var i = 0
            while (i < N) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
