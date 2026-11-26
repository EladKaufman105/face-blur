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
 * Translation between Hebrew, English, Arabic and Persian with Google ML Kit, on the device (a
 * language pack of about 30 MB per language is downloaded once; a pair of two other languages
 * goes through English). Every step has a time limit, so a stalled download or translation fails
 * instead of hanging.
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
     * Translates each of [items] (a text with its source and target language), calling [onLine]
     * with each result. A text whose languages aren't translatable, or are the same, is kept as is.
     * @param onDownload called before a language pack may need downloading.
     */
    suspend fun translate(
        items: List<SubtitleEngine.Item>,
        onDownload: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onLine: (Int, String) -> Unit = { _, _ -> },
    ): List<String> {
        val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
        try {
            val out = ArrayList<String>(items.size)
            items.forEachIndexed { i, item ->
                val src = SubLanguages.mlKitTag(item.from)?.let { TranslateLanguage.fromLanguageTag(it) }
                val dst = SubLanguages.mlKitTag(item.to)?.let { TranslateLanguage.fromLanguageTag(it) }
                val r = if (item.text.isBlank()) {
                    ""
                } else if (src == null || dst == null || src == dst) {
                    item.text
                } else {
                    val client = clients["$src>$dst"] ?: run {
                        val c = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
                        clients["$src>$dst"] = c
                        onDownload()
                        try {
                            withTimeout(DOWNLOAD_TIMEOUT_MS) { c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            throw Failed("translation pack download timed out", e)
                        }
                        c
                    }
                    try {
                        withTimeout(LINE_TIMEOUT_MS) { client.translate(item.text).await() }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        throw Failed("translation timed out", e)
                    }
                }
                out += r
                onLine(i, r)
                onProgress((i + 1f) / items.size)
            }
            return out
        } finally {
            clients.values.forEach { it.close() }
        }
    }
}
