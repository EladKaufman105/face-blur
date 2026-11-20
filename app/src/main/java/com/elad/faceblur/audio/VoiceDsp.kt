package com.elad.faceblur.audio

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Voice change for anonymity.
 *
 * @param pitch pitch multiplier (speed unchanged).
 * @param ringHz ring modulation (robotic sound) at this frequency, 0 = none.
 * @param ringMix how much of the ring modulation is mixed in.
 */
enum class VoiceFx(val pitch: Float, val ringHz: Float = 0f, val ringMix: Float = 0f) {
    NONE(1f),
    DEEP(0.72f),
    HIGH(1.45f),
    ROBOT(0.94f, 55f, 1f),
    /** Lower and buzzing: hardest to recognize. */
    DISGUISE(0.8f, 32f, 0.75f),
    /** No sound at all. */
    MUTE(1f),
}

/** A sound effect to mix in: [samples] at [SFX_RATE], from media time [startUs]. */
class SfxPlay(val startUs: Long, val samples: FloatArray, val gain: Float) {
    val endUs = startUs + samples.size * 1_000_000L / SFX_RATE
}

const val SFX_RATE = 48_000

/**
 * Bleeps, ring modulation, muting and sound effects on PCM, in place, by media time. Pitch is
 * done elsewhere (Sonic).
 *
 * @param bleeps sorted stretches (ms) to replace with a beep or silence.
 * @param sfx sound effects mixed on top (not bleeped, not changed by the voice).
 */
class VoiceDsp(
    bleeps: List<LongArray>,
    private val fx: VoiceFx,
    private val tone: Boolean,
    /** The pitch change applied after this (the player's), so the beep still sounds at 1 kHz. */
    pitchAfter: Float = 1f,
    private val sfx: List<SfxPlay> = emptyList(),
) {
    private val mute = fx == VoiceFx.MUTE
    private val beepHz = BEEP_HZ / pitchAfter
    private val ringHz = fx.ringHz / pitchAfter

    private val starts = LongArray(bleeps.size) { bleeps[it][0] * 1000 }
    private val ends = LongArray(bleeps.size) { bleeps[it][1] * 1000 }

    val active get() = starts.isNotEmpty() || fx.ringMix > 0f || mute || sfx.isNotEmpty()

    /** Sound effects playing at [us], summed. */
    private fun sfxAt(us: Long, playing: List<SfxPlay>): Float {
        var s = 0f
        for (p in playing) {
            if (us < p.startUs || us >= p.endUs) continue
            val pos = (us - p.startUs) * SFX_RATE / 1e6
            val i = pos.toInt()
            if (i >= p.samples.size - 1) continue
            val f = (pos - i).toFloat()
            s += (p.samples[i] + (p.samples[i + 1] - p.samples[i]) * f) * p.gain
        }
        return s
    }

    /** Gain of the original sound at [us]: 0 inside a bleep, short fades at its edges. */
    private fun keep(us: Long, from: Int): Float {
        var g = 1f
        var i = from
        while (i < starts.size && starts[i] - FADE_US <= us) {
            if (us < ends[i] + FADE_US) {
                val d = when {
                    us < starts[i] -> (starts[i] - us).toFloat() / FADE_US
                    us > ends[i] -> (us - ends[i]).toFloat() / FADE_US
                    else -> 0f
                }
                g = min(g, d.coerceIn(0f, 1f))
            }
            i++
        }
        return g
    }

    /** First bleep that may reach [us]. */
    private fun firstFrom(us: Long): Int {
        var lo = 0
        var hi = ends.size
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (ends[m] + FADE_US < us) lo = m + 1 else hi = m
        }
        return lo
    }

    /**
     * Processes the frames between [buf]'s position and limit (position unchanged), first frame
     * at media time [startUs]. 16-bit or float PCM, interleaved.
     */
    fun process(buf: ByteBuffer, startUs: Long, rate: Int, channels: Int, float: Boolean) {
        if (!active || rate <= 0 || channels <= 0) return
        val bytes = if (float) 4 else 2
        val frameBytes = bytes * channels
        val frames = (buf.limit() - buf.position()) / frameBytes
        if (frames <= 0) return
        val endUs = startUs + frames * 1_000_000L / rate
        var bi = firstFrom(startUs)
        val anyBleep = bi < starts.size && starts[bi] - FADE_US < endUs
        val playing = sfx.filter { it.startUs < endUs && it.endUs > startUs }
        if (!anyBleep && fx.ringMix <= 0f && !mute && playing.isEmpty()) return
        val voice = if (mute) 0f else 1f
        val base = buf.position()
        for (f in 0 until frames) {
            val us = startUs + f * 1_000_000L / rate
            val t = us / 1e6
            var g = 1f
            if (anyBleep) {
                while (bi < ends.size && ends[bi] + FADE_US < us) bi++
                g = keep(us, bi)
            }
            val ring = if (fx.ringMix > 0f) (1f - fx.ringMix) + fx.ringMix * sin(2 * PI * ringHz * t).toFloat() else 1f
            val beep = if (tone && g < 1f) (1f - g) * BEEP_LEVEL * sin(2 * PI * beepHz * t).toFloat() else 0f
            val add = beep + if (playing.isEmpty()) 0f else sfxAt(us, playing)
            for (c in 0 until channels) {
                val at = base + f * frameBytes + c * bytes
                val x = if (float) buf.getFloat(at) else buf.getShort(at) / 32768f
                val y = (x * ring * g * voice + add).coerceIn(-1f, 1f)
                if (float) buf.putFloat(at, y) else buf.putShort(at, (y * 32767f).toInt().toShort())
            }
        }
    }

    companion object {
        private const val FADE_US = 8_000L
        private const val BEEP_HZ = 1000.0
        private const val BEEP_LEVEL = 0.32f
    }
}
