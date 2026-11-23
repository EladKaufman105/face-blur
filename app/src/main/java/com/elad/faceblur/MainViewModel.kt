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
import com.elad.faceblur.pipeline.ProcessingErrors
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
import com.elad.faceblur.project.projectFields
import com.elad.faceblur.subs.SubtitleStyle
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.template.BuiltInTemplates
import com.elad.faceblur.template.Template
import com.elad.faceblur.template.TemplateCategory
import com.elad.faceblur.template.TemplateChoice
import com.elad.faceblur.template.TemplateRunner
import com.elad.faceblur.template.TemplateSettings
import com.elad.faceblur.template.TemplateStore
import com.elad.faceblur.template.applyAll
import com.elad.faceblur.batch.Batch
import com.elad.faceblur.batch.BatchItem
import com.elad.faceblur.batch.BatchMath
import com.elad.faceblur.batch.BatchService
import com.elad.faceblur.batch.BatchState
import com.elad.faceblur.template.toTemplate
import org.json.JSONObject
import com.elad.faceblur.analysis.MaskStore
import com.elad.faceblur.effects.Background
import java.io.File
import java.util.concurrent.Executors

/** Where a quick tool on the home screen takes the video: the editor, without face detection. */
enum class QuickTool { SUBTITLES, BACKGROUND, SOUND, MP3 }

