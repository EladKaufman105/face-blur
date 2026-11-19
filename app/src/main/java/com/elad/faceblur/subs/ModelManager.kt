package com.elad.faceblur.subs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A speech-recognition model the app can download (once) and then run fully on the device.
 * @param approxBytes for the UI; the real size comes from the server.
 */
enum class SpeechModel(val fileName: String, val url: String, val approxBytes: Long, val language: String) {
    /** ivrit.ai's Hebrew fine-tune of Whisper large-v3-turbo (Apache-2.0), quantized to q5_0. */
    HEBREW(
        "ggml-ivrit-large-v3-turbo-q5_0.bin",
        "https://github.com/EladKaufman105/face-blur/releases/download/models-1/ggml-ivrit-large-v3-turbo-q5_0.bin",
        574_000_000L,
        "he",
    ),

    /** OpenAI's Whisper large-v3-turbo (MIT), any language, q5_0. */
    MULTILINGUAL(
        "ggml-large-v3-turbo-q5_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        574_000_000L,
        "auto",
    ),
}

/** Downloads and keeps speech models in the app's private storage. */
class ModelManager(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    /** Model being downloaded and its progress 0..1. */
    var downloading by mutableStateOf<SpeechModel?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    /** Bumped when files appear or disappear. */
    var version by mutableStateOf(0)
        private set

    fun file(m: SpeechModel) = File(dir, m.fileName)

    fun isReady(m: SpeechModel): Boolean {
        @Suppress("UNUSED_VARIABLE") val v = version
        return file(m).let { it.exists() && it.length() > 10_000_000L }
    }

    fun delete(m: SpeechModel) {
        file(m).delete()
        File(dir, m.fileName + ".part").delete()
        version++
    }

    /** Downloads [m] (resuming a partial download). Throws on failure; cancellable. */
    suspend fun download(m: SpeechModel) {
        downloading = m
        progress = 0f
        try {
            withContext(Dispatchers.IO) {
                val part = File(dir, m.fileName + ".part")
                var url = URL(m.url)
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    if (part.exists() && part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < 8) {
                        val loc = conn.getHeaderField("Location") ?: error("redirect without location")
                        url = URL(url, loc)
                        conn.disconnect()
                        redirects++
                        continue
                    }
                    if (code == 416) {
                        // Range past the end: the partial file is complete.
                        conn.disconnect()
                        break
                    }
                    if (code != 200 && code != 206) error("HTTP $code")
                    val resume = code == 206
                    if (!resume) part.delete()
                    val already = if (resume) part.length() else 0L
                    val total = already + conn.contentLengthLong.coerceAtLeast(0L)
                    conn.inputStream.use { input ->
                        FileOutputStream(part, resume).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var done = already
                            var shown = -1f
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val p = if (total > 0) done.toFloat() / total else 0f
                                if (p - shown >= 0.002f) {
                                    shown = p
                                    withContext(Dispatchers.Main) { progress = p }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    break
                }
                if (part.length() < 10_000_000L) error("download incomplete")
                if (!part.renameTo(file(m))) error("can't save the model")
            }
        } finally {
            downloading = null
            version++
        }
    }
}
