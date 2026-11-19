package com.elad.faceblur.subs

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hebrew <-> English translation with Google ML Kit, on the device (the language pack, ~30 MB,
 * is downloaded once).
 */
object Translator {
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    /**
     * Translates each text to the other language.
     * @param from per text: "he" or "en" (translated to the other one), anything else is kept as is.
     */
    suspend fun translate(texts: List<String>, from: List<String>, onProgress: (Float) -> Unit): List<String> {
        val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
        try {
            val out = ArrayList<String>(texts.size)
            texts.forEachIndexed { i, t ->
                val lang = from[i]
                out += if (t.isBlank()) {
                    ""
                } else if (lang != "he" && lang != "en") {
                    t
                } else {
                    val client = clients.getOrPut(lang) {
                        val src = if (lang == "en") TranslateLanguage.ENGLISH else TranslateLanguage.HEBREW
                        val dst = if (lang == "en") TranslateLanguage.HEBREW else TranslateLanguage.ENGLISH
                        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
                            .also { it.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                    }
                    client.translate(t).await()
                }
                onProgress((i + 1f) / texts.size)
            }
            return out
        } finally {
            clients.values.forEach { it.close() }
        }
    }
}
