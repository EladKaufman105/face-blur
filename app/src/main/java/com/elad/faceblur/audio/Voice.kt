package com.elad.faceblur.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * The sound's edits.
 *
 * @param tone bleeped words get a beep (else silence).
 * @param maskWords bleeped words are also hidden in the subtitles (****).
 */
data class AudioEdit(val voice: VoiceFx = VoiceFx.NONE, val tone: Boolean = true, val maskWords: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("voice", voice.name).put("tone", tone).put("mask", maskWords)

    companion object {
        fun fromJson(o: JSONObject?): AudioEdit {
            if (o == null) return AudioEdit()
            return AudioEdit(
                runCatching { VoiceFx.valueOf(o.optString("voice")) }.getOrDefault(VoiceFx.NONE),
                o.optBoolean("tone", true),
                o.optBoolean("mask", true),
            )
        }
    }
}

/** [VoiceDsp] in the export: time counted from the first sample. */
class VoiceAudioProcessor(private val dsp: VoiceDsp) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!dsp.active) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val fmt = inputAudioFormat
        val out = replaceOutputBuffer(n)
        out.put(inputBuffer)
        out.flip()
        dsp.process(out, frames * 1_000_000L / fmt.sampleRate, fmt.sampleRate, fmt.channelCount, fmt.encoding == C.ENCODING_PCM_FLOAT)
        frames += n / fmt.bytesPerFrame
    }

    override fun onFlush() {
        frames = 0
    }

    override fun onReset() {
        frames = 0
    }
}

/**
 * The editor's player with the voice edits heard live: bleeps and ring modulation are applied
 * to the decoded sound by its media time (pitch is set on the player).
 */
class VoicePreviewRenderers(context: Context, private val dsp: () -> VoiceDsp?) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return object : ForwardingAudioSink(sink) {
            private var rate = 0
            private var channels = 0
            private var encoding = C.ENCODING_INVALID
            private var offsetUs = 0L
            private var lastIn: ByteBuffer? = null
            private var lastPts = Long.MIN_VALUE
            private var copy: ByteBuffer? = null
            private var pending: ByteBuffer? = null

            override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                rate = inputFormat.sampleRate
                channels = inputFormat.channelCount
                encoding = inputFormat.pcmEncoding
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
            }

            override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                offsetUs = outputStreamOffsetUs
                super.setOutputStreamOffsetUs(outputStreamOffsetUs)
            }

            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                // The sink may take a buffer in several calls, and wants the same object each
                // time: whether it's processed (a copy) or passed on is decided once per buffer.
                if (buffer !== lastIn || presentationTimeUs != lastPts) {
                    lastIn = buffer
                    lastPts = presentationTimeUs
                    val d = dsp()
                    val pcm = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
                    pending = if (d == null || !d.active || !pcm) {
                        buffer
                    } else {
                        val n = buffer.remaining()
                        var c = copy
                        if (c == null || c.capacity() < n) c = ByteBuffer.allocateDirect(max(n, 1 shl 14)).order(ByteOrder.nativeOrder())
                        c!!.clear()
                        c.put(buffer.duplicate())
                        c.flip()
                        d.process(c, presentationTimeUs - offsetUs, rate, channels, encoding == C.ENCODING_PCM_FLOAT)
                        copy = c
                        c
                    }
                }
                val p = pending ?: buffer
                val done = super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
                if (done) {
                    // The source counts as consumed too.
                    if (p !== buffer) buffer.position(buffer.limit())
                    lastIn = null
                    pending = null
                }
                return done
            }

            override fun flush() {
                lastIn = null
                pending = null
                super.flush()
            }
        }
    }
}
