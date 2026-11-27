package com.elad.faceblur.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

/**
 * Hebrew <-> English translation's language pack, which ML Kit keeps (and downloads by itself
 * when a translation needs it): shown in the same list as the models, and downloaded or deleted
 * from there through ML Kit. About 30 MB (Google's number).
 */
object TranslationPacks {
    enum class State { UNKNOWN, MISSING, READY, DOWNLOADING, WAITING_WIFI, FAILED }

    /** The approximate size, for the screens. */
    const val APPROX_BYTES = 30L * 1_000_000L

    var state by mutableStateOf(State.UNKNOWN)
        private set

    private val hebrew get() = TranslateRemoteModel.Builder(TranslateLanguage.HEBREW).build()

    private fun manager(): RemoteModelManager? = runCatching { RemoteModelManager.getInstance() }.getOrNull()

    /** Looks at what ML Kit has (not while a download runs). */
    fun refresh() {
        if (state == State.DOWNLOADING || state == State.WAITING_WIFI) return
        val m = manager() ?: return
        runCatching {
            m.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { set -> if (state != State.DOWNLOADING && state != State.WAITING_WIFI) state = if (set.any { it.language == TranslateLanguage.HEBREW }) State.READY else State.MISSING }
        }
    }

    /**
     * Downloads the Hebrew pack. [wifiOnly]: ML Kit waits for Wi-Fi itself (the user's rule says
     * Wi-Fi only, or the pack is over their limit for mobile data).
     */
    fun download(wifiOnly: Boolean) {
        val m = manager() ?: return
        if (state == State.READY || state == State.DOWNLOADING) return
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        state = if (wifiOnly) State.WAITING_WIFI else State.DOWNLOADING
        runCatching {
            m.download(hebrew, conditions)
                .addOnSuccessListener { state = State.READY }
                .addOnFailureListener { state = State.FAILED }
        }.onFailure { state = State.FAILED }
    }

    fun delete() {
        val m = manager() ?: return
        if (state == State.DOWNLOADING || state == State.WAITING_WIFI) return
        runCatching {
            m.deleteDownloadedModel(hebrew).addOnCompleteListener {
                state = State.UNKNOWN
                refresh()
            }
        }
    }
}
