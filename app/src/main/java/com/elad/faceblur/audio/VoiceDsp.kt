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

/**
 * Bleeps and ring modulation on PCM, in place, by media time. Pitch is done elsewhere (Sonic).
 *
 * @param bleeps sorted stretches (ms) to replace with a beep or silence.
 */
class VoiceDsp(
    bleeps: List<LongArray>,
    private val fx: VoiceFx,
    private val tone: Boolean,
    /** The pitch change applied after this (the player's), so the beep still sounds at 1 kHz. */
    pitchAfter: Float = 1f,
) {
    private val beepHz = BEEP_HZ / pitchAfter
    private val ringHz = fx.ringHz / pitchAfter

    private val starts = LongArray(bleeps.size) { bleeps[it][0] * 1000 }
    private val ends = LongArray(bleeps.size) { bleeps[it][1] * 1000 }

    val active get() = starts.isNotEmpty() || fx.ringMix > 0f

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
        if (!anyBleep && fx.ringMix <= 0f) return
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
            for (c in 0 until channels) {
                val at = base + f * frameBytes + c * bytes
                val x = if (float) buf.getFloat(at) else buf.getShort(at) / 32768f
                val y = (x * ring * g + beep).coerceIn(-1f, 1f)
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
