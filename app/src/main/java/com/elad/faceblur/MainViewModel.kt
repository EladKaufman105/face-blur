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
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import android.os.Handler
import android.os.Looper
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.project.ProjectMeta
import com.elad.faceblur.project.ProjectStore
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject
import com.elad.faceblur.analysis.MaskStore
import com.elad.faceblur.effects.Background
import java.io.File
import java.util.concurrent.Executors

/** Where a quick tool on the home screen takes the video: the editor, without face detection. */
enum class QuickTool { SUBTITLES, BACKGROUND, SOUND, MP3 }

/** What the progress screen shows: a small copy of the frame being processed, and faces found on it. */
class LivePreview(val frame: android.graphics.Bitmap, val faces: List<com.elad.faceblur.analysis.Box>, val gpu: Boolean = false)

sealed interface UiState {
    data object Empty : UiState
    data object Loading : UiState
    data class Ready(val info: VideoInfo) : UiState
    /**
     * @param stage 0 preparing, 1 analysis, 2 export.
     * @param auto the export follows the analysis on its own (two steps in one go).
     */
    data class Working(val info: VideoInfo, val stage: Int, val progress: Float, val subsOnly: Boolean = false, val auto: Boolean = false) : UiState
    data class Editing(val info: VideoInfo, val editor: EditorController) : UiState
    data class Done(val info: VideoInfo, val result: Outcome.Saved, val canEdit: Boolean, val subsOnly: Boolean = false) : UiState
    data class NoFaces(val info: VideoInfo, val canEdit: Boolean) : UiState
    /** A saved project whose video can't be read any more: pick it again. [wrong]: the last pick was another video. */
    data class Relink(val project: ProjectMeta, val wrong: Boolean = false) : UiState
    data class Failed(val info: VideoInfo?, val message: String) : UiState
    /** A video's sound saved as an MP3. */
    data class AudioDone(val info: VideoInfo, val result: com.elad.faceblur.audio.Mp3Export.Result) : UiState
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

    /** Saved projects, last edited first. */
    var projects by mutableStateOf<List<ProjectMeta>>(emptyList())
        private set
    /** The project of the open session. */
    private var projectId: String? = null
    private var autosave: Job? = null
    /** Writes projects one at a time, also after the screen is gone. */
    private val saver = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The frame being processed right now (progress screen); null before the first one. */
    /** The last crash's report (shown once, to send to the developer), or null. */
    var crashReport by mutableStateOf<String?>(runCatching { CrashLog.pending(app) }.getOrNull())
        private set

    fun dismissCrash() {
        crashReport = null
        CrashLog.clear(getApplication())
    }

