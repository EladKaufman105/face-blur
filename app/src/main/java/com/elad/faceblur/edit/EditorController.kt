package com.elad.faceblur.edit

import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.i18n.t
import com.elad.faceblur.ui.ltr
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elad.faceblur.analysis.AnalysisResult
import com.elad.faceblur.analysis.Box
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.track.FaceTimeline
import com.elad.faceblur.subs.ModelManager
import com.elad.faceblur.prefs.AppPrefs
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubLanguages
import com.elad.faceblur.subs.SubtitleEngine
import com.elad.faceblur.subs.SpeakerDetector
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.VoiceDsp
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.analysis.MaskStore
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BackgroundState
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.audio.SfxLibrary
import com.elad.faceblur.audio.SfxPlay
import com.elad.faceblur.audio.SfxPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun AnalysisResult.toEditModel() = EditModel(times, cuts, motion, aspect, dets, fps, tracks)

/** One video between analysis and export: the analysis data and the user's edits. */
class EditSession(
    val info: VideoInfo,
    initialSettings: Settings,
    analysis: AnalysisResult,
    val analysisMs: Long,
    /** Opened for subtitles only: no face detection was run (until "Find faces" does). */
    subtitlesOnly: Boolean = false,
) : Closeable {
    var analysis: AnalysisResult = analysis
        private set
    var subtitlesOnly: Boolean = subtitlesOnly
        private set
    var model = analysis.toEditModel()
        private set

    /**
     * Face detection was run on a subtitles-only session: its blurs and frames come in, and
     * everything else edited so far (subtitles, bleeps, sounds, cuts, which are all kept in
     * milliseconds) stays. The undo history starts again.
     */
    fun adoptDetection(found: AnalysisResult) {
        val old = analysis
        model = EditModel.withDetection(model, found.toEditModel())
        analysis = found
        subtitlesOnly = false
        old.close()
    }

    /** The blur's look (style, strength, area): chosen before detection, changeable in the editor. */
    var settings by androidx.compose.runtime.mutableStateOf(initialSettings)

    /** The project's folder (masks and such are kept there), once it has one. */
    @Volatile var projectDir: java.io.File? = null

    /** Person masks for background removal, once found. */
    @Volatile var masks: MaskStore? = null

    override fun close() {
        analysis.close()
        masks?.close()
    }
}

/**
 * Editor UI state and actions over an [EditSession]. Lives in the view model so a running
 * "track" survives recomposition. All methods are called on the main thread.
 */
