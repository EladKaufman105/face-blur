package com.elad.faceblur.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.provider.OpenableColumns
import com.elad.faceblur.subs.AudioDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A sound in the library: [id] "builtin:name" or "user:file.wav"; [name] Hebrew (translated in the UI). */
class SfxSound(val id: String, val name: String) {
    val builtin get() = id.startsWith("builtin:")
}

/**
 * Sound effects: the built-in ones (classic shorts sounds, synthesized by scripts/make_sfx.py,
 * in assets/sfx) and ones the user imported (kept in the app's files). Samples are 48 kHz mono.
 */
object SfxLibrary {
    private const val MAX_IMPORT_SEC = 20

    class ImportException(message: String) : Exception(message)

    val builtins: List<SfxSound> = listOf(
        "whoosh" to "ווש",
        "swoosh" to "ווש מהיר",
        "swipe" to "החלקה",
        "pop" to "פופ",
        "bubble" to "בועה",
        "click" to "קליק",
        "ding" to "דינג",
        "bell" to "פעמון",
        "notify" to "התראה",
        "cash" to "קופה",
        "boom" to "בום",
        "bass_drop" to "בס דרופ",
        "riser" to "עלייה",
        "drama" to "דרמה",
        "zap" to "לייזר",
        "glitch" to "גליץ'",
        "scratch" to "שריטת תקליט",
        "camera" to "מצלמה",
        "typing" to "הקלדה",
        "heartbeat" to "דופק",
        "tada" to "טה-דה",
        "fail" to "כישלון",
        "applause" to "מחיאות כפיים",
    ).map { (id, name) -> SfxSound("builtin:$id", name) }

    private val cache = LinkedHashMap<String, FloatArray>(16, 0.75f, true)

    fun userDir(context: Context) = File(context.filesDir, "sfx").apply { mkdirs() }

    /** Sounds the user imported, oldest first. */
    fun user(context: Context): List<SfxSound> =
        (userDir(context).listFiles { f -> f.isFile && f.extension == "wav" } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .map { SfxSound("user:" + it.name, it.nameWithoutExtension) }

    fun all(context: Context) = builtins + user(context)

    fun name(id: String): String =
        builtins.firstOrNull { it.id == id }?.name ?: id.substringAfter(':').substringBeforeLast('.')

    /** The samples of [id], or null if it's gone. */
    fun load(context: Context, id: String): FloatArray? = synchronized(cache) {
        cache[id]?.let { return it }
        val bytes = try {
            when {
                id.startsWith("builtin:") -> context.assets.open("sfx/" + id.removePrefix("builtin:") + ".wav").use { it.readBytes() }
                id.startsWith("user:") -> File(userDir(context), id.removePrefix("user:")).takeIf { it.exists() }?.readBytes()
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val x = runCatching { readWav(bytes) }.getOrNull() ?: return null
        cache[id] = x
        while (cache.size > 24) cache.remove(cache.keys.first())
        x
    }

    fun durationMs(context: Context, id: String): Long = (load(context, id)?.size ?: 0) * 1000L / SFX_RATE

    /** Imports a sound file (any format the phone decodes, up to 20 s). Returns its id. */
    fun import(context: Context, uri: Uri): String {
        val x = AudioDecoder.decode(context, uri, { true }, SFX_RATE) {} ?: throw ImportException("no audio")
        if (x.isEmpty()) throw ImportException("no audio")
        val clip = if (x.size > MAX_IMPORT_SEC * SFX_RATE) x.copyOf(MAX_IMPORT_SEC * SFX_RATE) else x
        var display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "sound"
        display = display.substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_").take(40).ifBlank { "sound" }
        var name = "$display.wav"
        var i = 2
        while (File(userDir(context), name).exists()) name = "$display ${i++}.wav"
        writeWav(File(userDir(context), name), clip)
        return "user:$name"
    }

    fun delete(context: Context, id: String) {
        if (!id.startsWith("user:")) return
        File(userDir(context), id.removePrefix("user:")).delete()
        synchronized(cache) { cache.remove(id) }
    }

    /** 16-bit PCM WAV (any rate, mono or stereo) to 48 kHz mono. */
    fun readWav(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var pos = 12
        var rate = SFX_RATE
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = b.getInt(pos + 4)
            val data = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(data + 2).toInt()
                rate = b.getInt(data + 4)
                bits = b.getShort(data + 14).toInt()
            } else if (id == "data") {
                require(bits == 16 && channels >= 1)
                val n = minOf(len, bytes.size - data) / (2 * channels)
                val out = FloatArray(n)
                for (i in 0 until n) {
                    var s = 0f
                    for (c in 0 until channels) s += b.getShort(data + (i * channels + c) * 2)
                    out[i] = s / channels / 32768f
                }
                return AudioDecoder.resample(out, rate, SFX_RATE)
            }
            pos = data + len + (len and 1)
        }
        error("no data")
    }

    fun writeWav(f: File, x: FloatArray) {
        val b = ByteBuffer.allocate(44 + x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SFX_RATE).putInt(SFX_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(x.size * 2)
        for (v in x) b.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(b.array())
        tmp.renameTo(f)
    }
}

/** Plays a sound from the library once (to hear it before adding it). */
object SfxPlayer {
    private var track: AudioTrack? = null

    fun play(context: Context, id: String) {
        stop()
        val x = SfxLibrary.load(context, id) ?: return
        val pcm = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SFX_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcm.size * 2, 4096))
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
