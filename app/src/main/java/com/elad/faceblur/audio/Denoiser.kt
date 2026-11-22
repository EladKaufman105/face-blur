package com.elad.faceblur.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Background noise reduction for one channel, streaming (works the same live in the player and
 * in the export): short-time spectra, the noise in each band estimated from its quietest
 * moments of the last ~1.5 s (steady noise: an air conditioner, wind, traffic, hum), and a gain
 * per band that keeps speech and lowers what's only noise ("decision-directed" Wiener gain,
 * which keeps the leftover noise smooth rather than chirpy).
 *
 * The output is the input delayed by one window ([latency] samples), so sound stays in step
 * with the video to within a few milliseconds.
 */
class Denoiser(rate: Int) {
    private val n = if (rate >= 32_000) 1024 else 512
    private val hop = n / 2
    val latency get() = n

    // sqrt-Hann in and out: at 50% overlap the product sums to 1 (perfect reconstruction).
    private val win = FloatArray(n) { sqrt(0.5f - 0.5f * cos(2 * PI * it / n).toFloat()) }
    private val bins = n / 2 + 1

    private val inBuf = FloatArray(n)
    private var inFill = 0
    private val outBuf = FloatArray(n + hop)
    private val ready = FloatArray(hop)
    private var readyPos = hop
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    private val smooth = FloatArray(bins)
    private val minCur = FloatArray(bins) { Float.MAX_VALUE }
    private val minPrev = FloatArray(bins) { Float.MAX_VALUE }
    private val noise = FloatArray(bins)
    private val gainPrev = FloatArray(bins) { 1f }
    private val snrPrev = FloatArray(bins) { 1f }
    private val gain = FloatArray(bins)
    private var frames = 0
    /** Frames per half of the minimum's memory (~0.75 s each). */
    private val span = max(8, (0.75f * rate / hop).toInt())

    fun reset() {
        inBuf.fill(0f); inFill = 0
        outBuf.fill(0f); ready.fill(0f); readyPos = hop
        smooth.fill(0f); minCur.fill(Float.MAX_VALUE); minPrev.fill(Float.MAX_VALUE)
        noise.fill(0f); gainPrev.fill(1f); snrPrev.fill(1f)
        frames = 0
    }

    /** One sample in, one (delayed, cleaned) sample out. */
    fun process(x: Float): Float {
        if (readyPos >= hop) {
            // Not enough output yet: this happens once per hop, after a frame is analysed.
            readyPos = 0
        }
        val y = ready[readyPos]
        inBuf[inFill++] = x
        readyPos++
        if (inFill == n) {
            frame()
            // Keep the second half as the next frame's first half.
            System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
            inFill = n - hop
        }
        return y
    }

    private fun frame() {
        for (i in 0 until n) {
            re[i] = inBuf[i] * win[i]
            im[i] = 0f
        }
        fft(re, im, false)
        frames++
        // Noise: the minimum of the smoothed power over two alternating windows.
        val first = frames == 1
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            smooth[k] = if (first) p else 0.7f * smooth[k] + 0.3f * p
            if (smooth[k] < minCur[k]) minCur[k] = smooth[k]
            val m = min(minCur[k], minPrev[k])
            // The minimum underestimates the mean noise: scale it up.
            noise[k] = if (frames < 6) max(noise[k], smooth[k]) else m * NOISE_BIAS
        }
        if (frames % span == 0) {
            System.arraycopy(minCur, 0, minPrev, 0, bins)
            minCur.fill(Float.MAX_VALUE)
        }
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            val nk = max(noise[k], 1e-12f)
            val post = p / nk
            val prior = ALPHA * gainPrev[k] * gainPrev[k] * snrPrev[k] + (1 - ALPHA) * max(post - 1f, 0f)
            val g = max(prior / (1f + prior), FLOOR)
            gain[k] = g
            snrPrev[k] = post
        }
        // A little smoothing across bands against musical noise.
        for (k in 0 until bins) {
            val a = gain[max(0, k - 1)]
            val b = gain[min(bins - 1, k + 1)]
            val g = 0.25f * a + 0.5f * gain[k] + 0.25f * b
            gainPrev[k] = gain[k]
            re[k] *= g
            im[k] *= g
            if (k in 1 until n - k) {
                re[n - k] *= g
                im[n - k] *= g
            }
        }
        fft(re, im, true)
        // Overlap-add: the first hop samples are finished.
        for (i in 0 until n) outBuf[i] += re[i] * win[i]
        System.arraycopy(outBuf, 0, ready, 0, hop)
        System.arraycopy(outBuf, hop, outBuf, 0, n)
        java.util.Arrays.fill(outBuf, n, n + hop, 0f)
        readyPos = 0
    }

    companion object {
        private const val ALPHA = 0.96f
        /** About -16 dB at most: the voice stays natural, the noise just steps back. */
        private const val FLOOR = 0.16f
        private const val NOISE_BIAS = 2.2f

        /** In-place radix-2 FFT; [inverse] also divides by the size. */
        fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang).toFloat()
                val wi = kotlin.math.sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f
                    var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) {
                val s = 1f / n
                for (i in 0 until n) {
                    re[i] *= s
                    im[i] *= s
                }
            }
        }
    }
}
