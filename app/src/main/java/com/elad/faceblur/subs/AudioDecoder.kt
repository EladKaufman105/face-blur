package com.elad.faceblur.subs

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Decodes a video's audio track to 16 kHz mono float PCM, as Whisper wants it. */
object AudioDecoder {
    const val RATE = 16_000

    /** @return samples at [outRate], or null if the video has no audio. */
    fun decode(context: Context, uri: Uri, isActive: () -> Boolean, outRate: Int = RATE, onProgress: (Float) -> Unit): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val mono = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!isActive()) return null
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get()
                                    mono.add(s / channels)
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val frames = sb.remaining() / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get()
                                    mono.add(s / channels / 32768f)
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return resample(mono.toArray(), rate, outRate)
        } finally {
            ex.release()
        }
    }

    /** Windowed-sinc low-pass (anti-aliasing) and linear resampling. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val src = if (to < from) lowPass(x, 0.45f * to / from) else x
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * step
            val k = floor(p).toInt()
            val f = (p - k).toFloat()
            val a = src[min(k, src.size - 1)]
            val b = src[min(k + 1, src.size - 1)]
            out[i] = a + (b - a) * f
        }
        return out
    }

    /** @param cutoff fraction of the sample rate. */
    private fun lowPass(x: FloatArray, cutoff: Float): FloatArray {
        val taps = 31
        val h = FloatArray(taps)
        val m = (taps - 1) / 2
        var sum = 0f
        for (i in 0 until taps) {
            val t = i - m
            val sinc = if (t == 0) 2 * cutoff else (sin(2 * PI * cutoff * t) / (PI * t)).toFloat()
            val w = (0.54 - 0.46 * cos(2 * PI * i / (taps - 1))).toFloat()
            h[i] = sinc * w
            sum += h[i]
        }
        for (i in 0 until taps) h[i] /= sum
        val y = FloatArray(x.size)
        for (i in x.indices) {
            var acc = 0f
            val lo = max(0, i - m)
            val hi = min(x.size - 1, i + m)
            for (j in lo..hi) acc += x[j] * h[j - i + m]
            y[i] = acc
        }
        return y
    }

    private class FloatList {
        private var a = FloatArray(1 shl 16)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }
}
