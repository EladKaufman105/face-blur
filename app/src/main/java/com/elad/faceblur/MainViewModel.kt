package com.elad.faceblur

import com.elad.faceblur.i18n.t
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.prefs.AppPrefs
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.subs.ModelManager
import androidx.media3.transformer.ExportException
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Outcome
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Empty : UiState
    data object Loading : UiState
    data class Ready(val info: VideoInfo) : UiState
    /** @param stage 0 preparing, 1 analysis, 2 export. */
    data class Working(val info: VideoInfo, val stage: Int, val progress: Float, val subsOnly: Boolean = false) : UiState
    data class Editing(val info: VideoInfo, val editor: EditorController) : UiState
    data class Done(val info: VideoInfo, val result: Outcome.Saved, val canEdit: Boolean, val subsOnly: Boolean = false) : UiState
    data class NoFaces(val info: VideoInfo, val canEdit: Boolean) : UiState
    data class Failed(val info: VideoInfo?, val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<UiState>(UiState.Empty)
        private set

    var mode by mutableStateOf(BlurMode.BLUR)
    var strength by mutableStateOf(0.6f)
    var padding by mutableStateOf(1.6f)
    var sensitive by mutableStateOf(false)
    /** Open the editor between analysis and export. */
    var review by mutableStateOf(true)

    /** Language, theme and export choices (persisted). */
    val prefs = AppPrefs(app)

    /** On-device speech models for subtitles. */
    val models = ModelManager(app)

    private var job: Job? = null
    private var editor: EditorController? = null

    /** @param thenSubtitles go straight to the subtitles editor (no blurring). */
    fun load(uri: Uri, thenSubtitles: Boolean = false) {
        if (state is UiState.Working) return
        closeSession()
        state = UiState.Loading
        viewModelScope.launch {
            state = try {
                UiState.Ready(withContext(Dispatchers.IO) { VideoInfo.probe(getApplication(), uri) })
            } catch (e: Exception) {
                UiState.Failed(null, e.message ?: t("לא ניתן לפתוח את הסרטון"))
            }
            if (thenSubtitles && state is UiState.Ready) startSubtitles()
        }
    }

    fun start() {
        val info = currentInfo() ?: return
        closeSession()
        val settings = Settings(mode, strength, padding, sensitive)
        state = UiState.Working(info, 1, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Ready(info) }) {
                val processor = FaceBlurProcessor(getApplication())
                val t0 = SystemClock.elapsedRealtime()
                val analysis = processor.analyze(info, settings) { p -> state = UiState.Working(info, 1, p) }
                val session = EditSession(info, settings, analysis, SystemClock.elapsedRealtime() - t0)
                val ctrl = EditorController(session, viewModelScope, getApplication(), models, prefs)
                editor = ctrl
                if (review) {
                    state = UiState.Editing(info, ctrl)
                } else if (session.model.tracks.isEmpty()) {
                    state = UiState.NoFaces(info, canEdit = true)
                } else {
                    render(ctrl)
                }
            }
        }
    }

    /** Subtitles without blurring: the editor on the video's frames, no face detection. */
    fun startSubtitles() {
        val info = currentInfo() ?: return
        closeSession()
        state = UiState.Working(info, 0, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Ready(info) }) {
                val analysis = withContext(Dispatchers.IO) { FaceBlurProcessor.framesOnly(getApplication(), info) }
                val session = EditSession(info, Settings(mode, strength, padding, sensitive), analysis, 0, subtitlesOnly = true)
                val ctrl = EditorController(session, viewModelScope, getApplication(), models, prefs)
                ctrl.previewAvailable = false
                ctrl.subsSheet = true
                editor = ctrl
                state = UiState.Editing(info, ctrl)
            }
        }
    }

    /** Opens the editor on the current session (after export, or with no faces found). */
    fun edit() {
        val ctrl = editor ?: return
        state = UiState.Editing(ctrl.session.info, ctrl)
    }

    fun export() {
        val ctrl = editor ?: return
        ctrl.pauser()
        ctrl.cancelTracking()
        ctrl.cancelSubtitles()
        val info = ctrl.session.info
        state = UiState.Working(info, 2, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Editing(info, ctrl) }) { render(ctrl) }
        }
    }

    private suspend fun render(ctrl: EditorController) {
        val s = ctrl.session
        state = UiState.Working(s.info, 2, 0f, s.subtitlesOnly)
        val saved = FaceBlurProcessor(getApplication()).render(
            s.info, s.settings, s.analysis, s.model, s.analysisMs, prefs.export, prefs.subStyle,
        ) { p -> state = UiState.Working(s.info, 2, p, s.subtitlesOnly) }
        if (s.analysisMs > 0 && saved.renderMs > 0) prefs.renderRatio = saved.renderMs.toFloat() / s.analysisMs
        state = UiState.Done(s.info, saved, canEdit = true, subsOnly = s.subtitlesOnly)
    }

    private suspend fun guard(info: VideoInfo, onCancel: () -> UiState, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            state = onCancel()
            throw e
        } catch (e: ExportException) {
            state = UiState.Failed(info, t("העיבוד נכשל (%s): %s").format(e.errorCodeName, e.cause?.message ?: e.message))
        } catch (e: Exception) {
            state = UiState.Failed(info, e.message ?: e.javaClass.simpleName)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun backToSettings() {
        val info = currentInfo() ?: return
        closeSession()
        state = UiState.Ready(info)
    }

    fun retrySensitive() {
        sensitive = true
        backToSettings()
    }

    fun reset() {
        job?.cancel()
        closeSession()
        state = UiState.Empty
    }

    private fun closeSession() {
        editor?.cancelTracking()
        editor?.cancelSubtitles()
        editor?.session?.close()
        editor = null
    }

    override fun onCleared() {
        closeSession()
    }

    private fun currentInfo(): VideoInfo? = when (val s = state) {
        is UiState.Ready -> s.info
        is UiState.Working -> s.info
        is UiState.Editing -> s.info
        is UiState.Done -> s.info
        is UiState.NoFaces -> s.info
        is UiState.Failed -> s.info
        else -> null
    }
}
