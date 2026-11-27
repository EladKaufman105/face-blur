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
import com.elad.faceblur.pipeline.Watermark
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
import com.elad.faceblur.template.blankTemplate
import com.elad.faceblur.template.fitSpeechModel
import com.elad.faceblur.batch.BatchOwnFiles
import com.elad.faceblur.batch.Batch
import com.elad.faceblur.batch.BatchItem
import com.elad.faceblur.batch.BatchMath
import com.elad.faceblur.batch.BatchService
import com.elad.faceblur.batch.BatchState
import com.elad.faceblur.template.toTemplate
import org.json.JSONObject
import com.elad.faceblur.analysis.AnalysisStats
import com.elad.faceblur.analysis.MaskStore
import com.elad.faceblur.analysis.StatsHistory
import com.elad.faceblur.analysis.StatsSnapshot
import com.elad.faceblur.effects.Background
import java.io.File
import java.util.concurrent.Executors

/** Where a quick tool on the home screen takes the video: the editor, without face detection. */
enum class QuickTool { SUBTITLES, BACKGROUND, SOUND, MP3 }

/**
 * What the progress screen shows: a small copy of the frame being processed, and faces found on it.
 * @param stats how the analysis is doing (where detection runs, times), shown on a tap.
 */
class LivePreview(val frame: android.graphics.Bitmap, val faces: List<com.elad.faceblur.analysis.Box>, val gpu: Boolean = false, val stats: AnalysisStats? = null)

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
    data class Done(val info: VideoInfo, val result: Outcome.Saved, val canEdit: Boolean, val subsOnly: Boolean = false, /** The faces were found with fast detection: its warning stays on the result. */ val fast: Boolean = false, /** The same for selfie detection. */ val selfie: Boolean = false) : UiState
    /** [note]: what the template's processing could not do (shown beside the message). */
    data class NoFaces(val info: VideoInfo, val canEdit: Boolean, val note: String? = null, /** Found with fast detection: its warning shows here too. */ val fast: Boolean = false, /** The same for selfie detection. */ val selfie: Boolean = false) : UiState
    /** A saved project whose video can't be read any more: pick it again. [wrong]: the last pick was another video. */
    data class Relink(val project: ProjectMeta, val wrong: Boolean = false) : UiState
    /**
     * [message]: what happened, in words to act on; [details]: the technical part, for a report;
     * [canEdit]: the editor is still there to go back to (an export that failed).
     */
    data class Failed(val info: VideoInfo?, val message: String, val details: String? = null, val canEdit: Boolean = false, val retry: Boolean = true) : UiState
    /** A video's sound saved as an MP3. */
    data class AudioDone(val info: VideoInfo, val result: com.elad.faceblur.audio.Mp3Export.Result, val canEdit: Boolean = false) : UiState
    /** Several videos picked for a batch: choose the template and check the time and space before starting. */
    data class BatchSetup(val videos: List<VideoInfo>) : UiState
    /** The batch's queue (and, when it's done, its results): the batch itself is in [BatchState]. */
    data object BatchRun : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private var stateValue by mutableStateOf<UiState>(UiState.Empty)
    var state: UiState
        get() = stateValue
        private set(v) {
            stateValue = v
            // A template saved while a video was open is taken again once the Ready screen is back.
            if (v is UiState.Ready) refreshStaleTemplate()
        }

    var mode by mutableStateOf(BlurMode.BLUR)
    var strength by mutableStateOf(0.6f)
    var padding by mutableStateOf(1.6f)
    var sensitive by mutableStateOf(false)
    /** Fast detection (5 a second); off whenever [sensitive] is on (see [detectMode]). */
    var fast by mutableStateOf(false)
    /** Selfie detection (BlazeFace on every frame); off whenever [sensitive] or [fast] is on (see [detectMode]). */
    var selfie by mutableStateOf(false)
    /** 0..1: more faces blurred, never fewer (see [com.elad.faceblur.analysis.DetectTuning]). */
    var sensitivity by mutableStateOf(0f)

    /** Normal, extended, fast or selfie: one of four, set together. */
    var detectMode: com.elad.faceblur.analysis.DetectMode
        get() = com.elad.faceblur.analysis.DetectMode.of(sensitive, fast, selfie)
        set(v) {
            sensitive = v == com.elad.faceblur.analysis.DetectMode.EXTENDED
            fast = v == com.elad.faceblur.analysis.DetectMode.FAST
            selfie = v == com.elad.faceblur.analysis.DetectMode.SELFIE
        }
    /** Open the editor between analysis and export. */
    var review by mutableStateOf(true)

    /** Language, theme and export choices (persisted). */
    val prefs = AppPrefs(app)

    /** On-device speech models for subtitles. */
    val models = ModelManager(app)

    /** Every downloadable model, checked; and their downloads (only ever after a tap). */
    val modelStore = com.elad.faceblur.models.ModelStore.get(app)
    val downloads = com.elad.faceblur.models.ModelDownloads.get(app)

    /** The pack is installed and usable (reads the screens' state, so composables recompose). */
    fun packReady(id: com.elad.faceblur.models.PackId): Boolean = modelStore.statuses[id]?.usable == true

    /** The user's templates (and the built-in looks, in [BuiltInTemplates]). */
    val templates = TemplateStore(app)

    /** What is chosen for the next video: the user's template or a built-in look (none: nothing). */
    var templateChoice by mutableStateOf(TemplateChoice())
        private set

    /** What the chosen templates set, to apply to the next video (null: no template chosen). */
    var templateSettings by mutableStateOf<TemplateSettings?>(null)
        private set
    /** The settings before any template was chosen (a template lays over these). */
    private var templateBase: TemplateSettings? = null

    /**
     * The export options of this video alone (a chosen template's, or a reopened project's). Null:
     * the user's own, in [prefs]. A template never writes to [prefs]: later videos stay as they were.
     */
    var sessionExport by mutableStateOf<com.elad.faceblur.pipeline.ExportOptions?>(null)
        private set
    /** The subtitle look of this video alone (a chosen template's); null: the user's own, in [prefs]. */
    var sessionSubStyle by mutableStateOf<SubtitleStyle?>(null)
        private set

    /** The export options in use now. */
    val exportOptions: com.elad.faceblur.pipeline.ExportOptions get() = sessionExport ?: prefs.export
    /** The subtitle look in use now. */
    val subtitleStyle: SubtitleStyle get() = sessionSubStyle ?: prefs.subStyle

    /** A change to the export options: kept for this video if a template or project set them, else remembered. */
    fun changeExport(v: com.elad.faceblur.pipeline.ExportOptions) {
        if (sessionExport != null) sessionExport = v else prefs.updateExport(v)
    }

    /** A change to the subtitle look: kept for this video if a template set it, else remembered. */
    fun changeSubStyle(v: SubtitleStyle) {
        if (sessionSubStyle != null) sessionSubStyle = v else prefs.updateSubStyle(v)
    }

    /** A batch is being processed: it owns the temp files and the models, so a single video waits. */
    val batchRunning: Boolean get() = BatchState.current?.finished == false

    /** The id of the batch being processed now (its folder is locked), or null. */
    val runningBatchId: String? get() = BatchState.current?.takeIf { !it.finished }?.id

    /** True (with a notice) when [batchId] is the batch being processed: its projects can't be renamed or deleted yet. */
    private fun lockedByBatch(batchId: String?): Boolean {
        if (batchId == null || batchId != runningBatchId) return false
        android.widget.Toast.makeText(getApplication(), t("אי אפשר לשנות שם או למחוק בזמן שהעיבוד רץ"), android.widget.Toast.LENGTH_LONG).show()
        return true
    }

    /** True (with a notice) while a batch runs: starting any other processing waits for it. */
    fun blockedByBatch(): Boolean {
        if (!batchRunning) return false
        android.widget.Toast.makeText(getApplication(), t("אצווה רצה כרגע. אפשר להמשיך כשהיא תסתיים."), android.widget.Toast.LENGTH_LONG).show()
        return true
    }

    /** Forgets the template chosen for the last video: its blur look comes back, its export and subtitle look go. */
    private fun resetTemplate() {
        templateBase?.let { b -> mode = b.mode; strength = b.strength; padding = b.padding; sensitive = b.sensitive; fast = b.fast; selfie = b.selfie; sensitivity = b.sensitivity }
        templateBase = null
        templateChoice = TemplateChoice()
        templateSettings = null
        subsOverride = null
        sessionExport = null
        sessionSubStyle = null
    }

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

    /** How the export is doing (the progress screen's stats panel); null until it knows what it is making. */
    var exportStats by mutableStateOf<com.elad.faceblur.pipeline.ExportStats?>(null)
        private set

    /** [snapshot] goes into [history] (one per analysis run) on the main thread. */
    private fun showLive(frame: android.graphics.Bitmap, faces: List<com.elad.faceblur.analysis.Box>, gpu: Boolean = false, snapshot: StatsSnapshot? = null, history: StatsHistory? = null) {
        main.post {
            if (state is UiState.Working) {
                val stats = if (snapshot != null && history != null) history.add(snapshot) else null
                livePreview = LivePreview(frame, faces, gpu, stats)
            }
        }
    }

    init {
        refreshProjects()
        // Nothing is downloaded here: every model comes after the user's tap (first-launch sheet,
        // a feature's "download to use" card, or Settings › Models).
    }

    // Only the newest listing is shown: an older one finishing late (say, from before a rename)
    // must not overwrite it.
    private var listing = 0

    fun refreshProjects() {
        val n = ++listing
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { ProjectStore.list(getApplication()) }
            if (n == listing) projects = list
            // Saved before project.json kept what the list shows: kept now (not the open one, its
            // saves keep it), so listing doesn't read their edits again.
            val app = getApplication<Application>()
            for (p in list) {
                if (!p.countsFromEdits || p.id == projectId) continue
                saver.execute { if (p.id != projectId) runCatching { ProjectStore.keepCounts(app, p.id) } }
            }
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

    private fun idsOf(choice: TemplateChoice): List<String> = listOfNotNull(choice.id)

    /** The user's own say on creating subtitles for the template chosen (null: as the template says). Reset when another is chosen. */
    private var batchSubsOverride: Boolean? = null
    private var subsOverride: Boolean? = null

    private fun TemplateSettings.withSubs(o: Boolean?) = if (o == null) this else copy(createSubs = o)

    /**
     * With subtitles to create and the template's speech model not on the phone but the other one
     * there, the other one is used when it can do the job (see [fitSpeechModel]), instead of holding
     * "blur and save directly" back for a download.
     */
    private fun TemplateSettings.fitted(): TemplateSettings = fitSpeechModel(this, models::isReady)

    /** Turns subtitle creation on or off for the batch being set up (the template had it the other way). */
    fun setBatchSubtitles(on: Boolean) {
        batchSubsOverride = on
        recomputeBatch()
    }

    /** Turns subtitle creation on or off for the next video (the template had it the other way). */
    fun setTemplateSubtitles(on: Boolean) {
        subsOverride = on
        templateSettings = templateSettings?.copy(createSubs = on)?.fitted()
    }

    /** The settings a template is laid over (for the previews): before any template was chosen. */
    fun previewBase(): TemplateSettings = templateBase ?: currentSettings()

    private fun recomputeBatch() {
        // A batch never inherits the weaker detections (fast, selfie) chosen for one video: only a template can make a batch use them.
        val base = previewBase().copy(fast = false, selfie = false)
        val notes = ArrayList<String>()
        val chosen = idsOf(batchChoice).mapNotNull { templates.find(it) }.map { templates.usable(it, notes) }
        batchSettings = applyAll(base, chosen).withSubs(batchSubsOverride).fitted()
        batchNotes = notes
    }

    fun chooseBatchTemplates(choice: TemplateChoice) {
        if (choice != batchChoice) batchSubsOverride = null
        batchChoice = choice
        recomputeBatch()
    }

    /** Videos picked from the gallery for a batch (up to [BatchMath.MAX_VIDEOS] in all; more can be added on the setup screen). */
    fun pickedForBatch(uris: List<Uri>) {
        if (uris.isEmpty() || state is UiState.Working || blockedByBatch()) return
        val existing = (state as? UiState.BatchSetup)?.videos.orEmpty()
        if (existing.isEmpty()) {
            closeSession()
            resetTemplate()
            batchChoice = TemplateChoice()
            batchSubsOverride = null
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

    /** The packs the batch needs and doesn't have (it doesn't start without them). */
    val batchMissingPacks: List<com.elad.faceblur.models.PackId> get() = batchSettings.neededPacks.filter { !packReady(it) }

    /**
     * The packs the next video needs and doesn't have: the faces unless the template has no face
     * blur, and what the template's processing needs (people background, subtitles).
     */
    val missingPacks: List<com.elad.faceblur.models.PackId>
        // Selfie mode's pack follows the mode chosen on the screen (the template's choice may have been changed there).
        get() = ((templateSettings?.neededPacks ?: listOf(com.elad.faceblur.models.PackId.FACES))
            .filter { it != com.elad.faceblur.models.PackId.SELFIE && !(selfieWanted && it == com.elad.faceblur.models.PackId.FACES) } +
            (if (selfieWanted) listOf(com.elad.faceblur.models.PackId.SELFIE) else emptyList())).filter { !packReady(it) }

    /** Selfie detection is chosen and the video's face blur uses it. */
    private val selfieWanted: Boolean
        get() = detectMode == com.elad.faceblur.analysis.DetectMode.SELFIE && templateSettings?.blurOff != true

    /** Selfie detection can't run: its pack is missing. */
    val selfieMissing: Boolean get() = selfieWanted && !packReady(com.elad.faceblur.models.PackId.SELFIE)

    /** The next video's template needs a model that isn't on the phone beside the faces (subtitles, people). */
    val templateNeedsModel: Boolean get() = missingPacks.any { it != com.elad.faceblur.models.PackId.FACES }

    /** Face blur can't run: its pack is missing (and the template, if any, blurs faces). */
    val facesMissing: Boolean get() = templateSettings?.blurOff != true && detectMode != com.elad.faceblur.analysis.DetectMode.SELFIE && !packReady(com.elad.faceblur.models.PackId.FACES)

    /** Starts the batch in a foreground service (it goes on with the screen off) and shows its queue. */
    fun startBatch() {
        val setup = state as? UiState.BatchSetup ?: return
        if (blockedByBatch()) return
        // Never a batch with face blur but no face model (it would fail on every video), or without a model it needs.
        if (batchSettings.neededPacks.any { !modelStore.isUsable(it) }) return
        val items = setup.videos.mapIndexed { i, v ->
            val thumb = v.thumbnail?.let { b ->
                val k = 320f / maxOf(b.width, b.height, 1)
                if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
            }
            BatchItem(i, v.uri, v.name, v.durationMs, thumb)
        }
        val name = idsOf(batchChoice).mapNotNull { templates.find(it) }.joinToString(" + ") { if (it.builtIn) t(it.name) else it.name }
        val batchId = com.elad.faceblur.project.ProjectStore.newId()
        // The batch keeps its own copy of the template's logo and picture: editing the template meanwhile changes nothing in it.
        val s = runCatching {
            BatchOwnFiles.own(batchSettings, BatchOwnFiles.dir(getApplication<Application>().filesDir), batchId)
        }.getOrDefault(batchSettings)
        BatchState.current = Batch(items, s, name.ifEmpty { t("בלי תבנית") }, batchId).also { it.speeds = batchSpeeds() }
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
        if (blockedByBatch()) return
        item.projectId?.let { openProject(it) }
    }

    // ------------------------------------------------------------------ templates

    /** The template open in the full-screen editor (null: none). */
    var editingTemplate by mutableStateOf<String?>(null)
        private set

    fun editTemplate(id: String) {
        newTemplateDraft = null
        editingTemplate = id
    }

    /** A new template open in the editor, not saved yet (null: none). It is kept only when the editor saves it. */
    var newTemplateDraft by mutableStateOf<Template?>(null)
        private set

    /** "New template" (Settings): the editor opens on a template from scratch (the default settings). */
    fun startNewTemplate() {
        editingTemplate = null
        newTemplateDraft = blankTemplate(templates.newId(), t("תבנית חדשה"))
    }

    fun closeTemplateEditor() {
        editingTemplate = null
        newTemplateDraft = null
    }

    /** A template was saved or restored: if it is the one chosen, its settings are taken again. */
    fun templateSaved(tpl: Template) {
        if (batchChoice.id == tpl.id) chooseBatchTemplates(batchChoice)
        if (templateChoice.id != tpl.id) return
        // Back on the Ready screen after a cancelled automatic run, the run's project is still open but
        // can't be reached from here: save and close it (with its own export), so the template applies again.
        if (stateValue is UiState.Ready && (editor != null || projectId != null)) closeSession()
        // A video that is open (working, in the editor, done) keeps its own export and settings: the template
        // is taken again only when the Ready screen is back.
        if (templateIsInUse()) templateStale = true else chooseTemplates(templateChoice)
    }

    private var templateStale = false

    private fun templateIsInUse() = projectId != null || editor != null || when (stateValue) {
        is UiState.Empty, is UiState.Loading, is UiState.Ready, is UiState.BatchSetup, is UiState.BatchRun -> false
        else -> true
    }

    private fun refreshStaleTemplate() {
        if (!templateStale || projectId != null || editor != null) return
        templateStale = false
        if (templateChoice.id != null) chooseTemplates(templateChoice)
    }

    /** The settings as they stand now (the Ready screen's), for a template to lay over. */
    private fun currentSettings() = TemplateSettings(
        mode = mode, strength = strength, padding = padding, sensitive = sensitive, fast = fast, selfie = selfie, sensitivity = sensitivity,
        translate = prefs.subsTranslate, subStyle = prefs.subStyle, export = prefs.export,
    )

    /**
     * Chooses the templates for the next video: the settings fill in from them (the blur's look,
     * the subtitle look, the export), and their processing runs after the analysis.
     */
    fun chooseTemplates(choice: TemplateChoice) {
        val templateChoiceBefore = templateChoice
        templateStale = false
        templateChoice = choice
        val ids = idsOf(choice)
        if (ids.isEmpty()) {
            // Back to what it was before.
            resetTemplate()
            return
        }
        val base = templateBase ?: currentSettings().also { templateBase = it }
        val notes = ArrayList<String>()
        val chosen = ids.mapNotNull { templates.find(it) }.map { templates.usable(it, notes) }
        if (choice != templateChoiceBefore) subsOverride = null
        val s = applyAll(base, chosen).withSubs(subsOverride).fitted()
        applyToScreen(s)
        templateSettings = s
    }

    private fun applyToScreen(s: TemplateSettings) {
        mode = s.mode
        strength = s.strength
        padding = s.padding
        sensitive = s.sensitive
        fast = s.fast && !s.sensitive
        selfie = s.selfie && !s.sensitive && !s.fast
        sensitivity = s.sensitivity
        // Only for this video: the user's own export options and subtitle look stay as they are.
        sessionExport = s.export
        sessionSubStyle = s.subStyle
    }

    /** The editor's current choices as settings (for "Save as template"). */
    fun settingsOf(ctrl: EditorController): TemplateSettings {
        val st = ctrl.session.settings
        val model = if (models.isReady(SpeechModel.HEBREW) || !models.isReady(SpeechModel.MULTILINGUAL)) SpeechModel.HEBREW else SpeechModel.MULTILINGUAL
        return TemplateSettings(
            blurOff = ctrl.session.subtitlesOnly,
            mode = st.mode, strength = st.strength, padding = st.padding, sensitive = st.sensitive, fast = st.fast, selfie = st.selfie, sensitivity = st.sensitivity,
            createSubs = ctrl.model.cues.isNotEmpty(),
            speechModel = model, language = model.language,
            translate = ctrl.translateOnCreate,
            subStyle = subtitleStyle,
            split = ctrl.split,
            audio = ctrl.audio.copy(gainDb = 0f),
            cutSilences = ctrl.cutCount > 0, cutFillers = ctrl.cutCount > 0 && ctrl.model.cues.isNotEmpty(),
            background = ctrl.background,
            stabilize = ctrl.stabilizeLevel,
            export = exportOptions,
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

    /** Saves the editor's choices as the user's template [name], with the [keep] categories (the files are copied off the main thread). */
    fun saveEditorAsTemplate(ctrl: EditorController, name: String, keep: Set<TemplateCategory>) {
        val id = templates.newId()
        val tpl0 = settingsOf(ctrl).toTemplate(id, name.trim().take(60).ifBlank { t("תבנית חדשה") }, keep)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    var tpl = tpl0
                    tpl.background?.let { b -> tpl = tpl.copy(background = templates.withOwnImage(b, id)) }
                    tpl = templates.withOwnLogo(tpl)
                    templates.save(tpl)
                    tpl
                }.getOrNull()
            }
            ctrl.notice = if (saved != null) t("התבנית נשמרה") else t("התבנית לא נשמרה")
        }
    }

    /** Runs the chosen template's processing on the open editor session, not waiting (the editor is on screen). */
    private fun applyTemplateAsync(ctrl: EditorController, s: TemplateSettings) {
        editorScope.launch {
            ctrl.notice = t("מחיל את התבנית…")
            val notes = TemplateRunner.applyProcessing(ctrl, s)
            ctrl.notice = notes.first() ?: t("התבנית הוחלה")
        }
    }

    /** Every new editing session is saved as a project. */
    private suspend fun beginProject(ctrl: EditorController) {
        val s = ctrl.session
        val id = ProjectStore.newId()
        projectId = id
        val dir = ProjectStore.dir(getApplication(), id)
        s.projectDir = dir
        // A logo a template brought is copied into the project: it stays when the template changes or goes.
        sessionExport?.let { e ->
            if (e.watermark.logo != null) {
                sessionExport = withContext(Dispatchers.IO) {
                    runCatching { e.copy(watermark = Watermark.withOwnCopy(e.watermark, dir)) }.getOrDefault(e)
                }
            }
        }
        val json = ctrl.projectFields(subtitleStyle, sessionExport)
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

    /** "Find faces" ran in a subtitles-only project: its analysis is saved again, then the edits. */
    private fun persistFaces(ctrl: EditorController) {
        val id = projectId ?: return
        val app = getApplication<Application>()
        val analysis = ctrl.session.analysis
        val fields = ctrl.projectFields(subtitleStyle, sessionExport)
        val edits = ctrl.model.exportState()
        // The edits (with the blurs) first, then the new analysis and the "no longer subtitles only" flag:
        // a kill between the two leaves blurs that are saved, never a project that looks done but has none.
        saver.execute {
            runCatching {
                ProjectStore.save(app, id, fields, edits)
                ProjectStore.replaceAnalysis(app, id, analysis)
            }.onFailure { android.util.Log.w("ONIT", "project not saved", it) }
            main.post { if (state !is UiState.Editing) refreshProjects() }
        }
    }

    private fun startAutosave(ctrl: EditorController) {
        ctrl.onFacesFound = { persistFaces(ctrl) }
        autosave?.cancel()
        autosave = viewModelScope.launch {
            snapshotFlow { listOf(ctrl.version, ctrl.audio, ctrl.split, subtitleStyle, sessionExport, ctrl.background, ctrl.masksReady, ctrl.session.settings, ctrl.stabilizeLevel, ctrl.translateTo, ctrl.safeZoneOn, ctrl.safePlatform) }
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
        val fields = ctrl.projectFields(subtitleStyle, sessionExport)
        val edits = ctrl.model.exportState()
        val app = getApplication<Application>()
        saver.execute {
            runCatching { ProjectStore.save(app, id, fields, edits) }
                .onFailure { android.util.Log.w("ONIT", "project not saved", it) }
            main.post { if (state !is UiState.Editing) refreshProjects() }
        }
    }

    fun openProject(id: String) {
        if (state is UiState.Working || blockedByBatch()) return
        closeSession()
        resetTemplate()
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
                sj.optBoolean("fast"),
                sj.optDouble("sensitivity", 0.0).toFloat(),
                sj.optBoolean("selfie"),
            )
            val session = EditSession(info, settings, loaded.analysis, j.optLong("analysisMs"), j.optBoolean("subtitlesOnly"))
            loaded.edits?.let { session.model.restore(it) }
            val dir = ProjectStore.dir(app, id)
            session.projectDir = dir
            session.masks = withContext(Dispatchers.IO) { MaskStore.open(File(dir, "masks.bin"), File(dir, "masks.bin.idx")) }
            val ctrl = EditorController(session, editorScope, app, models, prefs)
            if (session.subtitlesOnly) ctrl.previewAvailable = false
            // A project made from a template (it has its own export) keeps the template's export and
            // subtitle look for this session only; the user's own settings stay as they are.
            val style = j.optString("style").takeIf { it.isNotBlank() }?.let { SubtitleStyle.fromJson(it) }
            val ownExport = j.optJSONObject("export")?.let { com.elad.faceblur.pipeline.ExportOptions.fromJson(it) }
            if (ownExport != null) {
                sessionExport = ownExport
                sessionSubStyle = style ?: prefs.subStyle
            } else {
                style?.let { prefs.updateSubStyle(it) }
            }
            val sp = j.optJSONObject("split")
            ctrl.restore(
                SplitRule(sp?.optInt("words") ?: 0, sp?.optInt("chars") ?: 0),
                AudioEdit.fromJson(j.optJSONObject("audio")),
                j.optInt("frame"),
                Background.fromJson(j.optJSONObject("background")),
                when (val st = j.opt("stabilize")) { is Boolean -> if (st) 2 else 0; is Number -> st.toInt(); else -> 0 },
            )
            ctrl.changeTranslateTo(j.optString("translateTo").takeIf { it.isNotBlank() })
            // The safe zone's view aid, as it was left with this project (older projects have none: off).
            j.optJSONObject("safeZone")?.let { z ->
                ctrl.safeZoneOn = z.optBoolean("on", false)
                runCatching { com.elad.faceblur.template.Platform.valueOf(z.optString("platform")) }.getOrNull()?.let { ctrl.safePlatform = it }
            }
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
        if (blockedByBatch()) return
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
        if (lockedByBatch(projects.firstOrNull { it.id == id }?.batchId)) return
        val app = getApplication<Application>()
        projects = projects.filter { it.id != id }
        saver.execute {
            ProjectStore.delete(app, id)
            main.post { refreshProjects() }
        }
    }

    /** Deletes a batch's folder: its projects (not the videos saved in the gallery). */
    fun deleteBatchFolder(batchId: String) {
        if (lockedByBatch(batchId)) return
        val app = getApplication<Application>()
        projects = projects.filter { it.batchId != batchId }
        saver.execute {
            runCatching { ProjectStore.deleteBatch(app, batchId) }
            main.post { refreshProjects() }
        }
    }

    fun renameBatchFolder(batchId: String, name: String) {
        if (lockedByBatch(batchId)) return
        val n = name.trim().take(80)
        val app = getApplication<Application>()
        saver.execute {
            runCatching { ProjectStore.renameBatch(app, batchId, n) }
            main.post { refreshProjects() }
        }
    }

    fun renameProject(id: String, name: String) {
        if (lockedByBatch(projects.firstOrNull { it.id == id }?.batchId)) return
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
        if (state is UiState.Working || blockedByBatch()) return
        closeSession()
        resetTemplate()
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
            guard(info, onCancel = { closeSession(); UiState.Ready(info) }) {
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
        if (blockedByBatch()) return
        val tpl = templateSettings
        // Face blur never starts without the whole face pack (the Ready screen offers the download).
        if (tpl?.blurOff != true && detectMode != com.elad.faceblur.analysis.DetectMode.SELFIE && !modelStore.isUsable(com.elad.faceblur.models.PackId.FACES)) {
            android.widget.Toast.makeText(getApplication(), t("כדי לטשטש פנים צריך את מודל זיהוי הפנים"), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Selfie detection never starts without its model, and never falls back to another detector.
        if (selfieMissing) {
            android.widget.Toast.makeText(getApplication(), t("כדי להשתמש במצב סלפי צריך את מודל הסלפי"), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // A template with no face blur: the editor's frames without face detection; with the editor
        // off ("save straight") the video is saved from there, not opened for editing.
        val route = com.elad.faceblur.template.StartRoute.of(tpl?.blurOff == true, save = !review)
        if (route == com.elad.faceblur.template.StartRoute.Route.EDIT_NO_BLUR || route == com.elad.faceblur.template.StartRoute.Route.SAVE_NO_BLUR) {
            startTool(null, tpl, save = route == com.elad.faceblur.template.StartRoute.Route.SAVE_NO_BLUR)
            return
        }
        closeSession()
        val settings = Settings(mode, strength, padding, sensitive, fast, sensitivity, selfie)
        val auto = !review
        livePreview = null
        state = UiState.Working(info, 1, 0f, auto = auto)
        job = viewModelScope.launch {
            guard(info, onCancel = { closeSession(); UiState.Ready(info) }) {
                val processor = FaceBlurProcessor(getApplication())
                val t0 = SystemClock.elapsedRealtime()
                // The stats panel's last minute: new for each run.
                val history = StatsHistory()
                val analysis = processor.analyze(
                    info, settings,
                    onPreview = { frame, dets, acc, snap -> showLive(frame, dets.filter { it.score >= 0.5f }.map { it.box }, acc == "gpu", snap, history) },
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
                    var failure: String? = null
                    if (processing != null) {
                        state = UiState.Working(info, 4, 0f, auto = true)
                        failure = TemplateRunner.applyProcessing(ctrl, processing).failures.firstOrNull()
                    }
                    // No faces and nothing the template really changed: no copy of the same video is saved.
                    val changed = tpl != null && (TemplateRunner.changesVideo(ctrl, tpl) || TemplateRunner.exportChangesVideo(getApplication(), tpl.export))
                    if (session.model.tracks.isEmpty() && !changed) {
                        state = UiState.NoFaces(info, canEdit = true, note = failure, fast = settings.fast, selfie = settings.selfie)
                    } else if (settings.fast || settings.selfie) {
                        // Fast and selfie detection may have missed a face: never saved unseen. The editor
                        // opens (its notice says why) and the video is saved from there, as the web app's check screen.
                        state = UiState.Editing(info, ctrl)
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
     * With a [template] (from the Ready screen) its processing is applied; with [save], and
     * no tool, the video is then saved without opening the editor.
     */
    fun startTool(tool: QuickTool?, template: TemplateSettings? = null, save: Boolean = false) {
        val info = currentInfo() ?: return
        if (blockedByBatch()) return
        closeSession()
        state = UiState.Working(info, 0, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { closeSession(); UiState.Ready(info) }) {
                val analysis = withContext(Dispatchers.IO) { FaceBlurProcessor.framesOnly(getApplication(), info) }
                val session = EditSession(info, Settings(mode, strength, padding, sensitive, fast, sensitivity, selfie), analysis, 0, subtitlesOnly = true)
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
                if (template != null && tool == null && save) {
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

    /** The export sheet's "sound only (MP3)": the editor's export saves the sound, with its edits, as an MP3. */
    var exportAudioOnly by mutableStateOf(false)

    fun export() {
        val ctrl = editor ?: return
        if (blockedByBatch()) return
        // While faces are being found the blurs are not all there yet: nothing is exported then.
        if (ctrl.findFacesProgress != null) return
        ctrl.pauser()
        ctrl.cancelTracking()
        ctrl.cancelSubtitles()
        val info = ctrl.session.info
        if (exportAudioOnly) {
            exportMp3(ctrl)
            return
        }
        // The people background has no masks (yet): saving would leave it out without a word.
        if (com.elad.faceblur.edit.MaskPolicy.exportBlocked(ctrl.background, ctrl.peopleMasksOk)) {
            ctrl.notice = t("הרקע של האנשים עדיין לא מוכן: קודם מזהים את האנשים בלשונית הרקע, או מכבים את הרקע.")
            return
        }
        state = UiState.Working(info, 2, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Editing(info, ctrl) }) { render(ctrl) }
        }
    }

    /**
     * The sound as an MP3 with the same edits as the video export (voice change, cuts, bleeps,
     * sound effects, cleanup). If they can't be applied the export fails: the original sound is
     * never saved instead (the voice change is for anonymity).
     */
    private fun exportMp3(ctrl: EditorController) {
        val info = ctrl.session.info
        state = UiState.Working(info, 3, 0f)
        job = viewModelScope.launch {
            guard(info, onCancel = { UiState.Editing(info, ctrl) }) {
                val audio = ctrl.audio
                val route = com.elad.faceblur.audio.Mp3Plan.route(audio, ctrl.model.bleeps.size, ctrl.model.sfx.size, !ctrl.model.cutMap().isEmpty)
                val base = info.name.substringBeforeLast('.') + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                fun progress(from: Float, span: Float) = { p: Float ->
                    viewModelScope.launch(Dispatchers.Main) { if (state is UiState.Working) state = UiState.Working(info, 3, from + span * p) }
                    Unit
                }
                val r = when (route) {
                    com.elad.faceblur.audio.Mp3Plan.Route.NO_SOUND -> throw com.elad.faceblur.pipeline.MutedSoundException()
                    com.elad.faceblur.audio.Mp3Plan.Route.RAW -> withContext(Dispatchers.IO) {
                        com.elad.faceblur.audio.Mp3Export.convert(getApplication(), info.uri, base, isActive = { isActive }, onProgress = progress(0f, 1f))
                    }
                    com.elad.faceblur.audio.Mp3Plan.Route.EDITED -> {
                        val edited = FaceBlurProcessor(getApplication()).renderAudio(info, ctrl.model, audio, progress(0f, 0.5f))
                        try {
                            withContext(Dispatchers.IO) {
                                com.elad.faceblur.audio.Mp3Export.convert(getApplication(), Uri.fromFile(edited), base, isActive = { isActive }, onProgress = progress(0.5f, 0.5f))
                            }
                        } finally {
                            edited.delete()
                        }
                    }
                }
                state = UiState.AudioDone(info, r, canEdit = true)
            }
        }
    }

    private suspend fun render(ctrl: EditorController, auto: Boolean = false) {
        val s = ctrl.session
        // Never save without a people background that is set up (also for templates and batches).
        if (com.elad.faceblur.edit.MaskPolicy.exportBlocked(ctrl.background, ctrl.peopleMasksOk)) throw com.elad.faceblur.pipeline.MissingMasksException()
        // What the screens after it need, read before the work starts.
        val subsOnly = s.subtitlesOnly
        if (!auto) livePreview = null
        exportStats = null
        val counters = com.elad.faceblur.pipeline.ExportCounters()
        val history = com.elad.faceblur.pipeline.ExportHistory()
        state = UiState.Working(s.info, 2, 0f, subsOnly, auto)
        saveProject(ctrl)
        val saved = FaceBlurProcessor(getApplication()).render(
            s.info, s.settings, s.analysis, s.model, s.analysisMs, exportOptions, subtitleStyle, ctrl.audio,
            ctrl.background, s.masks,
            stabilizer = ctrl.stabilizer,
            onPreview = { frame -> showLive(frame, emptyList()) },
            counters = counters,
        ) { p ->
            state = UiState.Working(s.info, 2, p, subsOnly, auto)
            // The progress comes about every half second: the counters are read with it.
            history.add(counters.snapshot(android.os.SystemClock.elapsedRealtime()))?.let { exportStats = it }
        }
        exportStats = null
        if (s.analysisMs > 0 && saved.renderMs > 0) prefs.renderRatio = saved.renderMs.toFloat() / s.analysisMs
        state = UiState.Done(s.info, saved, canEdit = true, subsOnly = subsOnly, fast = !subsOnly && s.settings.fast, selfie = !subsOnly && s.settings.selfie)
    }

    private suspend fun guard(info: VideoInfo, onCancel: () -> UiState, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            state = onCancel()
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FaceBlur", "processing failed", e)
            state = UiState.Failed(info, ProcessingErrors.friendly(e), ProcessingErrors.technical(e), canEdit = editor != null, retry = e !is com.elad.faceblur.pipeline.MissingMasksException)
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
        backToSettings()
        detectMode = com.elad.faceblur.analysis.DetectMode.EXTENDED
    }

    fun reset() {
        job?.cancel()
        closeSession()
        state = UiState.Empty
    }

    private fun closeSession() {
        exportAudioOnly = false
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