class EditorController(
    val session: EditSession,
    private val scope: CoroutineScope,
    private val appContext: Context? = null,
    /** Speech models for subtitles (null: subtitles unavailable). */
    val models: ModelManager? = null,
    private val prefs: AppPrefs? = null,
) {
    val model get() = session.model
    val padding get() = session.settings.padding
    val frameCount get() = model.frameCount

    /** Mirrors [EditModel.version]: read it to recompose on edits. */
    var version by mutableIntStateOf(0)
        private set
    var frame by mutableIntStateOf(0)
        private set
    var selectedId by mutableStateOf<Int?>(null)
        private set
    var addMode by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var trackingProgress by mutableStateOf<Float?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    /** Frame shown while dragging a clip's edge (the playhead itself stays put). */
    var peekFrame by mutableStateOf<Int?>(null)
        private set
    /** Show the real blur in the player (vs. outlines only). */
    var previewOn by mutableStateOf(true)
        private set
    var previewAvailable by mutableStateOf(true)
    /** Draw the platform's safe zone over the preview (a view aid for vertical frames; never in the export). */
    var safeZoneOn by mutableStateOf(false)
    /** The platform whose zone is drawn. */
    var safePlatform by mutableStateOf(com.elad.faceblur.template.Platform.TIKTOK)

    /**
     * How far the player's GPU effects were cut back after failing on this device: 0 all of them
     * (background, blur, steadying), 1 the live blur alone, 2 none (plain video and outlines).
     */
    var effectsLevel by mutableIntStateOf(0)

    /**
     * The tool panel that's open, if any: one at a time, so opening one closes the one before
     * (they never pile up). Each also has its own flag below, for the code that opens and closes it.
     */
    var panel by mutableStateOf<EditorPanel?>(null)
        private set
    private fun showPanel(p: EditorPanel, open: Boolean) {
        val was = panel
        panel = if (open) p else if (panel == p) null else panel
        if (was != panel) {
            if (was == EditorPanel.LIST) bleepMode = false
            if (was == EditorPanel.VOICE) SfxPlayer.stop()
        }
    }
    /** Opens [p] in place of whichever panel is open (a tool group's tab). */
    fun openPanel(p: EditorPanel) = showPanel(p, true)

    /** The frame panel (9:16, 1:1, 4:5, following the faces) is open. */
    var framePanel: Boolean
        get() = panel == EditorPanel.FRAME
        set(v) = showPanel(EditorPanel.FRAME, v)

    /** Closes whichever panel is open. */
    fun closePanel() {
        panel?.let { showPanel(it, false) }
    }

    /** Panels beside the video (a tablet): one stays open, so finishing a task doesn't close it. */
    var sidePanels = false

    /** The cuts panel (silences, filler words, cutting by the text) is open. */
    var cutPanel: Boolean
        get() = panel == EditorPanel.CUT
        set(v) = showPanel(EditorPanel.CUT, v)

    /** The watermark panel is open. */
    var watermarkPanel: Boolean
        get() = panel == EditorPanel.WATERMARK
        set(v) = showPanel(EditorPanel.WATERMARK, v)
    var sizePanel by mutableStateOf(false)
    var selectedCueId by mutableStateOf<Int?>(null)
        private set
    /** Subtitle generation stage and progress, while it runs. */
    var subsProgress by mutableStateOf<Pair<SubtitleEngine.Stage, Float>?>(null)
        private set
    /** The subtitles panel (make them, what to do with them, how they're exported). */
    var subsSheet: Boolean
        get() = panel == EditorPanel.SUBTITLES
        set(v) = showPanel(EditorPanel.SUBTITLES, v)
    var cueEditor by mutableStateOf(false)
    /** The subtitle look panel is open (in place of the timeline). */
    var stylePanel: Boolean
        get() = panel == EditorPanel.STYLE
        set(v) = showPanel(EditorPanel.STYLE, v)
    /** The list of all subtitles is open (in place of the timeline). */
    var listEditor: Boolean
        get() = panel == EditorPanel.LIST
        set(v) = showPanel(EditorPanel.LIST, v)
    /** The words / characters per subtitle choice is open above the tools. */
    var splitPanel by mutableStateOf(false)
    /** How the sentences are cut into subtitles now. */
    var split by mutableStateOf(SplitRule())
        private set
    /** Translate new subtitles when they're created (remembered). */
    var translateOnCreate by mutableStateOf(prefs?.subsTranslate ?: false)
        private set

    /** What subtitles are translated into: "he", "en", "ar" or "fa"; null: English (Hebrew for English speech). This session only. */
    var translateTo by mutableStateOf<String?>(null)
        private set
    private var pendingTextSnapshot = false
    private var subsJob: Job? = null

    /** Voice change and bleep settings. */
    var audio by mutableStateOf(AudioEdit())
        private set
    /** The voice panel is open (in place of the timeline). */
    var voicePanel: Boolean
        get() = panel == EditorPanel.VOICE
        set(v) = showPanel(EditorPanel.VOICE, v)
    /** The subtitle list shows words to tap and bleep (instead of text to edit). */
    var bleepMode by mutableStateOf(false)
    /** Bleeps and robot sound for the player, rebuilt on changes (read on the audio thread). */
    @Volatile var voiceDsp: VoiceDsp? = null
        private set
    private var dspVersion = -1
    /** Set by the player UI: applies [audio]'s pitch / mute to the player. */
    var voiceApplier: () -> Unit = {}

    /** Frame the player and the overlay show. */
    val shownFrame get() = peekFrame ?: frame

    /** Set by the player UI: shows frame [f]. */
    var seeker: (Int) -> Unit = {}
    /** Set by the player UI. */
    var pauser: () -> Unit = {}
    /** Set by the player UI. */
    var playToggler: () -> Unit = {}
    /** Set by the player UI: starts playing (never pauses), from where it is. */
    var starter: () -> Unit = {}
    /** Set by the player UI: re-renders the paused frame (after an edit, for the live blur). */
    var refresher: () -> Unit = {}

    /** Live blur for the editor player; set by the player UI. */
    var preview: PreviewSource? = null
        set(value) {
            field = value
            value?.enabled = previewOn
            rebuildPreview()
        }

    private var trackJob: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private var sliderGesture = false
    private var rebuildPending = false

    private fun sync() {
        version = model.version
        rebuildVoice()
        if (selectedId != null && model.track(selectedId!!) == null) selectedId = null
        if (selectedCueId != null && model.cue(selectedCueId!!) == null) selectedCueId = null
        if (selectedSfxId != null && model.sfxClip(selectedSfxId!!) == null) selectedSfxId = null
        schedulePreview()
    }

    private fun schedulePreview() {
        if (rebuildPending || preview == null) return
        rebuildPending = true
        main.postDelayed({
            rebuildPending = false
            rebuildPreview()
            if (!playing) refresher()
        }, 120)
    }

    /** The blur panel is open. */
    var blurPanel: Boolean
        get() = panel == EditorPanel.BLUR
        set(v) = showPanel(EditorPanel.BLUR, v)

    /** A new look for every blur: [mode], [strength] and the area around the face ([padding]). */
    fun setBlur(mode: BlurMode = session.settings.mode, strength: Float = session.settings.strength, padding: Float = session.settings.padding) {
        val s = session.settings
        if (mode == s.mode && strength == s.strength && padding == s.padding) return
        session.settings = Settings(mode, strength, padding, s.sensitive, s.fast, s.sensitivity)
        rebuildPreview()
        if (!playing) refresher()
    }

    private fun rebuildPreview() {
        val p = preview ?: return
        val a = session.analysis
        p.timeline = FaceTimeline.build(
            model.renderTracks(), a.times, a.cuts.map { a.times[it] }.toLongArray(),
            a.uprightW, a.uprightH, a.rotation, padding,
        )
    }

    fun togglePreview() {
        previewOn = !previewOn
        preview?.enabled = previewOn
        if (!playing) refresher()
    }

    val selected get() = selectedId?.let { model.track(it) }

    // ------------------------------------------------------------------ playback

    /**
     * From the player while it plays (or after a seek over a cut lands). A frame inside a cut starts
     * one seek past it (to the first kept frame, over cuts that touch, or a stop when the cut runs
     * to the end); until it lands, the frames still arriving from the cut start nothing and the
     * time shown is where playback is going, not the cut.
     */
    fun onFrameShown(f: Int) {
        if (!playing && !skips.pending) return
        val shown = f.coerceIn(0, frameCount - 1)
        if (handleCut(shown)) return
        skips.cleared()
        frame = shown
    }

    /**
     * From the playback clock: only meets a cut (seeks past it, or stops at the end). It never sets the
     * frame shown and never clears the guard, so it can't fight the frames the player reports.
     */
    fun checkCutAt(f: Int) {
        if (!playing && !skips.pending) return
        handleCut(f.coerceIn(0, frameCount - 1))
    }

    /** If frame [shown] is inside a cut, deals with it and returns true. */
    private fun handleCut(shown: Int): Boolean {
        if (model.removed.isEmpty()) return false
        val endMs = CutSkip.skipEndMs(model.removed, msAtRelUs(timeOf(shown)))
        if (endMs < 0) return false
        val to = keptFrameAfter(endMs)
        when (val step = skips.onCutFrame(to, frameCount, android.os.SystemClock.uptimeMillis())) {
            is CutSkipStep.Seek -> { frame = step.frame; seeker(step.frame) }
            CutSkipStep.Finish -> {
                frame = lastKeptFrame()
                pauser()
                seeker(frame)
            }
            CutSkipStep.None -> Unit
        }
        return true
    }

    /**
     * The first kept frame at or after [endMs]. A gap too short to hold a frame doesn't count: a cut that
     * starts at the frame found is jumped whole, one cut at a time. frameCount if the cut runs to the end.
     */
    private fun keptFrameAfter(endMs: Long): Int {
        var to = firstFrameFrom(cueRelUs(endMs))
        while (to < frameCount) {
            val e = CutSkip.skipEndMs(model.removed, msAtRelUs(timeOf(to)))
            if (e < 0) break
            to = firstFrameFrom(cueRelUs(e))
        }
        return to
    }

    /** Ends a jump over a cut (the player was paused or sought by the user). */
    fun cancelSkip() = skips.cleared()

    /** The frame to play from: the current one, or the first kept one after the cut it is paused in. */
    fun resumeFrame(): Int {
        if (!frameCut(frame)) return frame
        val endMs = CutSkip.skipEndMs(model.removed, msAtRelUs(timeOf(frame)))
        val to = keptFrameAfter(endMs)
        return if (to >= frameCount) firstKeptFrame() else to
    }
    private val skips = CutSkipGuard()

    /** A jump over a cut is under way (the player may report "not playing" for a moment while it buffers). */
    val skipPending: Boolean get() = skips.pending

    /** Whether the frame [f] is inside a cut. */
    private fun frameCut(f: Int): Boolean = model.removed.isNotEmpty() && model.cutAt(msAtRelUs(timeOf(f))) != null

    /** The last frame that isn't cut (the last one when nothing at the end is cut). */
    fun lastKeptFrame(): Int {
        var f = frameCount - 1
        while (f > 0 && frameCut(f)) f--
        return f.coerceAtLeast(0)
    }

    /** The first frame that isn't cut (where playing from the start begins). */
    fun firstKeptFrame(): Int {
        var f = 0
        while (f < frameCount - 1 && frameCut(f)) f++
        return f
    }
    /** The first frame at or after [us] (from the first frame); frameCount if none. */
    fun firstFrameFrom(us: Long): Int {
        val t = model.times
        if (t.isEmpty()) return 0
        val target = t[0] + us
        var lo = 0
        var hi = t.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (t[m] < target) lo = m + 1 else hi = m
        }
        return lo
    }

    fun seek(f: Int) {
        // A seek of the user's own ends any jump over a cut.
        skips.cleared()
        val c = f.coerceIn(0, frameCount - 1)
        if (c == frame && peekFrame == null) return
        frame = c
        seeker(c)
    }

    /** Frame index at a time (µs from the first frame), nearest. */
    fun frameAt(us: Long): Int {
        val t = model.times
        if (t.isEmpty()) return 0
        val target = t[0] + us
        var lo = 0
        var hi = t.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= target) lo = mid else hi = mid
        }
        return if (kotlin.math.abs(t[hi] - target) < kotlin.math.abs(t[lo] - target)) hi else lo
    }

    /** Time of a frame in µs from the first frame. */
    fun timeOf(f: Int): Long {
        val t = model.times
        if (t.isEmpty()) return 0
        return t[f.coerceIn(0, t.size - 1)] - t[0]
    }

    val frameUs: Long get() = (1_000_000f / max(1f, model.fps)).toLong()

    fun step(d: Int) {
        if (playing) pauser()
        seek(frame + d)
    }

    fun select(id: Int?) {
        selectedId = id
        if (id != null) selectedCueId = null
    }

    /** Select a blur and, if the playhead isn't on it, jump to its first frame. */
    fun focus(id: Int) {
        selectedId = id
        selectedCueId = null
        selectedSfxId = null
        val t = model.track(id) ?: return
        if (frame !in t.start..t.end) seek(t.start)
    }

    // ------------------------------------------------------------------ edits

    fun undo() {
        if (trackingProgress != null) return
        model.undo()
        sync()
    }

    fun redo() {
        if (trackingProgress != null) return
        model.redo()
        sync()
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        model.delete(id)
        selectedId = null
        sync()
    }

    fun splitSelected() {
        val id = selectedId ?: return
        val t = model.track(id) ?: return
        if (frame <= t.start || frame > t.end) {
            notice = t("כדי לפצל, הזז את הסמן לתוך טווח הטשטוש")
            return
        }
        model.split(id, frame)?.let { selectedId = it }
        sync()
    }

    fun startHere() {
        val id = selectedId ?: return
        model.setStart(id, frame)
        sync()
    }

    fun endHere() {
        val id = selectedId ?: return
        model.setEnd(id, frame)
        sync()
    }

    /** Dragging a clip edge on the timeline: one undo step per drag. */
    fun beginTrim() {
        model.beginGesture()
    }

    /** @param start true for the clip's start edge. Shows the edge's frame in the player. */
    fun trimTo(id: Int, start: Boolean, f: Int) {
        val c = f.coerceIn(0, frameCount - 1)
        if (start) model.setStart(id, c, undoable = false) else model.setEnd(id, c, undoable = false)
        val t = model.track(id) ?: return
        val shown = if (start) t.start else t.end
        if (peekFrame != shown) {
            peekFrame = shown
            seeker(shown)
        }
        sync()
    }

    fun endCueTrim() = endTrim()

    /** The selected blur now starts (or ends) at the playhead. */
    fun setSelectedEdgeHere(start: Boolean) {
        val id = selectedId ?: return
        if (start) model.setStart(id, frame) else model.setEnd(id, frame)
        sync()
    }

    /** The selected subtitle now starts (or ends) at the playhead. */
    fun setSelectedCueEdgeHere(start: Boolean) {
        val id = selectedCueId ?: return
        model.beginGesture()
        model.setCueEdge(id, start, nowMs)
        sync()
    }

    fun endTrim() {
        if (peekFrame != null) {
            peekFrame = null
            seeker(frame)
        }
    }

    /** At the start of a drag / pinch: false if the selected blur isn't on this frame. */
    fun beginGesture(): Boolean {
        val t = selected ?: return false
        if (frame !in t.start..t.end) return false
        model.beginGesture()
        return true
    }

    fun moveSelected(dx: Float, dy: Float) {
        val id = selectedId ?: return
        model.moveBy(id, frame, dx, dy)
        sync()
    }

    fun deselect() {
        selectedId = null
        selectedCueId = null
        selectedSfxId = null
        sizePanel = false
        sfxGainPanel = false
    }

    // ------------------------------------------------------------------ subtitles

    val selectedCue get() = selectedCueId?.let { model.cue(it) }

    /** Timeline µs (from the first frame) of a media time in ms, and back. */
    fun cueRelUs(ms: Long): Long = ms * 1000 - (model.times.firstOrNull() ?: 0L)
    fun msAtRelUs(us: Long): Long = (us + (model.times.firstOrNull() ?: 0L)) / 1000

    /** Media time (ms) of the playhead. */
    val nowMs get() = msAtRelUs(timeOf(shownFrame))

    fun selectCue(id: Int?) {
        selectedCueId = id
        if (id != null) {
            selectedId = null
            selectedSfxId = null
        }
        sizePanel = false
    }

    fun focusCue(id: Int) {
        selectCue(id)
        val c = model.cue(id) ?: return
        if (nowMs !in c.startMs until c.endMs) seek(frameAt(cueRelUs(c.startMs)))
    }

    fun deleteSelectedCue() {
        val id = selectedCueId ?: return
        model.deleteCue(id)
        selectedCueId = null
        sync()
    }

    fun editSelectedCue(he: String, en: String) {
        val id = selectedCueId ?: return
        model.editCue(id, he, en)
        sync()
    }

    /** From the text editor: the spoken-language text and the translation. */
    fun editSelectedCueTexts(spoken: String, translation: String) {
        val id = selectedCueId ?: return
        model.editCueTexts(id, spoken, translation)
        sync()
    }

    /** A new, empty subtitle at the playhead, opened for typing (in a dialog, or in the list). */
    fun addCueHere(dialog: Boolean = true): Int {
        // Into the free time at the playhead (after the subtitle that's there, if any).
        val id = model.addCueInGap(nowMs, msAtRelUs(timeOf(frameCount - 1) + frameUs))
        if (id < 0) {
            notice = t("אין כאן מקום לכתובית חדשה. אפשר לפצל כתובית קיימת.")
            return -1
        }
        // In the language most of the subtitles are in.
        model.cue(id)?.src = model.cues.filter { it.src != "" }.groupingBy { it.src }.eachCount().maxByOrNull { it.value }?.key ?: "he"
        sync()
        selectCue(id)
        if (dialog) cueEditor = true
        return id
    }

    fun beginCueTrim() {
        model.beginGesture()
    }

    fun trimCueTo(id: Int, start: Boolean, relUs: Long) {
        model.setCueEdge(id, start, msAtRelUs(relUs))
        val c = model.cue(id) ?: return
        val f = frameAt(cueRelUs(if (start) c.startMs else c.endMs - 1))
        if (peekFrame != f) {
            peekFrame = f
            seeker(f)
        }
        sync()
    }

    /**
     * Transcribes the video with [m], once it is downloaded (the panel shows the model's download
     * card until then). [lang]: the spoken language the person picked for the multilingual model
     * ("he", "ar", "en", "fa"; null or "auto": detect it); the Hebrew model is always Hebrew.
     */
    fun makeSubtitles(m: SpeechModel, lang: String? = null) {
        val mm = models ?: return
        if (subsJob?.isActive == true) return
        if (mm.isReady(m)) generateSubtitles(m, lang)
    }

    /** True while subtitles or speakers are being made (a model download goes on by itself). */
    val subsBusy get() = subsProgress != null || speakersProgress != null

    /**
     * Anything running in the background: subtitles, speakers, loudness, cuts, finding the
     * people, measuring the camera's motion.
     */
    val busy get() = findFacesProgress != null || subsBusy || loudnessBusy || cutBusy || segmentProgress != null || stabilizeProgress != null

    /** Suspends until the background work ([busy]) is done: for a template applied with no editor on screen. */
    suspend fun awaitIdle(timeoutMs: Long = IDLE_TIMEOUT_MS) {
        val start = android.os.SystemClock.elapsedRealtime()
        while (busy) {
            if (android.os.SystemClock.elapsedRealtime() - start > timeoutMs) {
                // Don't hang a batch forever: stop what runs and say so (a failure, not a cancellation).
                cancelSubtitles()
                cancelMotion()
                throw com.elad.faceblur.pipeline.WorkTimeoutException()
            }
            kotlinx.coroutines.delay(120)
        }
    }

    /** The app's context (null in tests that have none). */
    val context: Context? get() = appContext

    /** Whether new subtitles are translated, for this session only (a template's choice; not remembered). */
    fun setTranslateTransient(v: Boolean) {
        translateOnCreate = v
    }

    /** Transcribes the video with [m] (downloaded) and fills the subtitles. */
    fun generateSubtitles(m: SpeechModel, lang: String? = null) {
        val path = models?.file(m)?.takeIf { models.isReady(m) }?.absolutePath ?: return
        generateSubtitles(path, if (m == SpeechModel.HEBREW) m.language else SubLanguages.speechCode(lang))
    }

    fun generateSubtitles(modelPath: String, lang: String) {
        val ctx = appContext ?: return
        if (playing) pauser()
        subsJob?.cancel()
        subsProgress = SubtitleEngine.Stage.AUDIO to 0f
        // A downloaded speech model is held while it runs (not deleted or switched under it).
        val modelStore = com.elad.faceblur.models.ModelStore.get(ctx)
        val pack = modelStore.registry.packOf(java.io.File(modelPath).name)?.id
            ?.takeIf { java.io.File(modelPath).parentFile?.canonicalPath == modelStore.dir.canonicalPath }
        subsJob = scope.launch {
            var held = false
            try {
                if (pack != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { modelStore.acquire(pack) }
                    held = true
                }
                val r = SubtitleEngine.run(
                    ctx, session.info.uri, modelPath, lang, translate = false,
                    speed = models?.speed(modelPath) ?: 0.6f,
                    onSpeed = { v -> models?.learnSpeed(modelPath, v) },
                ) { st, p ->
                    main.post { if (subsProgress != null) subsProgress = st to p }
                }
                // Keep the transcription even if translating fails or is stopped.
                model.setSentences(r.lines.map { NewCue(it.startMs, it.endMs, it.he, it.en, it.src, it.words) })
                split = SplitRule()
                sync()
                if (r.lines.isEmpty()) {
                    notice = t("לא נמצא דיבור בסרטון")
                    return@launch
                }
                if (!sidePanels) subsSheet = false
                if (translateOnCreate) {
                    notice = t("נוצרו %d כתוביות. מתרגם…").format(r.lines.size)
                    translateMissing()
                } else {
                    notice = t("נוצרו %d כתוביות. אפשר לערוך כל אחת בהקשה.").format(r.lines.size)
                }
            } catch (e: SubtitleEngine.NoAudioException) {
                notice = t("אין קול בסרטון")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.elad.faceblur.models.ModelMissing) {
                notice = t("כדי ליצור כתוביות צריך את מודל הדיבור")
            } catch (e: Exception) {
                notice = t("יצירת הכתוביות נכשלה: %s").format(e.message ?: e.javaClass.simpleName)
            } finally {
                if (held && pack != null) modelStore.release(pack)
                subsProgress = null
                sync()
            }
        }
    }

    /** What needs translating now (see [EditModel.translationJobs]). */
    private fun jobs() = model.translationJobs(audio.maskWords)

    /** Sentences with a spoken text and no translation yet. */
    val untranslatedCount get() = jobs().count { !it.overwrites }

    /** Sentences whose translation is hidden over bleeped words (they translate again from the masked text). */
    val staleCount get() = jobs().count { it.overwrites }

    /** Whether [c]'s translation is hidden in the video: it may carry a bleeped word (see [EditModel.translationShown]). */
    fun translationHidden(c: Cue) = c.otherText.isNotBlank() && !model.translationShown(c, audio.maskWords)

    /** Translates the subtitles that have only one language (again, after a failure). */
    fun translateSubtitles() {
        if (subsJob?.isActive == true || jobs().isEmpty()) return
        subsJob = scope.launch {
            try {
                translateMissing()
            } finally {
                subsProgress = null
                sync()
            }
        }
    }

    /** Whole sentences translate better than pieces of them: one per group. */
    private suspend fun translateMissing() {
        val todo = jobs()
        if (todo.isEmpty()) return
        subsProgress = SubtitleEngine.Stage.TRANSLATE to 0f
        // Translations that were typed by hand and are hidden get replaced: one step brings them back.
        if (todo.any { it.overwrites }) model.beginTranslationBatch()
        try {
            SubtitleEngine.translateLines(
                todo.map { it.pair },
                todo.map { it.src },
                translateTo,
                { st, p -> main.post { if (subsProgress != null) subsProgress = st to p } },
            ) { i, he, en ->
                val job = todo[i]
                main.post {
                    if (job.pair.first.isBlank()) model.fillGroup(job.group, he, intoEnglish = false, source = job.source)
                    else model.fillGroup(job.group, en, intoEnglish = true, source = job.source)
                    sync()
                }
            }
            notice = t("הכתוביות מוכנות: %d. אפשר לערוך כל אחת בהקשה.").format(model.cues.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            notice = t("התרגום נעצר. אפשר להמשיך מתפריט הכתוביות.")
            throw e
        } catch (e: Exception) {
            notice = t("התרגום לא הצליח (צריך אינטרנט פעם אחת להורדת חבילת השפה). אפשר לנסות שוב מתפריט הכתוביות.")
            android.util.Log.w("FaceBlur", "translation failed", e)
        }
    }

    /** Re-cuts the subtitles (from their whole sentences, so nothing is lost). */
    /** Puts back a saved project's choices (no edits recorded). */
    fun restore(rule: SplitRule, audio: AudioEdit, frame: Int, background: Background = Background(), stabilize: Int = 0) {
        split = rule
        this.audio = audio
        if (stabilize > 0) chooseStabilize(stabilize)
        this.background = background
        bgState.background = background
        this.frame = frame.coerceIn(0, max(0, frameCount - 1))
        sync()
    }

    // ------------------------------------------------------------------ voice

    fun changeAudio(a: AudioEdit) {
        if (a == audio) return
        audio = a
        dspVersion = -1
        rebuildVoice()
        voiceApplier()
    }

    private fun rebuildVoice() {
        if (dspVersion == model.version) return
        dspVersion = model.version
        val b = model.bleeps.map { (x, y) -> longArrayOf(x, y) }
        // Only sounds already decoded; the others are decoded in the background, then this runs again.
        val ctx = appContext
        val missing = model.sfx.map { it.sound }.distinct().filter { SfxLibrary.peek(it) == null }
        if (ctx != null && missing.isNotEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) { for (id in missing) SfxLibrary.load(ctx, id) }
                dspVersion = -1
                rebuildVoice()
            }
        }
        val sfx = model.sfx.mapNotNull { c -> SfxLibrary.peek(c.sound)?.let { SfxPlay(c.startMs * 1000, it, c.gain) } }
        val cuts = model.removed.map { (x, y) -> longArrayOf(x, y) }
        voiceDsp = VoiceDsp(
            b, audio.voice, audio.tone, pitchAfter = audio.voice.pitch, sfx = sfx,
            denoise = audio.denoise, enhance = audio.enhance, gain = audio.gain, cuts = cuts,
        ).takeIf { it.active }
    }

    // ------------------------------------------------------------------ voice cleanup

    /** Measuring the loudness (for "social-media level"). */
    var loudnessBusy by mutableStateOf(false)
        private set

    /** 0 off, 1 light, 2 medium, 3 strong. */
    fun setDenoise(level: Int) = changeAudio(audio.copy(denoise = level.coerceIn(0, 3)))

    fun setEnhance(on: Boolean) = changeAudio(audio.copy(enhance = on))

    /** Turns the loudness levelling on (measuring the video once) or off. */
    fun setLoud(on: Boolean) {
        if (!on) {
            changeAudio(audio.copy(loud = false))
            return
        }
        if (audio.gainDb != 0f) {
            changeAudio(audio.copy(loud = true))
            return
        }
        val ctx = appContext ?: return
        if (loudnessBusy) return
        loudnessBusy = true
        scope.launch {
            try {
                val (pcm, ch) = withContext(Dispatchers.IO) { mono16k(ctx) to channelCount(ctx) }
                val lufs = pcm?.let { com.elad.faceblur.audio.Loudness.measure(it, com.elad.faceblur.subs.AudioDecoder.RATE, ch) }
                if (lufs == null) {
                    notice = t("לא נמצא דיבור למדידה")
                } else {
                    val g = com.elad.faceblur.audio.Loudness.gainDb(lufs)
                    changeAudio(audio.copy(loud = true, gainDb = if (g == 0f) 0.01f else g))
                    notice = t("העוצמה: %s ← %s").format(ltr("%.1f LUFS".format(lufs)), ltr("%.1f LUFS".format(lufs + g)))
                }
            } finally {
                loudnessBusy = false
            }
        }
    }

    /** The video's sound, mono at 16 kHz, decoded once and kept (silences, loudness). */
    private var pcm16k: FloatArray? = null
    private fun mono16k(ctx: Context): FloatArray? {
        pcm16k?.let { return it }
        val x = com.elad.faceblur.subs.AudioDecoder.decode(ctx, session.info.uri, { true }) {}
        pcm16k = x
        return x
    }

    private fun channelCount(ctx: Context): Int = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(ctx, session.info.uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        } finally {
            ex.release()
        }
    }.getOrDefault(1)

    // ------------------------------------------------------------------ cuts

    /** Where a cut by hand starts (ms), while one is being marked. */
    var cutStartMs by mutableStateOf<Long?>(null)
    /** Finding silences / filler words. */
    var cutBusy by mutableStateOf(false)
        private set

    val cutCount get() = model.removed.size
    val cutTotalMs get() = model.removed.entries.sumOf { it.value - it.key }
    /** The cut stretch at the playhead, if it's in one. */
    val cutHere get() = model.cutAt(nowMs)

    fun toggleCutWord(id: Int, i: Int) {
        model.toggleCutWord(id, i)
        sync()
    }

    fun toggleCutCue(id: Int) {
        model.toggleCutCue(id)
        sync()
    }

    fun startCutHere() {
        pauser()
        cutStartMs = nowMs
    }

    fun endCutHere() {
        val a = cutStartMs ?: return
        val b = nowMs
        cutStartMs = null
        model.cut(min(a, b), max(a, b))
        sync()
    }

    fun restoreCutHere() {
        if (model.restoreCutAt(nowMs)) sync()
    }

    fun clearCuts() {
        model.clearCuts()
        sync()
    }

    /**
     * Cuts the pauses longer than [minPauseMs] (keeping a breath at each side), found in the
     * sound's level; spoken words (when there are subtitles) are never cut.
     */
    fun cutSilences(minPauseMs: Long) {
        val ctx = appContext ?: return
        if (cutBusy) return
        cutBusy = true
        scope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { mono16k(ctx) }
                if (pcm == null) {
                    notice = t("אין סאונד בסרטון")
                    return@launch
                }
                val words = model.cues.flatMap { c ->
                    Subtitles.words(c.srcText).indices.map { i -> Subtitles.wordSpanMs(c, i).let { (a, b) -> longArrayOf(a, b) } }
                }
                // The decoded sound starts at media time 0, like the subtitles' times.
                val ranges = withContext(Dispatchers.Default) {
                    SilenceFinder.find(pcm, com.elad.faceblur.subs.AudioDecoder.RATE, minPauseMs, words)
                }
                val n = model.addCuts(ranges)
                sync()
                notice = if (n == 0) t("לא נמצאו שתיקות ארוכות") else t("נחתכו %d שתיקות · %.1f שנ׳").format(n, ranges.sumOf { it[1] - it[0] } / 1000f)
            } finally {
                cutBusy = false
            }
        }
    }

    /** Cuts the filler sounds ("אממ", "אה", "um") found in the subtitles' words. */
    fun cutFillers() {
        val ranges = ArrayList<LongArray>()
        for (c in model.cues) {
            val ws = Subtitles.words(c.srcText)
            ws.forEachIndexed { i, w ->
                if (SilenceFinder.isFiller(w)) {
                    val (a, b) = Subtitles.wordSpanMs(c, i)
                    if (b > a) ranges += longArrayOf(a, b)
                }
            }
        }
        val n = model.addCuts(ranges)
        sync()
        notice = if (n == 0) t("לא נמצאו מילות מילוי בכתוביות") else t("נחתכו %d מילות מילוי").format(n)
    }

    // ------------------------------------------------------------------ stabilization

    /** How much the camera is steadied: 0 off, 1 light, 2 medium, 3 strong. */
    var stabilizeLevel by mutableIntStateOf(0)
        private set
    val stabilize get() = stabilizeLevel > 0
    /** Measuring the camera's motion (a video opened without face detection), while it runs. */
    var stabilizeProgress by mutableStateOf<Float?>(null)
        private set
    /** What the player's stabilize effect reads (on its own thread). */
    @Volatile var stabilizer: Stabilizer? = null
        private set
    /** The camera's motion: from the face analysis, or measured on demand. */
    private var motion: Stabilizer.Motion? = null
    private var motionJob: Job? = null

    /** The stabilizer's panel is open. */
    var stabilizePanel: Boolean
        get() = panel == EditorPanel.STABILIZE
        set(v) = showPanel(EditorPanel.STABILIZE, v)

    fun chooseStabilize(level: Int) {
        if (level <= 0) {
            motionJob?.cancel()
            stabilizeProgress = null
            stabilizeLevel = 0
            stabilizer = null
            refresher()
            return
        }
        stabilizeLevel = level.coerceAtMost(3)
        val known = motion ?: session.analysis.let { a -> Stabilizer.Motion(a.times, a.motion, a.cuts, a.aspect, a.fps, a.rotation) }.takeIf { it.measured }
        if (known != null) {
            motion = known
            applyStabilizer()
            return
        }
        // No face detection ran: measure the camera's motion first (a quick pass).
        val ctx = appContext ?: return
        if (motionJob?.isActive == true) return
        stabilizeProgress = 0f
        motionJob = scope.launch {
            try {
                motion = FaceBlurProcessor(ctx).measureMotion(session.info) { p -> stabilizeProgress = p }
                if (stabilizeLevel > 0) applyStabilizer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ONIT", "motion pass failed", e)
                notice = t("מדידת תנועת המצלמה נכשלה")
                stabilizeLevel = 0
            } finally {
                stabilizeProgress = null
            }
        }
    }

    private fun applyStabilizer() {
        val s = motion?.stabilizer(stabilizeLevel)
        stabilizer = s
        if (s == null) notice = t("המצלמה כבר יציבה: אין מה לייצב")
        refresher()
    }

    /** How much of the picture the current level enlarges (percent), or null. */
    val stabilizeZoomPercent get() = stabilizer?.let { ((it.zoom - 1f) * 100).roundToInt() }

    /** Cancels the motion pass if the editor goes away. */
    fun cancelMotion() {
        motionJob?.cancel()
        findJob?.cancel()
    }

    // ------------------------------------------------------------------ faces

    /** Face detection run from the editor (a session opened without it): 0..1 while it runs. */
    var findFacesProgress by mutableStateOf<Float?>(null)
        private set

    /** Bumped when the player must be made again (its blur effect only exists once there are faces). */
    var playerEpoch by mutableIntStateOf(0)
        private set

    /** Called once detection found faces and they were taken in (the project is saved again). */
    var onFacesFound: (() -> Unit)? = null
    private var findJob: Job? = null

    /**
     * "Find faces" for a session opened without detection (subtitles, sound...): detection runs on
     * the video, the blurs come in next to what was edited, and the faces list shows them.
     */
    fun findFaces() {
        val ctx = appContext ?: return
        if (!session.subtitlesOnly || findJob?.isActive == true) return
        // Never without the whole face pack (and never a weaker way instead): the panel offers the download.
        if (!com.elad.faceblur.models.ModelStore.get(ctx).isUsable(com.elad.faceblur.models.PackId.FACES)) {
            notice = t("כדי לטשטש פנים צריך את מודל זיהוי הפנים")
            return
        }
        if (segmentProgress != null || segmentJob?.isActive == true) {
            notice = t("אי אפשר לזהות פנים בזמן שהאנשים מזוהים. חכה שהזיהוי יסתיים או בטל אותו.")
            return
        }
        pauser()
        addMode = false
        findFacesProgress = 0f
        // ATOMIC: the body (and its finally) runs even if cancelled before it starts, so the progress is always cleared.
        findJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            try {
                val found = FaceBlurProcessor(ctx).analyze(session.info, session.settings) { p -> main.post { if (findFacesProgress != null) findFacesProgress = p } }
                val keep = session.masks != null && MaskPolicy.keepMasks(session.analysis.times, found.times)
                session.adoptDetection(found)
                if (!keep) {
                    // What was made from the old frames goes: the people's masks, and the background's frame times.
                    val old = session.masks
                    session.masks = null
                    bgState.masks = null
                    old?.close()
                    session.projectDir?.let { d -> java.io.File(d, "masks.bin").delete(); java.io.File(d, "masks.bin.idx").delete() }
                    masksReady = false
                }
                bgState = BackgroundState(background, session.masks, session.analysis.times)
                previewAvailable = true
                frame = 0
                selectedId = null
                sync()
                rebuildPreview()
                playerEpoch++
                onFacesFound?.invoke()
                notice = if (model.tracks.isEmpty()) t("לא זוהו פנים. אפשר לסמן ידנית עם \"הוסף טשטוש\".") else null
                if (MaskPolicy.rerunPeople(background, keep)) {
                    // The people background can't be drawn without masks for these frames: find them again.
                    findPeople()
                    notice = t("הפריימים השתנו, אז האנשים מזוהים מחדש בשביל הרקע")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.elad.faceblur.models.ModelMissing) {
                notice = t("כדי לטשטש פנים צריך את מודל זיהוי הפנים")
            } catch (e: Exception) {
                android.util.Log.w("ONIT", "finding faces failed", e)
                notice = t("זיהוי הפנים נכשל")
            } finally {
                findFacesProgress = null
            }
        }
    }

    fun cancelFindFaces() {
        findJob?.cancel()
    }

    /** Turns a face's blur off or back on (one undo step). */
    fun toggleBlurOff(id: Int) {
        model.toggleOff(id)
        sync()
    }

    /** Turns every face's blur on or off (one undo step). */
    fun setAllBlursOff(off: Boolean) {
        model.setAllOff(off)
        sync()
    }

    // ------------------------------------------------------------------ sound effects

    /** The sound effects panel tab is showing (else the voice tab). */
    var soundTab by mutableIntStateOf(0)
    var selectedSfxId by mutableStateOf<Int?>(null)
        private set
    val selectedSfx get() = selectedSfxId?.let { model.sfxClip(it) }
    var sfxGainPanel by mutableStateOf(false)

    /** Adds a library sound at the playhead (decoded first, off the main thread). */
    fun addSfx(sound: String) {
        val ctx = appContext ?: return
        val at = nowMs
        scope.launch {
            val dur = withContext(Dispatchers.IO) { SfxLibrary.durationMs(ctx, sound) }
            if (dur <= 0) return@launch
            model.addSfx(sound, at, dur)
            sync()
            notice = t("נוסף: %s").format(t(SfxLibrary.name(sound)))
        }
    }

    fun selectSfx(id: Int?) {
        selectedSfxId = id
        if (id != null) {
            selectedId = null
            selectedCueId = null
        }
        sizePanel = false
        sfxGainPanel = false
    }

    fun deleteSelectedSfx() {
        val id = selectedSfxId ?: return
        model.deleteSfx(id)
        selectSfx(null)
        sync()
    }

    fun moveSelectedSfxHere() {
        val id = selectedSfxId ?: return
        model.placeSfx(id, nowMs)
        sync()
    }

    fun setSelectedSfxGain(g: Float) {
        val id = selectedSfxId ?: return
        model.setSfxGain(id, g)
        sync()
    }

    /** Plays the selected sound effect where it is in the video. */
    fun playSelectedSfx() {
        val c = selectedSfx ?: return
        seek(frameAt(cueRelUs(max(0L, c.startMs - 300))))
        starter()
    }

    fun beginSfxDrag() = model.beginGesture()

    fun dragSfxTo(id: Int, ms: Long) {
        model.dragSfx(id, ms)
        sync()
    }

    /** Bleeps spoken word [i] of subtitle [id] or puts it back. */
    fun toggleBleep(id: Int, i: Int) {
        val on = model.toggleBleep(id, i)
        sync()
        if (on && audio.voice == VoiceFx.NONE && model.bleeps.size == 1) notice = t("המילה תצפצף בייצוא. אפשר לשמוע בנגן.")
    }

    fun clearBleeps() {
        model.clearBleeps()
        sync()
    }

    /** Subtitles from an SRT file in place of the present ones (and their bleeps): one undoable step. */
    fun importSubtitles(list: List<NewCue>) {
        if (list.isEmpty()) return
        model.importCues(list)
        selectCue(null)
        sync()
        list.minOfOrNull { it.startMs }?.let { first -> seek(frameAt(cueRelUs(first + 10))) }
    }

    /** Words bleeped in all subtitles. */
    val bleepedCount get() = model.cues.sumOf { model.bleepedWords(it).size }

    /** The .srt files the export would save now (the same text, hidden words and times), one per language. */
    fun srtFiles(): List<com.elad.faceblur.subs.Srt.SrtFile> = com.elad.faceblur.subs.Srt.files(model.srtCues(audio.maskWords))

    /** Bleeped words would be in the .srt files as they are: hiding them in the subtitles is switched off. */
    val srtShowsBleeps get() = !audio.maskWords && bleepedCount > 0

    /** What the .srt files saved now are named by: the video's name. */
    val srtBaseName get() = session.info.name

    // ------------------------------------------------------------------ background

    /** Background removal settings. */
    var background by mutableStateOf(Background())
        private set
    /** The background panel is open (in place of the timeline). */
    var bgPanel: Boolean
        get() = panel == EditorPanel.BACKGROUND
        set(v) = showPanel(EditorPanel.BACKGROUND, v)
    /** Progress of finding the people, while it runs. */
    var segmentProgress by mutableStateOf<Float?>(null)
        private set
    /** The people have been found in every frame. */
    var masksReady by mutableStateOf(session.masks != null)
        private set
    /** What the player's background effect draws (changed live). */
    var bgState = BackgroundState(Background(), session.masks, session.analysis.times)
        private set
    private var segmentJob: Job? = null

    fun changeBackground(b: Background) {
        if (b == background) return
        background = b
        bgState.background = b
        if (!playing) refresher()
    }

    /** Masks loaded with a saved project. */
    fun masksLoaded() {
        bgState.masks = session.masks
        masksReady = session.masks != null
        if (!playing) refresher()
    }

    /** Finds the people in every frame (a pass over the video, on the device). */
    fun findPeople() {
        val ctx = appContext ?: return
        if (segmentJob?.isActive == true) return
        if (findFacesProgress != null) {
            notice = t("אי אפשר לזהות אנשים בזמן שהפנים מזוהות. חכה שהזיהוי יסתיים או בטל אותו.")
            return
        }
        // Checked before the old masks go: without the people model they stay as they are.
        if (!com.elad.faceblur.models.ModelStore.get(ctx).isUsable(com.elad.faceblur.models.PackId.PEOPLE)) {
            notice = t("כדי להסיר רקע צריך את מודל האנשים")
            return
        }
        pauser()
        // The old index must not outlive the masks it described.
        session.projectDir?.let { d -> java.io.File(d, "masks.bin.idx").delete() }
        // Set before the launch: awaitIdle() must see the work as started.
        segmentProgress = 0f
        // ATOMIC: the body (and its finally) runs even if cancelled before it starts, so the progress is always cleared.
        segmentJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            try {
                val dir = session.projectDir ?: java.io.File(ctx.cacheDir, "masks").apply { mkdirs() }
                val old = session.masks
                session.masks = null
                bgState.masks = null
                masksReady = false
                old?.close()
                val store = FaceBlurProcessor(ctx).segment(session.info, session.analysis, java.io.File(dir, "masks.bin")) { p ->
                    main.post { if (segmentProgress != null) segmentProgress = p }
                }
                session.masks = store
                masksLoaded()
                notice = t("האנשים בסרטון זוהו")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.elad.faceblur.models.ModelMissing) {
                notice = t("כדי להסיר רקע צריך את מודל האנשים")
            } catch (e: Exception) {
                android.util.Log.w("ONIT", "segmentation failed", e)
                notice = t("זיהוי האנשים נכשל: %s").format(e.message ?: e.javaClass.simpleName)
            } finally {
                segmentProgress = null
                masksReady = session.masks != null
            }
        }
    }

    /** Whether a people background could be drawn right now (the masks are there and not being redone). */
    val peopleMasksOk get() = session.masks != null && masksReady && segmentProgress == null

    fun cancelFindPeople() {
        segmentJob?.cancel()
    }

    // ------------------------------------------------------------------ vertical / square frame

    private var reframeCache: Triple<Int, ExportAspect, Pair<FloatArray, FloatArray>>? = null
    private var reframeAt = 0L

    /**
     * The window a vertical / square export shows at frame [f] (normalized, top-left origin),
     * or null for the frame as shot.
     */
    fun reframeWindow(aspect: ExportAspect, f: Int): android.graphics.RectF? {
        if (aspect == ExportAspect.ORIGINAL || frameCount == 0) return null
        val a = session.analysis
        val (cw, ch) = Reframer.cropFraction(a.uprightW, a.uprightH, aspect.ratio)
        var c = reframeCache
        val now = android.os.SystemClock.uptimeMillis()
        // While dragging, the path is worked out again at most a few times a second.
        if (c == null || c.second != aspect || (c.first != model.version && now - reframeAt > 400)) {
            c = Triple(model.version, aspect, model.reframePath(padding, cw, ch))
            reframeCache = c
            reframeAt = now
        }
        val (x, y) = c.third
        val i = f.coerceIn(0, x.size - 1)
        return android.graphics.RectF(x[i] - cw / 2, y[i] - ch / 2, x[i] + cw / 2, y[i] + ch / 2)
    }

    // ------------------------------------------------------------------ speakers

    fun setSpeaker(id: Int, speaker: Int, wholeSentence: Boolean = true) {
        model.setSpeaker(id, speaker, wholeSentence)
        sync()
    }

    /** Speakers in use (1..). */
    val speakersUsed get() = model.cues.map { it.speaker.coerceAtLeast(1) }.toSortedSet()

    fun changeSplit(rule: SplitRule) {
        split = rule
        if (model.cues.isEmpty()) return
        model.regroup(rule)
        sync()
    }

    /** Picks what subtitles are translated into (null: English, or Hebrew for English speech). */
    fun changeTranslateTo(v: String?) {
        translateTo = v?.takeIf { SubLanguages.translatable(it) }
    }

    fun changeTranslateOnCreate(v: Boolean) {
        translateOnCreate = v
        prefs?.updateSubsTranslate(v)
    }

    /** Some subtitle has a translation. */
    val hasTranslation get() = model.cues.any { it.otherText.isNotBlank() }

    fun deleteCue(id: Int) {
        model.deleteCue(id)
        sync()
    }

    fun mergeCueWithNext(id: Int) {
        if (model.mergeWithNext(id)) sync()
    }

    fun mergeCueWithPrevious(id: Int) {
        if (model.mergeWithPrevious(id)) sync()
    }

    /** Joins the selected subtitle with the next one (or the one before, if it's the last). */
    fun mergeSelectedCue() {
        val id = selectedCueId ?: return
        if (model.mergeWithNext(id) || model.mergeWithPrevious(id)) {
            sync()
            model.cues.firstOrNull { it.id == id }?.let { selectCue(it.id) }
        }
    }

    /** Splits the selected subtitle at the word nearest to the playhead. */
    fun splitSelectedCue() {
        val id = selectedCueId ?: return
        val r = model.splitCueAtTime(id, nowMs)
        if (r < 0) {
            notice = t("אפשר לפצל רק כתובית עם יותר ממילה אחת")
            return
        }
        sync()
        selectCue(id)
    }

    /** Splits so spoken word [k] starts a new subtitle. */
    fun splitCueAtWord(id: Int, k: Int) {
        if (model.splitCueAtWord(id, k) >= 0) sync()
    }

    /** Splits at the cursor in the spoken text (-1: in the middle). */
    fun splitCueAtCaret(id: Int, offset: Int) {
        if (model.splitCueAtChar(id, offset) >= 0) sync() else notice = t("אפשר לפצל רק כתובית עם יותר ממילה אחת")
    }

    /** Find and replace in every subtitle; returns how many were replaced. */
    fun replaceInSubtitles(find: String, replace: String): Int {
        val n = model.replaceAll(find, replace)
        if (n > 0) sync()
        return n
    }

    /** A text field of a subtitle got focus: its first change will be one undo step. */
    fun beginTextEdit() {
        pendingTextSnapshot = true
    }

    /** Typing in a subtitle (from the list): its spoken text and translation. */
    fun typeCueText(id: Int, spoken: String, translation: String) {
        if (pendingTextSnapshot) {
            model.beginGesture()
            pendingTextSnapshot = false
        }
        model.setCueTexts(id, spoken, translation)
        sync()
    }

    fun cancelSubtitles() {
        subsJob?.cancel()
        speakersJob?.cancel()
        segmentJob?.cancel()
    }

    // ------------------------------------------------------------------ who speaks

    /** Speaker detection progress 0..1 while it runs. */
    var speakersProgress by mutableStateOf<Float?>(null)
        private set
    private var speakersJob: Job? = null

    /**
     * Finds who says each sentence (on the device) and gives each speaker a color.
     * @param count the number of speakers, if known (null: found automatically).
     */
    fun detectSpeakers(count: Int?) {
        val mm = models ?: return
        val ctx = appContext ?: return
        if (model.cues.isEmpty() || speakersJob?.isActive == true || subsBusy) return
        pauser()
        // The voice model is downloaded from its card in the panel, never on the way.
        if (!mm.speakerReady) {
            notice = t("כדי למצוא דוברים צריך את מודל הקולות")
            return
        }
        val speakerStore = com.elad.faceblur.models.ModelStore.get(ctx)
        speakersJob = scope.launch {
            speakersProgress = 0f
            var held = false
            try {
                // Checked (size and SHA-256) before it runs, and held while it runs.
                val speakerModel = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    speakerStore.acquire(com.elad.faceblur.models.PackId.SPEAKERS).also { held = true }[ModelManager.SPEAKER_FILE]
                } ?: throw com.elad.faceblur.models.ModelMissing(com.elad.faceblur.models.PackId.SPEAKERS)
                val groups = model.cues.sortedBy { it.startMs }.groupBy { it.group }.values.toList()
                val spans = groups.map { cs -> SpeakerDetector.Span(cs.minOf { it.startMs }, cs.maxOf { it.endMs }) }
                val labels = SpeakerDetector.detect(ctx, session.info.uri, speakerModel.path, spans, count) { p ->
                    main.post { if (speakersProgress != null) speakersProgress = p }
                }
                val map = HashMap<Int, Int>()
                groups.forEachIndexed { i, cs -> for (c in cs) map[c.id] = labels[i] }
                model.setSpeakers(map)
                sync()
                val n = labels.distinct().size
                notice = if (n <= 1) t("נמצא דובר אחד") else t("נמצאו %d דוברים, כל אחד בצבע משלו").format(n)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SubtitleEngine.NoAudioException) {
                notice = t("אין קול בסרטון")
            } catch (e: com.elad.faceblur.models.ModelMissing) {
                notice = t("כדי למצוא דוברים צריך את מודל הקולות")
            } catch (e: Exception) {
                android.util.Log.w("ONIT", "speakers failed", e)
                notice = t("זיהוי הדוברים נכשל: %s").format(e.message ?: e.javaClass.simpleName)
            } finally {
                if (held) speakerStore.release(com.elad.faceblur.models.PackId.SPEAKERS)
                speakersProgress = null
            }
        }
    }

    fun cancelSpeakers() {
        speakersJob?.cancel()
    }

    fun scaleSelectedBy(f: Float) {
        val t = selected ?: return
        model.setScale(t.id, t.scale * f)
        sync()
    }

    /** Size slider: one undo step per slide. */
    fun setSelectedScale(s: Float) {
        val t = selected ?: return
        if (!sliderGesture) {
            model.beginGesture()
            sliderGesture = true
        }
        model.setScale(t.id, s)
        sync()
    }

    fun endSlider() {
        sliderGesture = false
    }

    fun hitTest(x: Float, y: Float): Int? = model.hitTest(frame, x, y, padding, HIT_RADIUS)

    fun tap(x: Float, y: Float) {
        if (addMode) {
            addAt(x, y)
        } else {
            val id = hitTest(x, y)
            if (id == null) deselect() else select(id)
        }
    }

    /** New blur at a tapped point (sized from a detection there, if any), then tracked both ways. */
    fun addAt(x: Float, y: Float) {
        addMode = false
        val det = model.detectionAt(frame, x, y)
        val box = det ?: run {
            val w = DEFAULT_SIZE
            val h = w * 1.25f / model.aspect
            Box(x, y + 0.06f * h, w, h)
        }
        val id = model.add(frame, box.cx, box.cy, box.w, box.h)
        selectedId = id
        sync()
        track(id, 0, fresh = true)
    }

    /** Re-track the selected blur from the current frame: -1 backward, +1 forward. */
    fun trackSelected(dir: Int) {
        val id = selectedId ?: return
        track(id, dir, fresh = false)
    }

    private fun track(id: Int, dir: Int, fresh: Boolean) {
        val store = session.analysis.frames
        if (store == null) {
            notice = t("מעקב אוטומטי לא זמין לסרטון הזה")
            return
        }
        val t = model.track(id) ?: return
        val from = frame
        val init = model.rawBox(t, from)?.let { b -> Box(b.cx, b.cy, b.w * t.scale, b.h * t.scale) } ?: return
        val scale = t.scale
        if (playing) pauser()
        trackJob?.cancel()
        trackingProgress = 0f
        trackJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    ManualTracker(store, model).track(from, init, dir, { isActive }) { p ->
                        main.post { if (trackingProgress != null) trackingProgress = p }
                    }
                }
                if (result.size <= 1) {
                    notice = t("לא הצלחתי לעקוב מכאן. אפשר להזיז את הטשטוש ולקבוע התחלה וסוף ידנית.")
                } else {
                    // The tracker works on the visible size; the path stores it without the user's factor.
                    val unscaled = java.util.TreeMap<Int, Box>()
                    result.forEach { (k, b) -> unscaled[k] = Box(b.cx, b.cy, b.w / scale, b.h / scale) }
                    model.applyTracking(id, unscaled, fresh, undoable = !fresh)
                    val tr = model.track(id)
                    if (tr != null) {
                        val secs = (tr.end - tr.start + 1) / max(1f, model.fps)
                        notice = t("הטשטוש עוקב %.1f שניות. בדוק וקצר אם צריך.").format(secs)
                    }
                }
            } finally {
                trackingProgress = null
                sync()
            }
        }
    }

    fun cancelTracking() {
        trackJob?.cancel()
    }

    companion object {
        /** The longest a template step is waited for with no editor on screen. */
        const val IDLE_TIMEOUT_MS = 60L * 60 * 1000

        /** Minimum tap radius, in frame widths. */
        private const val HIT_RADIUS = 0.045f
        /** Width of a new blur when there's no detection under the tap, in frame widths. */
        private const val DEFAULT_SIZE = 0.12f
    }
}

/** The editor's tool panels (see [EditorController.panel]). */
enum class EditorPanel { BLUR, SUBTITLES, LIST, STYLE, VOICE, BACKGROUND, WATERMARK, CUT, STABILIZE, FRAME }
