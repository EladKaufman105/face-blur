package com.elad.faceblur.audio

import com.elad.faceblur.gen.BleepConst
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.max
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
    /** Background noise lowered (see [Denoiser]): 0 off, 1 light, 2 medium, 3 strong. */
    private val denoise: Int = 0,
    /** "Enhance voice": clarity EQ and an even level (see [VoiceEnhancer], [Compressor]). */
    private val enhance: Boolean = false,
    /** Makeup gain for social-media loudness (linear, 1 = none), with a limiter. */
    private val gain: Float = 1f,
    /** Stretches cut out of the video (ms): silent in the player, which jumps over them. */
    cuts: List<LongArray> = emptyList(),
    /**
     * The beep's peak level for each of [bleeps] (see [BleepLevel.levels]): as loud as the speech it
     * replaces. null (not measured yet) or the wrong size: [BleepConst.BLEEP_FALLBACK_LEVEL] for all.
     */
    bleepLevels: FloatArray? = null,
) {
    private val mute = fx == VoiceFx.MUTE
    private val beepHz = BEEP_HZ / pitchAfter
    private val ringHz = fx.ringHz / pitchAfter

    private val starts = LongArray(bleeps.size) { bleeps[it][0] * 1000 }
    private val ends = LongArray(bleeps.size) { bleeps[it][1] * 1000 }
    private val levels = FloatArray(bleeps.size) { bleepLevels?.takeIf { l -> l.size == bleeps.size }?.get(it) ?: BleepConst.BLEEP_FALLBACK_LEVEL }
    private val cutStarts = LongArray(cuts.size) { cuts[it][0] * 1000 }
    private val cutEnds = LongArray(cuts.size) { cuts[it][1] * 1000 }

    private val cleanup get() = denoise > 0 || enhance || gain != 1f
    val active get() = starts.isNotEmpty() || fx.ringMix > 0f || mute || sfx.isNotEmpty() || cleanup || cutStarts.isNotEmpty()

    // Cleanup state: one denoiser per channel, a linked limiter; reset when the time jumps.
    private var den: Array<Denoiser>? = null
    private var eq: Array<VoiceEnhancer> = emptyArray()
    private var comp: Compressor? = null
    private var limiter: Limiter? = null
    private var stateRate = 0
    private var stateCh = 0
    private var expectUs = -1L

    /** Forgets the sound so far (a seek, a new export). */
    fun reset() {
        den?.forEach { it.reset() }
        eq.forEach { it.reset() }
        comp?.reset()
        limiter?.reset()
        expectUs = -1L
    }

    private fun clean(buf: ByteBuffer, startUs: Long, endUs: Long, frames: Int, rate: Int, channels: Int, float: Boolean) {
        val ch = minOf(channels, 8)
        if (den == null || stateCh != ch || stateRate != rate) {
            den = if (denoise > 0) Array(ch) { Denoiser(rate, denoise) } else emptyArray()
            eq = if (enhance) Array(ch) { VoiceEnhancer(rate) } else emptyArray()
            comp = if (enhance) Compressor(rate) else null
            limiter = Limiter(rate)
            stateRate = rate
            stateCh = ch
            expectUs = -1L
        }
        if (expectUs >= 0 && kotlin.math.abs(startUs - expectUs) > 40_000) reset()
        expectUs = endUs
        val d = den!!
        val e = eq
        val cmp = comp
        val lim = limiter!!
        val limit = gain > 1f || cmp != null
        val bytes = if (float) 4 else 2
        val frameBytes = bytes * channels
        val base = buf.position()
        val v = FloatArray(ch)
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) {
                val at = base + f * frameBytes + c * bytes
                var x = if (float) buf.getFloat(at) else buf.getShort(at) / 32768f
                if (d.isNotEmpty()) x = d[c].process(x)
                if (e.isNotEmpty()) x = e[c].process(x)
                v[c] = x
                peak = kotlin.math.max(peak, kotlin.math.abs(x))
            }
            // Even level first (enhance), then the loudness gain, then the ceiling.
            val cg = (cmp?.gainFor(peak) ?: 1f) * gain
            peak *= cg
            for (c in 0 until ch) v[c] *= cg
            val g = if (limit) lim.gainFor(peak) else 1f
            for (c in 0 until ch) {
                val at = base + f * frameBytes + c * bytes
                val y = Limiter.soft(v[c] * g).coerceIn(-1f, 1f)
                if (float) buf.putFloat(at, y) else buf.putShort(at, (y * 32767f).toInt().toShort())
            }
        }
    }

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
    private fun keep(us: Long, from: Int, starts: LongArray = this.starts, ends: LongArray = this.ends): Float {
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

    /** The beep's level at [us]: that of the bleep that keeps least of the original sound there (the louder of two that overlap). */
    private fun levelAt(us: Long, from: Int): Float {
        var g = 1f
        var level = BleepConst.BLEEP_FALLBACK_LEVEL
        var i = from
        while (i < starts.size && starts[i] - FADE_US <= us) {
            if (us < ends[i] + FADE_US) {
                val d = when {
                    us < starts[i] -> (starts[i] - us).toFloat() / FADE_US
                    us > ends[i] -> (us - ends[i]).toFloat() / FADE_US
                    else -> 0f
                }.coerceIn(0f, 1f)
                if (d < g) { g = d; level = levels[i] } else if (d == g) level = max(level, levels[i])
            }
            i++
        }
        return level
    }

    /** First bleep that may reach [us]. */
    private fun firstFrom(us: Long, ends: LongArray = this.ends): Int {
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
        if (cleanup) clean(buf, startUs, endUs, frames, rate, channels, float)
        var bi = firstFrom(startUs)
        val anyBleep = bi < starts.size && starts[bi] - FADE_US < endUs
        var ci = firstFrom(startUs, cutEnds)
        val anyCut = ci < cutStarts.size && cutStarts[ci] - FADE_US < endUs
        val playing = sfx.filter { it.startUs < endUs && it.endUs > startUs }
        if (!anyBleep && !anyCut && fx.ringMix <= 0f && !mute && playing.isEmpty()) return
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
            val beep = if (tone && g < 1f) (1f - g) * levelAt(us, bi) * sin(2 * PI * beepHz * t).toFloat() else 0f
            val add = beep + if (playing.isEmpty()) 0f else sfxAt(us, playing)
            var cg = 1f
            if (anyCut) {
                while (ci < cutEnds.size && cutEnds[ci] + FADE_US < us) ci++
                cg = keep(us, ci, cutStarts, cutEnds)
            }
            for (c in 0 until channels) {
                val at = base + f * frameBytes + c * bytes
                val x = if (float) buf.getFloat(at) else buf.getShort(at) / 32768f
                val y = ((x * ring * g * voice + add) * cg).coerceIn(-1f, 1f)
                if (float) buf.putFloat(at, y) else buf.putShort(at, (y * 32767f).toInt().toShort())
            }
        }
    }

    companion object {
        private const val FADE_US = 8_000L
        private const val BEEP_HZ = 1000.0
    }
}
