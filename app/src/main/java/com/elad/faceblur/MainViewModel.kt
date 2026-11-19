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
    data class Working(val info: VideoInfo, val stage: Int, val progress: Float) : UiState
    data class Editing(val info: VideoInfo, val editor: EditorController) : UiState
    data class Done(val info: VideoInfo, val result: Outcome.Saved, val canEdit: Boolean) : UiState
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

    private var job: Job? = null
    private var editor: EditorController? = null

    fun load(uri: Uri) {
        if (state is UiState.Working) return
        closeSession()
        state = UiState.Loading
        viewModelScope.launch {
            state = try {
                UiState.Ready(withContext(Dispatchers.IO) { VideoInfo.probe(getApplication(), uri) })
            } catch (e: Exception) {
                UiState.Failed(null, e.message ?: t("לא ניתן לפתוח את הסרטון"))
            }
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
                val ctrl = EditorController(session, viewModelScope)
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

    /** Opens the editor on the current session (after export, or with no faces found). */
    fun edit() {
        val ctrl = editor ?: return
        state = UiState.Editing(ctrl.session.info, ctrl)
    }

    fun export() {
        val ctrl = editor ?: return
        ctrl.pauser()
        ctrl.cancelTracking()
        val info = ctrl.session.info
        state = UiState.Working(info, 2, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Editing(info, ctrl) }) { render(ctrl) }
        }
    }

    private suspend fun render(ctrl: EditorController) {
        val s = ctrl.session
        state = UiState.Working(s.info, 2, 0f)
        val saved = FaceBlurProcessor(getApplication()).render(
            s.info, s.settings, s.analysis, s.model, s.analysisMs, prefs.export,
        ) { p -> state = UiState.Working(s.info, 2, p) }
        state = UiState.Done(s.info, saved, canEdit = true)
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
