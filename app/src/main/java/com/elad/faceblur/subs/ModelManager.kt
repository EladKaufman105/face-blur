package com.elad.faceblur.subs

import android.content.Context
import com.elad.faceblur.models.Job
import com.elad.faceblur.models.ModelDownloads
import com.elad.faceblur.models.ModelStore
import com.elad.faceblur.models.NetDecision
import com.elad.faceblur.models.PackId
import java.io.File

/**
 * A speech-recognition model the app can download (once) and then run fully on the device. Its
 * file, URL, size and checksum are in the model registry, under [pack].
 * @param approxBytes for the screens.
 */
enum class SpeechModel(val fileName: String, val pack: PackId, val approxBytes: Long, val language: String) {
    /** ivrit.ai's Hebrew fine-tune of Whisper large-v3-turbo (Apache-2.0), quantized to q5_0. */
    HEBREW("ggml-ivrit-large-v3-turbo-q5_0.bin", PackId.SPEECH_HE, 574_000_000L, "he"),

    /** OpenAI's Whisper large-v3-turbo (MIT), any language, q5_0. */
    MULTILINGUAL("ggml-large-v3-turbo-q5_0.bin", PackId.SPEECH_ANY, 574_000_000L, "auto"),
}

/**
 * The speech and speaker models, as the subtitle screens see them: a thin layer over the model
 * store and its downloads ([ModelStore], [ModelDownloads]), plus the learned transcription speed.
 */
class ModelManager(context: Context) {
    private val store = ModelStore.get(context)
    private val downloads = ModelDownloads.get(context)
    private val sp = context.applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE)

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

    /** The speech model being downloaded (queued, downloading or checking), if any. */
    val downloading: SpeechModel?
        get() = SpeechModel.entries.firstOrNull { store.statuses[it.pack]?.busy == true }

    /** Its progress 0..1. */
    val progress: Float
        get() = downloading?.let { (store.statuses[it.pack]?.job as? Job.Downloading)?.fraction } ?: 0f

    fun file(m: SpeechModel) = store.file(m.fileName)

    /** Installed and checked (reads the screens' state: a composable that asks recomposes on a change). */
    fun isReady(m: SpeechModel): Boolean = store.statuses[m.pack]?.usable == true

    /** Can be downloaded (not a model whose mirror isn't published yet). */
    fun isAvailable(m: SpeechModel): Boolean = store.registry[m.pack].pending == null

    fun delete(m: SpeechModel) {
        store.delete(m.pack)
    }

    /** The voice model that tells speakers apart (WeSpeaker, ~13 MB). */
    val speakerFile: File get() = store.file(SPEAKER_FILE)

    val speakerReady: Boolean get() = store.statuses[PackId.SPEAKERS]?.usable == true

    /** Asks for the speaker model (it downloads in the background, by the user's network rule). */
    fun requestSpeakerModel(): NetDecision = downloads.request(listOf(PackId.SPEAKERS))

    companion object {
        /** WeSpeaker ResNet34-LM (CC BY 4.0, attribution in the licences screen and THIRD-PARTY-NOTICES.md), made fit for OpenCV; see the repo's speaker-model branch. */
        const val SPEAKER_FILE = "speaker-wespeaker-r34lm-fp16.onnx"
    }
}
