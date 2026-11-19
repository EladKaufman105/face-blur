package com.elad.faceblur.subs

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hebrew <-> English translation with Google ML Kit, on the device (the language pack, ~30 MB,
 * is downloaded once). Every step has a time limit, so a stalled download or translation
 * fails instead of hanging.
 */
object Translator {
    private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
    private const val LINE_TIMEOUT_MS = 30_000L

    class Failed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    /**
     * Translates each text to the other language, calling [onLine] with each result.
     * @param from per text: "he" or "en" (translated to the other one), anything else is kept as is.
     * @param onDownload called before a language pack may need downloading.
     */
    suspend fun translate(
        texts: List<String>,
        from: List<String>,
        onDownload: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onLine: (Int, String) -> Unit = { _, _ -> },
    ): List<String> {
        val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
        try {
            val out = ArrayList<String>(texts.size)
            texts.forEachIndexed { i, t ->
                val lang = from[i]
                val r = if (t.isBlank()) {
                    ""
                } else if (lang != "he" && lang != "en") {
                    t
                } else {
                    val client = clients[lang] ?: run {
                        val src = if (lang == "en") TranslateLanguage.ENGLISH else TranslateLanguage.HEBREW
                        val dst = if (lang == "en") TranslateLanguage.HEBREW else TranslateLanguage.ENGLISH
                        val c = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
                        clients[lang] = c
                        onDownload()
                        try {
                            withTimeout(DOWNLOAD_TIMEOUT_MS) { c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            throw Failed("translation pack download timed out", e)
                        }
                        c
                    }
                    try {
                        withTimeout(LINE_TIMEOUT_MS) { client.translate(t).await() }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        throw Failed("translation timed out", e)
                    }
                }
                out += r
                onLine(i, r)
                onProgress((i + 1f) / texts.size)
            }
            return out
        } finally {
            clients.values.forEach { it.close() }
        }
    }
}