/** What the progress screen shows: a small copy of the frame being processed, and faces found on it. */
/** @param stats how the analysis is doing (where detection runs, times), shown on a tap. */
class LivePreview(val frame: android.graphics.Bitmap, val faces: List<com.elad.faceblur.analysis.Box>, val gpu: Boolean = false, val stats: String? = null)

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
    /**
     * [message]: what happened, in words to act on; [details]: the technical part, for a report;
     * [canEdit]: the editor is still there to go back to (an export that failed).
     */
    data class Failed(val info: VideoInfo?, val message: String, val details: String? = null, val canEdit: Boolean = false) : UiState
    /** A video's sound saved as an MP3. */
    data class AudioDone(val info: VideoInfo, val result: com.elad.faceblur.audio.Mp3Export.Result) : UiState
    /** Several videos picked for a batch: choose the template and check the time and space before starting. */
    data class BatchSetup(val videos: List<VideoInfo>) : UiState
    /** The batch's queue (and, when it's done, its results): the batch itself is in [BatchState]. */
    data object BatchRun : UiState
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

    /** The user's templates (and the built-in looks and destinations, in [BuiltInTemplates]). */
    val templates = TemplateStore(app)

    /** What is chosen for the next video: the user's template, or a look and a destination (null: none). */
    var templateChoice by mutableStateOf(TemplateChoice())
        private set

    /** What the chosen templates set, to apply to the next video (null: no template chosen). */
    var templateSettings by mutableStateOf<TemplateSettings?>(null)
        private set
    /** The settings before any template was chosen (a template lays over these). */
    private var templateBase: TemplateSettings? = null

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

    private fun showLive(frame: android.graphics.Bitmap, faces: List<com.elad.faceblur.analysis.Box>, gpu: Boolean = false, stats: String? = null) {
        main.post { if (state is UiState.Working) livePreview = LivePreview(frame, faces, gpu, stats) }
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



    // ------------------------------------------------------------------ batch

    /** The template choice of the batch being set up, and what it comes to. */
    var batchChoice by mutableStateOf(TemplateChoice())
        private set
    var batchSettings by mutableStateOf(TemplateSettings())
        private set
    /** Short notices from the chosen template (a font or picture that's gone). */
    var batchNotes by mutableStateOf<List<String>>(emptyList())
        private set
    var batchDownloadError by mutableStateOf<String?>(null)
        private set

    private fun idsOf(choice: TemplateChoice): List<String> =
        listOfNotNull(choice.user) + (if (choice.user == null) listOfNotNull(choice.look, choice.dest) else emptyList())

    private fun recomputeBatch() {
        val base = templateBase ?: currentSettings()
        val notes = ArrayList<String>()
        val chosen = idsOf(batchChoice).mapNotNull { templates.find(it) }.map { templates.usable(it, notes) }
        batchSettings = applyAll(base, chosen)
        batchNotes = notes
    }

    fun chooseBatchTemplates(choice: TemplateChoice) {
        batchChoice = choice
        recomputeBatch()
    }

    /** Videos picked from the gallery for a batch (up to [BatchMath.MAX_VIDEOS] in all; more can be added on the setup screen). */
    fun pickedForBatch(uris: List<Uri>) {
        if (uris.isEmpty() || state is UiState.Working) return
        val existing = (state as? UiState.BatchSetup)?.videos.orEmpty()
        if (existing.isEmpty()) {
            closeSession()
            batchChoice = TemplateChoice()
            recomputeBatch()
        }
        state = UiState.Loading
        viewModelScope.launch {
            val app = getApplication<Application>()
            val room = (BatchMath.MAX_VIDEOS - existing.size).coerceAtLeast(0)
            val probed = withContext(Dispatchers.IO) {
                uris.filter { u -> existing.none { it.uri == u } }.take(room).mapNotNull { u -> runCatching { VideoInfo.probe(app, u) }.getOrNull() }
            }
            val all = existing + probed
            state = if (all.isEmpty()) UiState.Failed(null, t("לא ניתן לפתוח את הסרטונים שנבחרו")) else UiState.BatchSetup(all)
        }
    }

    fun removeBatchVideo(info: VideoInfo) {
        val setup = state as? UiState.BatchSetup ?: return
        val left = setup.videos.filter { it !== info }
        state = if (left.isEmpty()) UiState.Empty else UiState.BatchSetup(left)
    }

    /** Learned speeds of this phone, for the time estimate. */
    fun batchSpeeds(): BatchMath.Speeds {
        val m = batchSettings.speechModel
        return BatchMath.Speeds(prefs.analysisRatio, prefs.renderRatio, models.speed(models.file(m).path))
    }

    /** Downloads the speech model the batch's template needs (before anything starts). */
    fun downloadBatchModel() {
        val m = batchSettings.speechModel
        if (models.downloading != null || models.isReady(m)) return
        batchDownloadError = null
        viewModelScope.launch {
            try {
                models.download(m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                batchDownloadError = t("הורדת המודל נכשלה: %s").format(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Starts the batch in a foreground service (it goes on with the screen off) and shows its queue. */
    fun startBatch() {
        val setup = state as? UiState.BatchSetup ?: return
        val s = batchSettings
        if (s.needsSpeechModel && !models.isReady(s.speechModel)) return
        val items = setup.videos.mapIndexed { i, v ->
            val thumb = v.thumbnail?.let { b ->
                val k = 320f / maxOf(b.width, b.height, 1)
                if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
            }
            BatchItem(i, v.uri, v.name, v.durationMs, thumb)
        }
        val name = idsOf(batchChoice).mapNotNull { templates.find(it) }.joinToString(" + ") { if (it.builtIn) t(it.name) else it.name }
        BatchState.current = Batch(items, s, name.ifEmpty { t("בלי תבנית") })
        BatchService.start(getApplication())
        state = UiState.BatchRun
    }

    /** Back to the batch's queue or results (from the home screen). */
    fun showBatch() {
        if (BatchState.current == null || state is UiState.Working) return
        closeSession()
        state = UiState.BatchRun
    }

    /** Leaves the queue screen; the batch goes on in the background. */
    fun leaveBatch() {
        state = UiState.Empty
    }

    /** Done with the results list. */
    fun closeBatch() {
        BatchState.current?.takeIf { it.finished }?.let { BatchState.current = null }
        state = UiState.Empty
    }

    /** "Check": opens the video's project in the editor. */
    fun checkBatchItem(item: BatchItem) {
        item.projectId?.let { openProject(it) }
    }

    // ------------------------------------------------------------------ templates

    /** The settings as they stand now (the Ready screen's), for a template to lay over. */
    private fun currentSettings() = TemplateSettings(
        mode = mode, strength = strength, padding = padding, sensitive = sensitive,
        translate = prefs.subsTranslate, subStyle = prefs.subStyle, export = prefs.export,
    )

    /**
     * Chooses the templates for the next video: the settings fill in from them (the blur's look,
     * the subtitle look, the export), and their processing runs after the analysis.
     */
    fun chooseTemplates(choice: TemplateChoice) {
        templateChoice = choice
        val ids = idsOf(choice)
        if (ids.isEmpty()) {
            // Back to what it was before.
            templateBase?.let { applyToScreen(it) }
            templateBase = null
            templateSettings = null
            return
        }
        val base = templateBase ?: currentSettings().also { templateBase = it }
        val notes = ArrayList<String>()
        val chosen = ids.mapNotNull { templates.find(it) }.map { templates.usable(it, notes) }
        val s = applyAll(base, chosen)
        applyToScreen(s)
        templateSettings = s
    }

    private fun applyToScreen(s: TemplateSettings) {
        mode = s.mode
        strength = s.strength
        padding = s.padding
        sensitive = s.sensitive
        prefs.updateExport(s.export)
        prefs.updateSubStyle(s.subStyle)
    }

    /** The editor's current choices as settings (for "Save as template"). */
    fun settingsOf(ctrl: EditorController): TemplateSettings {
        val st = ctrl.session.settings
        val model = if (models.isReady(SpeechModel.HEBREW) || !models.isReady(SpeechModel.MULTILINGUAL)) SpeechModel.HEBREW else SpeechModel.MULTILINGUAL
        return TemplateSettings(
            blurOff = ctrl.session.subtitlesOnly,
            mode = st.mode, strength = st.strength, padding = st.padding, sensitive = st.sensitive,
            createSubs = ctrl.model.cues.isNotEmpty(),
            speechModel = model, language = model.language,
            translate = ctrl.translateOnCreate,
            subStyle = prefs.subStyle,
            split = ctrl.split,
            audio = ctrl.audio.copy(gainDb = 0f),
            cutSilences = ctrl.cutCount > 0, cutFillers = ctrl.cutCount > 0 && ctrl.model.cues.isNotEmpty(),
            background = ctrl.background,
            stabilize = ctrl.stabilizeLevel,
            export = prefs.export,
        )
    }

    /** Which categories the editor has something in (the ones "Save as template" offers on). */
    fun categoriesInUse(ctrl: EditorController): Set<TemplateCategory> = buildSet {
        add(TemplateCategory.EXPORT)
        if (!ctrl.session.subtitlesOnly) add(TemplateCategory.BLUR)
        if (ctrl.model.cues.isNotEmpty()) add(TemplateCategory.SUBTITLES)
        val a = ctrl.audio
        if (a.voice != com.elad.faceblur.audio.VoiceFx.NONE || a.denoise > 0 || a.enhance || a.loud) add(TemplateCategory.VOICE)
        if (ctrl.cutCount > 0) add(TemplateCategory.CUTS)
        if (ctrl.background.active) add(TemplateCategory.BACKGROUND)
        if (ctrl.stabilizeLevel > 0) add(TemplateCategory.STABILIZE)
    }

    /** Saves the editor's choices as the user's template [name], with the [keep] categories. */
    fun saveEditorAsTemplate(ctrl: EditorController, name: String, keep: Set<TemplateCategory>): Template {
        val id = templates.newId()
        var tpl = settingsOf(ctrl).toTemplate(id, name.trim().take(60).ifBlank { t("תבנית חדשה") }, keep)
        tpl.background?.let { b -> tpl = tpl.copy(background = templates.withOwnImage(b, id)) }
        templates.save(tpl)
        return tpl
    }

    /** Runs the chosen template's processing on the open editor session, not waiting (the editor is on screen). */
    private fun applyTemplateAsync(ctrl: EditorController, s: TemplateSettings) {
        editorScope.launch {
            ctrl.notice = t("מחיל את התבנית…")
            val notes = TemplateRunner.applyProcessing(ctrl, s)
            ctrl.notice = notes.firstOrNull() ?: t("התבנית הוחלה")
        }
    }

    /** Every new editing session is saved as a project. */
    private fun beginProject(ctrl: EditorController) {
        val s = ctrl.session
        val id = ProjectStore.newId()
        projectId = id
        s.projectDir = ProjectStore.dir(getApplication(), id)
        val json = ctrl.projectFields(prefs.subStyle)
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
        val fields = ctrl.projectFields(prefs.subStyle)
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
            // A project made from a template exports the way the template said.
            j.optJSONObject("export")?.let { prefs.updateExport(com.elad.faceblur.pipeline.ExportOptions.fromJson(it)) }
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
        val tpl = templateSettings
        // A template with no face blur: the editor's frames without face detection.
        if (tpl?.blurOff == true) {
            startTool(null, tpl)
            return
        }
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
                    onPreview = { frame, dets, acc, stats -> showLive(frame, dets.filter { it.score >= 0.5f }.map { it.box }, acc == "gpu", stats) },
                ) { p -> state = UiState.Working(info, 1, p, auto = auto) }
                val session = EditSession(info, settings, analysis, SystemClock.elapsedRealtime() - t0)
                if (info.durationMs > 0) prefs.analysisRatio = session.analysisMs.toFloat() / info.durationMs
                val ctrl = EditorController(session, editorScope, getApplication(), models, prefs)
                editor = ctrl
                beginProject(ctrl)
                val processing = tpl?.takeIf { it.hasProcessing }
                if (review) {
                    state = UiState.Editing(info, ctrl)
                    if (processing != null) applyTemplateAsync(ctrl, processing)
                } else {
                    if (processing != null) {
                        state = UiState.Working(info, 4, 0f, auto = true)
                        TemplateRunner.applyProcessing(ctrl, processing)
                    }
                    if (session.model.tracks.isEmpty() && processing == null) {
                        state = UiState.NoFaces(info, canEdit = true)
                    } else {
                        render(ctrl, auto = true)
                    }
                }
            }
        }
    }

    /** Subtitles without blurring: the editor on the video's frames, no face detection. */
    fun startSubtitles() = startTool(QuickTool.SUBTITLES)

    /**
     * The editor on the video's frames, without face detection, open on [tool] (none: no panel).
     * With a [template] (from the Ready screen) its processing is applied; with review off, and
     * no tool, the video is then saved without opening the editor.
     */
    fun startTool(tool: QuickTool?, template: TemplateSettings? = null) {
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
                    QuickTool.MP3, null -> Unit
                }
                editor = ctrl
                beginProject(ctrl)
                val processing = template?.takeIf { it.hasProcessing }
                if (template != null && tool == null && !review) {
                    if (processing != null) {
                        state = UiState.Working(info, 4, 0f, subsOnly = true, auto = true)
                        TemplateRunner.applyProcessing(ctrl, processing)
                    }
                    render(ctrl, auto = true)
                } else {
                    state = UiState.Editing(info, ctrl)
                    if (processing != null) applyTemplateAsync(ctrl, processing)
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
        } catch (e: Exception) {
            android.util.Log.w("FaceBlur", "processing failed", e)
            state = UiState.Failed(info, ProcessingErrors.friendly(e), ProcessingErrors.technical(e), canEdit = editor != null)
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
