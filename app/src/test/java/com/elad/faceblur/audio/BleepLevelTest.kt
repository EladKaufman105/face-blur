package com.elad.faceblur.audio

import com.elad.faceblur.gen.BleepConst
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bleep's loudness follows the speech it replaces (the web app's tests/bleep.cjs checks the same
 * vectors and the same cases): a quiet word gets a quiet beep, a loud one a louder beep under the cap,
 * and nothing clips.
 */
class BleepLevelTest {
    private val rate = 16_000

    /** A voiced-like tone (150 Hz and two harmonics) of the given RMS, [seconds] long. */
    private fun speech(rms: Float, seconds: Int = 3): FloatArray {
        val x = FloatArray(rate * seconds) {
            val t = it.toDouble() / rate
            (sin(2 * PI * 150 * t) + 0.5 * sin(2 * PI * 300 * t) + 0.25 * sin(2 * PI * 450 * t)).toFloat()
        }
        val e = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        val k = (rms / e).toFloat()
        for (i in x.indices) x[i] *= k
        return x
    }

    /** [x] through [VoiceDsp] with a bleep at 1.0-1.5 s, in blocks as the player gives them; the peak of what is heard in 1.05-1.45 s. */
    private fun beepPeak(x: FloatArray, levels: FloatArray?, voice: VoiceFx = VoiceFx.NONE, tone: Boolean = true): Float {
        val dsp = VoiceDsp(listOf(longArrayOf(1000, 1500)), voice, tone, bleepLevels = levels)
        val out = FloatArray(x.size)
        val block = 1024
        var at = 0
        while (at < x.size) {
            val n = minOf(block, x.size - at)
            val buf = ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putFloat(x[at + i])
            buf.flip()
            dsp.process(buf, at * 1_000_000L / rate, rate, 1, true)
            for (i in 0 until n) out[at + i] = buf.getFloat(i * 4)
            at += n
        }
        var peak = 0f
        for (i in (1.05 * rate).toInt() until (1.45 * rate).toInt()) peak = maxOf(peak, abs(out[i]))
        // (Only inside the bleep: what is outside it is the input as it came, and a loud synthetic input may pass 1 by itself.)
        for (i in (1.0 * rate).toInt() until (1.5 * rate).toInt()) check(abs(out[i]) <= 1f) { "clipped: ${out[i]}" }
        return peak
    }

    private fun levelsFor(x: FloatArray) = BleepLevel.levels(x, rate, listOf(longArrayOf(1000, 1500)))

    /** shared/vectors/bleep/level.json, from the module's directory (Gradle's) or the repository's. */
    private fun vectors(): JSONObject {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "shared/vectors/bleep/level.json")
            if (f.exists()) return JSONObject(f.readText())
            d = d.parentFile
        }
        error("shared/vectors/bleep/level.json not found")
    }

    @Test
    fun levelMatchesTheSharedVectors() {
        val cases = vectors().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = BleepLevel.level(c.getDouble("rms").toFloat())
            check(abs(got - c.getDouble("level").toFloat()) < 1e-6f) { "level(${c.getDouble("rms")}) = $got, want ${c.getDouble("level")}" }
        }
        check(BleepLevel.level(Float.NaN) == BleepConst.BLEEP_MIN_LEVEL)
    }

    @Test
    fun quietSpeechGivesAQuietBeepAndLoudSpeechALouderOneUnderTheCap() {
        val quiet = beepPeak(speech(0.03f), levelsFor(speech(0.03f)))
        val mid = beepPeak(speech(0.1f), levelsFor(speech(0.1f)))
        val loud = beepPeak(speech(0.25f), levelsFor(speech(0.25f)))
        val huge = beepPeak(speech(0.6f), levelsFor(speech(0.6f)))
        check(quiet > BleepConst.BLEEP_MIN_LEVEL && quiet < 0.06f) { "quiet speech: a beep of $quiet" }
        check(quiet < mid && mid < loud) { "the beep does not follow the speech: $quiet $mid $loud" }
        check(abs(mid - 0.1f) < 0.005f) { "mid $mid" }
        check(loud <= BleepConst.BLEEP_MAX_LEVEL + 1e-4f && huge <= BleepConst.BLEEP_MAX_LEVEL + 1e-4f) { "over the cap: $loud $huge" }
        val hush = beepPeak(speech(0.0002f), levelsFor(speech(0.0002f)))
        check(abs(hush - BleepConst.BLEEP_MIN_LEVEL) < 2e-3f) { "near silence: $hush" }
    }

    @Test
    fun noLevelsMeansTheFallbackAndSilenceMeansSilence() {
        val fallback = beepPeak(speech(0.2f), null)
        check(abs(fallback - BleepConst.BLEEP_FALLBACK_LEVEL) < 2e-3f) { "fallback $fallback" }
        check(beepPeak(speech(0.2f), levelsFor(speech(0.2f)), tone = false) < 1e-6f) { "not silent" }
    }

    @Test
    fun nothingClipsWithFullScaleSpeech() {
        val x = FloatArray(rate * 3) { if (sin(2 * PI * 200 * it / rate) >= 0) 1f else -1f }
        // beepPeak checks every sample; the ring-modulated voices too.
        for (v in listOf(VoiceFx.NONE, VoiceFx.ROBOT, VoiceFx.DISGUISE)) beepPeak(x, levelsFor(x), v)
    }

    @Test
    fun eachBleepHasItsOwnLevel() {
        val x = speech(0.03f)
        for (i in (2 * rate) until (2.5 * rate).toInt()) x[i] *= 8f
        val levels = BleepLevel.levels(x, rate, listOf(longArrayOf(500, 1000), longArrayOf(2000, 2500)))
        check(levels[1] > levels[0] * 3) { "one level for both: ${levels.toList()}" }
    }
}
