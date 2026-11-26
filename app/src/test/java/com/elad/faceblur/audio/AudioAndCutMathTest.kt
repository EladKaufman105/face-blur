package com.elad.faceblur.audio

import com.elad.faceblur.edit.CutMap
import com.elad.faceblur.edit.SilenceFinder
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure parts: the time map, silences, filler words, noise reduction, loudness, the limiter.
 * (Moved as is from the instrumented LabsTest.audioAndCutMath: none of it needs a device.)
 */
class AudioAndCutMathTest {

    private fun note(s: String) = println(s)

    @Test
    fun audioAndCutMath() {
        // Cut map: 1-2 s and 5-5.5 s taken out.
        val m = CutMap(listOf(longArrayOf(1000, 2000), longArrayOf(5000, 5500)))
        check(m.outUs(500_000) == 500_000L) { "before" }
        check(m.outUs(1_500_000) == -1L) { "inside" }
        check(m.outUs(3_000_000) == 2_000_000L) { "after one" }
        check(m.outUs(6_000_000) == 4_500_000L) { "after two" }
        check(m.mapMs(1500, 3000) == (1000L to 2000L)) { "range ${m.mapMs(1500, 3000)}" }
        check(m.removedUs == 1_500_000L)
        note("cut map ok")

        // Silences: tone bursts with a 1.2 s gap and a 0.3 s gap (16 kHz).
        val rate = 16_000
        val x = FloatArray(rate * 6)
        fun tone(a: Double, b: Double) { for (i in (a * rate).toInt() until (b * rate).toInt()) x[i] = 0.3f * sin(2 * PI * 300 * i / rate).toFloat() }
        tone(0.0, 1.5); tone(2.7, 3.5); tone(3.8, 6.0)
        val rnd = java.util.Random(1)
        for (i in x.indices) x[i] += (rnd.nextGaussian() * 0.002).toFloat()
        val pauses = SilenceFinder.find(x, rate, 700, emptyList())
        note("silences: " + pauses.joinToString { "${it[0]}-${it[1]}" })
        check(pauses.size == 1) { "one long pause expected, got ${pauses.size}" }
        check(pauses[0][0] in 1550L..1750L && pauses[0][1] in 2450L..2650L) { "pause at ${pauses[0][0]}-${pauses[0][1]}" }
        // A word inside the pause is never cut.
        val around = SilenceFinder.find(x, rate, 700, listOf(longArrayOf(2000, 2200)))
        check(around.none { it[0] < 2200 && it[1] > 2000 }) { "cut through a word" }
        check(SilenceFinder.isFiller("אממ,") && SilenceFinder.isFiller("Um") && !SilenceFinder.isFiller("אמא")) { "fillers" }

        // Noise reduction: steady noise drops a lot, the tone stays.
        val r48 = 48_000
        val sig = FloatArray(r48 * 4)
        val noise = FloatArray(sig.size) { (rnd.nextGaussian() * 0.03).toFloat() }
        for (i in sig.indices) {
            val t = i.toDouble() / r48
            val voice = if (t in 2.0..3.0) 0.25f * sin(2 * PI * 440 * t).toFloat() else 0f
            sig[i] = voice + noise[i]
        }
        val d = Denoiser(r48)
        val y = FloatArray(sig.size) { d.process(sig[it]) }
        val lat = d.latency
        fun rms(a: FloatArray, from: Double, to: Double, shift: Int = 0): Double {
            var s = 0.0
            val i0 = (from * r48).toInt() + shift
            val i1 = (to * r48).toInt() + shift
            for (i in i0 until i1) s += a[i].toDouble() * a[i]
            return sqrt(s / (i1 - i0))
        }
        val noiseBefore = rms(sig, 1.2, 1.9)
        val noiseAfter = rms(y, 1.2, 1.9, lat)
        val toneBefore = rms(sig, 2.2, 2.9)
        val toneAfter = rms(y, 2.2, 2.9, lat)
        val dbNoise = 20 * log10(noiseAfter / noiseBefore)
        val dbTone = 20 * log10(toneAfter / toneBefore)
        note("denoise: noise %.1f dB, tone %.1f dB".format(dbNoise, dbTone))
        check(dbNoise < -8) { "noise only %.1f dB lower".format(dbNoise) }
        check(dbTone > -3) { "tone lost %.1f dB".format(dbTone) }

        // Loudness (EBU Tech 3341): a 1 kHz sine peaking at -20 dBFS in both channels is -20 LUFS.
        val s1k = FloatArray(rate * 5) { 0.1f * sin(2 * PI * 1000 * it / rate).toFloat() }
        val lufs = Loudness.measure(s1k, rate, channels = 2)!!
        note("loudness of -20 dBFS 1 kHz: %.2f LUFS".format(lufs))
        check(abs(lufs - (-20f)) < 0.5f) { "loudness $lufs" }

        // The voice chain with a big makeup gain never clips.
        val dsp = VoiceDsp(emptyList(), VoiceFx.NONE, true, denoise = 3, enhance = true, gain = 8f)
        val buf = ByteBuffer.allocateDirect(r48 * 2 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until r48) { val v = 0.5f * sin(2 * PI * 220 * i / r48).toFloat(); buf.putFloat(v); buf.putFloat(v) }
        buf.flip()
        dsp.process(buf, 0L, r48, 2, true)
        var peak = 0f
        for (i in 0 until r48 * 2) peak = maxOf(peak, abs(buf.getFloat(i * 4)))
        note("limited peak %.3f".format(peak))
        check(peak <= 1.0f) { "clipped $peak" }
    }
}