    /** A failure in the editor's background work: shown with its details, not a crash. */
    private val editorErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.w("ONIT", "editor task failed", e)
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        main.post { crashReport = "editor task\n\n" + sw.toString().take(20_000) }
    }
    private val editorScope get() = viewModelScope + editorErrors

    var livePreview by mutableStateOf<LivePreview?>(null)
        private set

    private fun showLive(frame: android.graphics.Bitmap, faces: List<com.elad.faceblur.analysis.Box>, gpu: Boolean = false) {
        main.post { if (state is UiState.Working) livePreview = LivePreview(frame, faces, gpu) }
    }

    init {
        refreshProjects()
        // Face detection on the GPU and background removal's second model need files (12 + 13
        // MB, once): fetch them quietly.
        viewModelScope.launch {
            if (!models.gpuFaceReady) runCatching { models.downloadGpuFaceModels() }
            if (!models.segReady) runCatching { models.downloadSegModels() }
        }
    }

    // Only the newest listing is shown: an older one finishing late (say, from before a rename)
    // must not overwrite it.
    private var listing = 0

    fun refreshProjects() {
        val n = ++listing
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { ProjectStore.list(getApplication()) }
            if (n == listing) projects = list
        }
    }

    /** What the editor changes, for project.json. */
    private fun editorFields(ctrl: EditorController): JSONObject = JSONObject()
        .put("style", prefs.subStyle.toJson())
        .put("split", JSONObject().put("words", ctrl.split.words).put("chars", ctrl.split.chars))
        .put("audio", ctrl.audio.toJson())
        .put("frame", ctrl.frame)
        .put("background", ctrl.background.toJson())
        .put("stabilize", ctrl.stabilizeLevel)
        .put("settings", ctrl.session.settings.let { st -> JSONObject().put("mode", st.mode.name).put("strength", st.strength.toDouble()).put("padding", st.padding.toDouble()).put("sensitive", st.sensitive) })

    /** Every new editing session is saved as a project. */
    private fun beginProject(ctrl: EditorController) {
        val s = ctrl.session
        val id = ProjectStore.newId()
        projectId = id
        s.projectDir = ProjectStore.dir(getApplication(), id)
        val json = editorFields(ctrl)
            .put("v", 1)
            .put("name", s.info.name)
            .put("uri", s.info.uri.toString())
            .put("created", System.currentTimeMillis())
            .put("durationMs", s.info.durationMs)
            .put("subtitlesOnly", s.subtitlesOnly)
            .put("analysisMs", s.analysisMs)
        val edits = ctrl.model.exportState()
        val app = getApplication<Application>()
        saver.execute {
            runCatching { ProjectStore.create(app, id, s.analysis, s.info.thumbnail, json, edits) }
                .onFailure { android.util.Log.w("ONIT", "project not saved", it) }
            main.post { refreshProjects() }
        }
        startAutosave(ctrl)
    }

    private fun startAutosave(ctrl: EditorController) {
        autosave?.cancel()
        autosave = viewModelScope.launch {
            snapshotFlow { listOf(ctrl.version, ctrl.audio, ctrl.split, prefs.subStyle, ctrl.background, ctrl.masksReady, ctrl.session.settings, ctrl.stabilizeLevel) }
                .drop(1)
                .collectLatest {
                    delay(1500)
                    saveProject(ctrl)
                }
        }
    }

    /** Saves the open project now (its state is copied here; written in the background). */
    fun saveProject(ctrl: EditorController? = editor) {
        val id = projectId ?: return
        if (ctrl == null) return
        val fields = editorFields(ctrl)
        val edits = ctrl.model.exportState()
        val app = getApplication<Application>()
        saver.execute {
            runCatching { ProjectStore.save(app, id, fields, edits) }
                .onFailure { android.util.Log.w("ONIT", "project not saved", it) }
            main.post { if (state !is UiState.Editing) refreshProjects() }
        }
    }

    fun openProject(id: String) {
        if (state is UiState.Working) return
        closeSession()
        state = UiState.Loading
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            val loaded = try {
                withContext(Dispatchers.IO) { ProjectStore.load(app, id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (a native library that didn't load): reported, not a crash.
                android.util.Log.w("ONIT", "project not loaded", e)
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                crashReport = "loading project $id\n\n" + sw.toString().take(20_000)
                state = UiState.Failed(null, t("לא ניתן לפתוח את הפרויקט"))
                return@launch
            }
            val info = try {
                withContext(Dispatchers.IO) { VideoInfo.probe(app, loaded.meta.uri) }
            } catch (e: CancellationException) {
                loaded.analysis.close()
                throw e
            } catch (e: Exception) {
                loaded.analysis.close()
                state = UiState.Relink(loaded.meta)
                return@launch
            }
            // Anything that goes wrong from here (an old or damaged project) is reported, not a crash.
            try {
            val j = loaded.json
            val sj = j.optJSONObject("settings") ?: JSONObject()
            val settings = Settings(
                runCatching { BlurMode.valueOf(sj.optString("mode")) }.getOrDefault(BlurMode.BLUR),
                sj.optDouble("strength", 0.6).toFloat(),
                sj.optDouble("padding", 1.6).toFloat(),
                sj.optBoolean("sensitive"),
            )
            val session = EditSession(info, settings, loaded.analysis, j.optLong("analysisMs"), j.optBoolean("subtitlesOnly"))
            loaded.edits?.let { session.model.restore(it) }
            val dir = ProjectStore.dir(app, id)
            session.projectDir = dir
            session.masks = withContext(Dispatchers.IO) { MaskStore.open(File(dir, "masks.bin"), File(dir, "masks.bin.idx")) }
            val ctrl = EditorController(session, editorScope, app, models, prefs)
            if (session.subtitlesOnly) ctrl.previewAvailable = false
            j.optString("style").takeIf { it.isNotBlank() }?.let { prefs.updateSubStyle(SubtitleStyle.fromJson(it)) }
            val sp = j.optJSONObject("split")
            ctrl.restore(
                SplitRule(sp?.optInt("words") ?: 0, sp?.optInt("chars") ?: 0),
                AudioEdit.fromJson(j.optJSONObject("audio")),
                j.optInt("frame"),
                Background.fromJson(j.optJSONObject("background")),
                when (val st = j.opt("stabilize")) { is Boolean -> if (st) 2 else 0; is Number -> st.toInt(); else -> 0 },
            )
            editor = ctrl
            projectId = id
            startAutosave(ctrl)
            state = UiState.Editing(info, ctrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("ONIT", "project not opened", e)
                runCatching { loaded.analysis.close() }
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                crashReport = "opening project $id\n\n" + sw.toString().take(20_000)
                state = UiState.Failed(null, t("לא ניתן לפתוח את הפרויקט"))
            }
        }
    }

    /** The project's video, picked again. */
    fun relink(uri: Uri) {
        val project = (state as? UiState.Relink)?.project ?: return
        state = UiState.Loading
        viewModelScope.launch {
            val app = getApplication<Application>()
            val info = try {
                withContext(Dispatchers.IO) { VideoInfo.probe(app, uri) }
            } catch (e: Exception) {
                state = UiState.Relink(project, wrong = true)
                return@launch
            }
            if (project.durationMs > 0 && kotlin.math.abs(info.durationMs - project.durationMs) > 1500) {
                state = UiState.Relink(project, wrong = true)
                return@launch
            }
            withContext(Dispatchers.IO) { ProjectStore.relink(app, project.id, uri) }
            openProject(project.id)
        }
    }

    fun deleteProject(id: String) {
        val app = getApplication<Application>()
        projects = projects.filter { it.id != id }
        saver.execute {
            ProjectStore.delete(app, id)
            main.post { refreshProjects() }
        }
    }

    fun renameProject(id: String, name: String) {
        val n = name.trim().take(80)
        if (n.isEmpty()) return
        val app = getApplication<Application>()
        saver.execute {
            runCatching { ProjectStore.rename(app, id, n) }
            main.post { refreshProjects() }
        }
    }

    /** Closes the editor (the project is saved) and goes home. */
    fun closeEditor() {
        job?.cancel()
        closeSession()
        state = UiState.Empty
        refreshProjects()
    }

    /** @param tool go straight to the editor on that tool (no face detection). */
    fun load(uri: Uri, tool: QuickTool? = null) {
        if (state is UiState.Working) return
        closeSession()
        state = UiState.Loading
        viewModelScope.launch {
            state = try {
                UiState.Ready(withContext(Dispatchers.IO) { VideoInfo.probe(getApplication(), uri) })
            } catch (e: Exception) {
                UiState.Failed(null, e.message ?: t("לא ניתן לפתוח את הסרטון"))
            }
            if (tool == QuickTool.MP3 && state is UiState.Ready) convertMp3() else if (tool != null && state is UiState.Ready) startTool(tool)
        }
    }

    /** The video's sound as an MP3 in Download/ONIT (stage 3 of the progress screen). */
    fun convertMp3() {
        val info = currentInfo() ?: return
        closeSession()
        state = UiState.Working(info, 3, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Ready(info) }) {
                val base = info.name.substringBeforeLast('.') + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val r = withContext(Dispatchers.IO) {
                    com.elad.faceblur.audio.Mp3Export.convert(getApplication(), info.uri, base, isActive = { isActive }) { p ->
                        viewModelScope.launch(Dispatchers.Main) { if (state is UiState.Working) state = UiState.Working(info, 3, p) }
                    }
                }
                state = UiState.AudioDone(info, r)
            }
        }
    }

    fun start() {
        val info = currentInfo() ?: return
        closeSession()
        val settings = Settings(mode, strength, padding, sensitive)
        val auto = !review
        livePreview = null
        state = UiState.Working(info, 1, 0f, auto = auto)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Ready(info) }) {
                val processor = FaceBlurProcessor(getApplication())
                val t0 = SystemClock.elapsedRealtime()
                val analysis = processor.analyze(
                    info, settings,
                    onPreview = { frame, dets, acc -> showLive(frame, dets.filter { it.score >= 0.5f }.map { it.box }, acc == "gpu") },
                ) { p -> state = UiState.Working(info, 1, p, auto = auto) }
                val session = EditSession(info, settings, analysis, SystemClock.elapsedRealtime() - t0)
                val ctrl = EditorController(session, editorScope, getApplication(), models, prefs)
                editor = ctrl
                beginProject(ctrl)
                if (review) {
                    state = UiState.Editing(info, ctrl)
                } else if (session.model.tracks.isEmpty()) {
                    state = UiState.NoFaces(info, canEdit = true)
                } else {
                    render(ctrl, auto = true)
                }
            }
        }
    }

    /** Subtitles without blurring: the editor on the video's frames, no face detection. */
    fun startSubtitles() = startTool(QuickTool.SUBTITLES)

    /** The editor on the video's frames, without face detection, open on [tool]. */
    fun startTool(tool: QuickTool) {
        val info = currentInfo() ?: return
        closeSession()
        state = UiState.Working(info, 0, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Ready(info) }) {
                val analysis = withContext(Dispatchers.IO) { FaceBlurProcessor.framesOnly(getApplication(), info) }
                val session = EditSession(info, Settings(mode, strength, padding, sensitive), analysis, 0, subtitlesOnly = true)
                val ctrl = EditorController(session, editorScope, getApplication(), models, prefs)
                ctrl.previewAvailable = false
                when (tool) {
                    QuickTool.SUBTITLES -> ctrl.subsSheet = true
                    QuickTool.BACKGROUND -> ctrl.bgPanel = true
                    QuickTool.SOUND -> ctrl.voicePanel = true
                    QuickTool.MP3 -> Unit
                }
                editor = ctrl
                beginProject(ctrl)
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

    private suspend fun render(ctrl: EditorController, auto: Boolean = false) {
        val s = ctrl.session
        if (!auto) livePreview = null
        state = UiState.Working(s.info, 2, 0f, s.subtitlesOnly, auto)
        saveProject(ctrl)
        val saved = FaceBlurProcessor(getApplication()).render(
            s.info, s.settings, s.analysis, s.model, s.analysisMs, prefs.export, prefs.subStyle, ctrl.audio,
            ctrl.background, s.masks,
            stabilizer = ctrl.stabilizer,
            onPreview = { frame -> showLive(frame, emptyList()) },
        ) { p -> state = UiState.Working(s.info, 2, p, s.subtitlesOnly, auto) }
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
        autosave?.cancel()
        autosave = null
        editor?.let { saveProject(it) }
        projectId = null
        editor?.cancelTracking()
        editor?.cancelSubtitles()
        editor?.cancelMotion()
        editor?.session?.close()
        editor = null
    }

    override fun onCleared() {
        closeSession()
        saver.shutdown()
    }

    private fun currentInfo(): VideoInfo? = when (val s = state) {
        is UiState.Ready -> s.info
        is UiState.Working -> s.info
        is UiState.Editing -> s.info
        is UiState.Done -> s.info
        is UiState.NoFaces -> s.info
        is UiState.Failed -> s.info
        is UiState.AudioDone -> s.info
        else -> null
    }
}
