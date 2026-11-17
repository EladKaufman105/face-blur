package com.elad.faceblur

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    data class Done(val info: VideoInfo, val result: Outcome.Saved) : UiState
    data class NoFaces(val info: VideoInfo) : UiState
    data class Failed(val info: VideoInfo?, val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<UiState>(UiState.Empty)
        private set

    var mode by mutableStateOf(BlurMode.BLUR)
    var strength by mutableStateOf(0.7f)
    var padding by mutableStateOf(1.35f)
    var sensitive by mutableStateOf(false)

    private var job: Job? = null

    fun load(uri: Uri) {
        if (state is UiState.Working) return
        state = UiState.Loading
        viewModelScope.launch {
            state = try {
                UiState.Ready(withContext(Dispatchers.IO) { VideoInfo.probe(getApplication(), uri) })
            } catch (e: Exception) {
                UiState.Failed(null, e.message ?: "לא ניתן לפתוח את הסרטון")
            }
        }
    }

    fun start() {
        val info = currentInfo() ?: return
        val settings = Settings(mode, strength, padding, sensitive)
        state = UiState.Working(info, 1, 0f)
        job = viewModelScope.launch {
            var stage = 1
            try {
                val outcome = FaceBlurProcessor(getApplication()).process(
                    info, settings,
                    onStage = { stage = it; state = UiState.Working(info, it, 0f) },
                    onProgress = { p -> state = UiState.Working(info, stage, p) },
                )
                state = when (outcome) {
                    is Outcome.Saved -> UiState.Done(info, outcome)
                    Outcome.NoFaces -> UiState.NoFaces(info)
                }
            } catch (e: CancellationException) {
                state = UiState.Ready(info)
                throw e
            } catch (e: ExportException) {
                state = UiState.Failed(info, "העיבוד נכשל (${e.errorCodeName}): ${e.cause?.message ?: e.message}")
            } catch (e: Exception) {
                state = UiState.Failed(info, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        val info = currentInfo()
        job?.cancel()
        job = null
        if (info != null) state = UiState.Ready(info)
    }

    fun backToSettings() {
        currentInfo()?.let { state = UiState.Ready(it) }
    }

    fun retrySensitive() {
        sensitive = true
        backToSettings()
    }

    fun reset() {
        job?.cancel()
        state = UiState.Empty
    }

    private fun currentInfo(): VideoInfo? = when (val s = state) {
        is UiState.Ready -> s.info
        is UiState.Working -> s.info
        is UiState.Done -> s.info
        is UiState.NoFaces -> s.info
        is UiState.Failed -> s.info
        else -> null
    }
}
