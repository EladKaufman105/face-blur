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
    private val sp = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    /** Expected seconds of work per second of audio for the model at [path] (learned). */
    fun speed(path: String): Float {
        val default = if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm") == true) 0.6f else 1.5f
        return sp.getFloat("speed_" + File(path).name, default)
    }

    fun learnSpeed(path: String, measured: Float) {
        val key = "speed_" + File(path).name
        val old = sp.getFloat(key, -1f)
        sp.edit().putFloat(key, if (old <= 0f) measured else old * 0.4f + measured * 0.6f).apply()
    }

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
            fetch(m.fileName, m.url, 10_000_000L) { progress = it }
        } finally {
            downloading = null
            version++
        }
    }

    /** The voice model that tells speakers apart (WeSpeaker, ~13 MB). */
    val speakerFile get() = File(dir, SPEAKER_FILE)

    val speakerReady get() = speakerFile.let { it.exists() && it.length() > 5_000_000L }

    /** Downloads the speaker model once. */
    suspend fun downloadSpeakerModel(onProgress: (Float) -> Unit) {
        try {
            fetch(SPEAKER_FILE, SPEAKER_URL, 5_000_000L, onProgress)
        } finally {
            version++
        }
    }

    /** Face detection on the GPU: its models (2 x 6 MB) are here. */
    val gpuFaceReady get() = GPU_FACE_FILES.all { (name, size) -> File(dir, name).length() == size }

    /** Downloads the GPU face models once (checked by size and SHA-256). */
    suspend fun downloadGpuFaceModels() {
        for ((name, size) in GPU_FACE_FILES) {
            val f = File(dir, name)
            if (f.length() == size) continue
            f.delete()
            fetch(name, "$GPU_FACE_BASE/$name", size) {}
            if (f.length() != size || sha256(f) != GPU_FACE_SHA256[name]) {
                f.delete()
                error("GPU face model damaged")
            }
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads [url] into [name] in the models folder, resuming a partial file. */
    private suspend fun fetch(name: String, urlText: String, minBytes: Long, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val part = File(dir, "$name.part")
            var url = URL(urlText)
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
                                withContext(Dispatchers.Main) { onProgress(p) }
                            }
                        }
                    }
                }
                conn.disconnect()
                break
            }
            if (part.length() < minBytes) error("download incomplete")
            if (!part.renameTo(File(dir, name))) error("can't save the model")
        }
    }

    companion object {
        /** RetinaFace as TFLite for the GPU delegate; see the repo's gpu-model branch and ml/. */
        const val GPU_FACE_BASE = "https://raw.githubusercontent.com/EladKaufman105/face-blur/gpu-model"
        val GPU_FACE_FILES = listOf("retinaface-mv2-1280x736.tflite" to 6314616L, "retinaface-mv2-736x1280.tflite" to 6314616L)
        val GPU_FACE_SHA256 = mapOf(
            "retinaface-mv2-1280x736.tflite" to "e3d003315300f7297c28db24736d7465fa88e5407dc528942e5aff4693fc9d96",
            "retinaface-mv2-736x1280.tflite" to "cdc93a8e1af969314ffa6d39e704bff6323c136569051f75be4a396acfd7f293",
        )

        const val SPEAKER_FILE = "speaker-wespeaker-r34lm-fp16.onnx"
        /** WeSpeaker ResNet34-LM (CC BY 4.0), made fit for OpenCV; see the repo's speaker-model branch. */
        const val SPEAKER_URL = "https://raw.githubusercontent.com/EladKaufman105/face-blur/speaker-model/speaker-wespeaker-r34lm-fp16.onnx"
    }
}
