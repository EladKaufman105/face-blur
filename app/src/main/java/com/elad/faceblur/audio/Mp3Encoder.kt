package com.elad.faceblur.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.elad.faceblur.i18n.t
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/** LAME (native, LGPL) turned to Kotlin: 16-bit PCM in, MP3 bytes out. */
class Mp3Encoder(rate: Int, private val channels: Int, kbps: Int) : AutoCloseable {
    private var h = nativeInit(rate, channels, kbps)
    init {
        check(h != 0L) { "MP3 encoder: unsupported format ($rate Hz, $channels ch)" }
    }

    /** Encodes [frames] frames of [pcm] (interleaved); returns the MP3 bytes written to [out]. */
    fun encode(pcm: ShortArray, frames: Int, out: ByteArray): Int = nativeEncode(h, pcm, frames, out)

    fun flush(out: ByteArray): Int = nativeFlush(h, out)

    override fun close() {
        if (h != 0L) nativeClose(h)
        h = 0L
    }

    companion object {
        init {
            System.loadLibrary("onit_mp3")
        }

        @JvmStatic private external fun nativeInit(rate: Int, channels: Int, kbps: Int): Long
        @JvmStatic private external fun nativeEncode(h: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int
        @JvmStatic private external fun nativeFlush(h: Long, out: ByteArray): Int
        @JvmStatic private external fun nativeClose(h: Long)

        /** Room for the MP3 of [frames] frames (LAME's worst case). */
        fun outSize(frames: Int) = frames * 5 / 4 + 7200
    }
}

/** A video's sound saved as an MP3 file. */
object Mp3Export {
    class Result(val uri: Uri, val name: String, val bytes: Long, val seconds: Float)

    /**
     * Decodes [src]'s sound on the device and encodes it at [kbps]; saved in Download/ONIT.
     * Mono stays mono, more than two channels become stereo.
     */
    fun convert(context: Context, src: Uri, baseName: String, kbps: Int = 192, isActive: () -> Boolean, onProgress: (Float) -> Unit): Result {
        val ex = MediaExtractor()
        ex.setDataSource(context, src, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            ex.release()
            error(t("אין סאונד בסרטון"))
        }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFmt, null, null, 0)
        codec.start()
        val tmp = File(context.cacheDir, "audio.mp3")
        var enc: Mp3Encoder? = null
        var outCh = 0
        var inCh = 0
        var isFloat = false
        var lastUs = 0L
        try {
            FileOutputStream(tmp).use { file ->
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                var pcm = ShortArray(0)
                var mp3 = ByteArray(0)
                while (!outDone) {
                    if (!isActive()) throw InterruptedException()
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        outCh = if (inCh == 1) 1 else 2
                        enc?.close()
                        enc = Mp3Encoder(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), outCh, kbps)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val e = enc ?: run {
                                // No format change was reported: take the track's.
                                inCh = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                outCh = if (inCh == 1) 1 else 2
                                Mp3Encoder(inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), outCh, kbps).also { enc = it }
                            }
                            val b = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            val floats = if (isFloat) b.asFloatBuffer() else null
                            val shorts = if (isFloat) null else b.asShortBuffer()
                            val frames = (floats?.remaining() ?: shorts!!.remaining()) / inCh
                            if (pcm.size < frames * outCh) pcm = ShortArray(frames * outCh)
                            fun sample(i: Int): Short =
                                if (floats != null) (floats.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort() else shorts!!.get(i)
                            // Mono and stereo as they are; more channels: front left and right.
                            for (k in 0 until frames) {
                                for (c in 0 until outCh) pcm[k * outCh + c] = sample(k * inCh + c)
                            }
                            val need = Mp3Encoder.outSize(frames)
                            if (mp3.size < need) mp3 = ByteArray(need)
                            val n = e.encode(pcm, frames, mp3)
                            if (n > 0) file.write(mp3, 0, n)
                            lastUs = info.presentationTimeUs
                            if (durUs > 0) onProgress((lastUs.toFloat() / durUs).coerceIn(0f, 0.99f))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
                enc?.let { e ->
                    val tail = ByteArray(7200 * 2)
                    val n = e.flush(tail)
                    if (n > 0) file.write(tail, 0, n)
                }
            }
        } finally {
            enc?.close()
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        val name = "$baseName.mp3"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ONIT")
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error(t("לא ניתן לשמור את הקובץ"))
        r.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
        val bytes = tmp.length()
        tmp.delete()
        onProgress(1f)
        return Result(uri, name, bytes, (if (durUs > 0) durUs else lastUs) / 1e6f)
    }
}
