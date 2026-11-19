package com.elad.faceblur.edit

import com.elad.faceblur.i18n.t
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
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubtitleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.max

fun AnalysisResult.toEditModel() = EditModel(times, cuts, motion, aspect, dets, fps, tracks)

/** One video between analysis and export: the analysis data and the user's edits. */
class EditSession(
    val info: VideoInfo,
    val settings: Settings,
    val analysis: AnalysisResult,
    val analysisMs: Long,
    /** Opened for subtitles only: no face detection was run. */
    val subtitlesOnly: Boolean = false,
) : Closeable {
    val model = analysis.toEditModel()

    override fun close() = analysis.close()
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
    var sizePanel by mutableStateOf(false)
    var selectedCueId by mutableStateOf<Int?>(null)
        private set
    /** Subtitle generation stage and progress, while it runs. */
    var subsProgress by mutableStateOf<Pair<SubtitleEngine.Stage, Float>?>(null)
        private set
    var subsSheet by mutableStateOf(false)
    var cueEditor by mutableStateOf(false)
    /** The subtitle look panel is open (in place of the timeline). */
    var stylePanel by mutableStateOf(false)
    private var subsJob: Job? = null

    /** Frame the player and the overlay show. */
    val shownFrame get() = peekFrame ?: frame

    /** Set by the player UI: shows frame [f]. */
    var seeker: (Int) -> Unit = {}
    /** Set by the player UI. */
    var pauser: () -> Unit = {}
    /** Set by the player UI. */
    var playToggler: () -> Unit = {}
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
        if (selectedId != null && model.track(selectedId!!) == null) selectedId = null
        if (selectedCueId != null && model.cue(selectedCueId!!) == null) selectedCueId = null
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

    /** From the player while it plays (or after a seek lands). */
    fun onFrameShown(f: Int) {
        if (playing) frame = f.coerceIn(0, frameCount - 1)
    }

    fun seek(f: Int) {
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
        val t = model.track(id) ?: return
        if (frame !in t.start..t.end) seek(t.start)
    }

    // ------------------------------------------------------------------ edits

    fun undo() {
        if (trackingProgress != null) return
        model.undo()
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
        sizePanel = false
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
        if (id != null) selectedId = null
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

    /** A new, empty subtitle at the playhead, opened for typing. */
    fun addCueHere() {
        val id = model.addCue(nowMs, msAtRelUs(timeOf(frameCount - 1) + frameUs))
        sync()
        selectCue(id)
        cueEditor = true
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

    /** Downloads [m] if needed, then transcribes the video with it. */
    fun makeSubtitles(m: SpeechModel) {
        val mm = models ?: return
        if (subsJob?.isActive == true || mm.downloading != null) return
        if (mm.isReady(m)) {
            generateSubtitles(m)
            return
        }
        subsJob = scope.launch {
            try {
                mm.download(m)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = t("הורדת המודל נכשלה: %s").format(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            generateSubtitles(m)
        }
    }

    /** True while a model downloads or subtitles are being made. */
    val subsBusy get() = subsProgress != null || models?.downloading != null

    /** Transcribes the video with [m] (downloaded) and fills the subtitles. */
    fun generateSubtitles(m: SpeechModel) {
        val path = models?.file(m)?.takeIf { models.isReady(m) }?.absolutePath ?: return
        generateSubtitles(path, m.language)
    }

    fun generateSubtitles(modelPath: String, lang: String) {
        val ctx = appContext ?: return
        if (playing) pauser()
        subsJob?.cancel()
        subsProgress = SubtitleEngine.Stage.AUDIO to 0f
        subsJob = scope.launch {
            try {
                val r = SubtitleEngine.run(
                    ctx, session.info.uri, modelPath, lang, translate = false,
                    speed = models?.speed(modelPath) ?: 0.6f,
                    onSpeed = { v -> models?.learnSpeed(modelPath, v) },
                ) { st, p ->
                    main.post { if (subsProgress != null) subsProgress = st to p }
                }
                // Keep the transcription even if translating fails or is stopped.
                model.setCues(r.lines.map { Triple(it.startMs, it.endMs, it.he to it.en) })
                sync()
                if (r.lines.isEmpty()) {
                    notice = t("לא נמצא דיבור בסרטון")
                    return@launch
                }
                subsSheet = false
                notice = t("נוצרו %d כתוביות. מתרגם…").format(r.lines.size)
                translateMissing()
            } catch (e: SubtitleEngine.NoAudioException) {
                notice = t("אין קול בסרטון")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = t("יצירת הכתוביות נכשלה: %s").format(e.message ?: e.javaClass.simpleName)
            } finally {
                subsProgress = null
                sync()
            }
        }
    }

    /** Subtitles with only one of the two languages. */
    val untranslatedCount get() = model.cues.count { it.he.isBlank() != it.en.isBlank() }

    /** Translates the subtitles that have only one language (again, after a failure). */
    fun translateSubtitles() {
        if (subsJob?.isActive == true || untranslatedCount == 0) return
        subsJob = scope.launch {
            try {
                translateMissing()
            } finally {
                subsProgress = null
                sync()
            }
        }
    }

    private suspend fun translateMissing() {
        val cues = model.cues.map { it.copy() }
        if (cues.none { it.he.isBlank() != it.en.isBlank() }) return
        subsProgress = SubtitleEngine.Stage.TRANSLATE to 0f
        try {
            SubtitleEngine.translateLines(
                cues.map { it.he to it.en },
                { st, p -> main.post { if (subsProgress != null) subsProgress = st to p } },
            ) { i, he, en ->
                main.post {
                    model.setCueText(cues[i].id, he, en)
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

    fun cancelSubtitles() {
        subsJob?.cancel()
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
        /** Minimum tap radius, in frame widths. */
        private const val HIT_RADIUS = 0.045f
        /** Width of a new blur when there's no detection under the tap, in frame widths. */
        private const val DEFAULT_SIZE = 0.12f
    }
}
