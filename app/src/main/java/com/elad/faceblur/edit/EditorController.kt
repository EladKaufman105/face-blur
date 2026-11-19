package com.elad.faceblur.edit

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
) : Closeable {
    val model = analysis.toEditModel()

    override fun close() = analysis.close()
}

/**
 * Editor UI state and actions over an [EditSession]. Lives in the view model so a running
 * "track" survives recomposition. All methods are called on the main thread.
 */
class EditorController(val session: EditSession, private val scope: CoroutineScope) {
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

    /** Set by the player UI: shows frame [f]. */
    var seeker: (Int) -> Unit = {}
    /** Set by the player UI. */
    var pauser: () -> Unit = {}
    /** Set by the player UI. */
    var playToggler: () -> Unit = {}

    private var trackJob: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private var sliderGesture = false

    private fun sync() {
        version = model.version
        if (selectedId != null && model.track(selectedId!!) == null) selectedId = null
    }

    val selected get() = selectedId?.let { model.track(it) }

    /** Display numbers (1-based, by start frame) for blurs, shared by the lanes and the overlay. */
    fun labels(): Map<Int, Int> =
        model.tracks.sortedWith(compareBy({ it.start }, { it.id })).mapIndexed { i, t -> t.id to i + 1 }.toMap()

    // ------------------------------------------------------------------ playback

    /** From the player while it plays (or after a seek lands). */
    fun onFrameShown(f: Int) {
        if (playing) frame = f.coerceIn(0, frameCount - 1)
    }

    fun seek(f: Int) {
        val c = f.coerceIn(0, frameCount - 1)
        frame = c
        seeker(c)
    }

    fun step(d: Int) {
        if (playing) pauser()
        seek(frame + d)
    }

    fun select(id: Int?) {
        selectedId = id
    }

    /** Select a blur and, if the playhead isn't on it, jump to its first frame. */
    fun focus(id: Int) {
        selectedId = id
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
            notice = "כדי לפצל, הזז את הסמן לתוך טווח הטשטוש"
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
        if (addMode) addAt(x, y) else selectedId = hitTest(x, y)
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
            notice = "מעקב אוטומטי לא זמין לסרטון הזה"
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
                    notice = "לא הצלחתי לעקוב מכאן. אפשר להזיז את הטשטוש ולקבוע התחלה וסוף ידנית."
                } else {
                    // The tracker works on the visible size; the path stores it without the user's factor.
                    val unscaled = java.util.TreeMap<Int, Box>()
                    result.forEach { (k, b) -> unscaled[k] = Box(b.cx, b.cy, b.w / scale, b.h / scale) }
                    model.applyTracking(id, unscaled, fresh, undoable = !fresh)
                    val tr = model.track(id)
                    if (tr != null) {
                        val secs = (tr.end - tr.start + 1) / max(1f, model.fps)
                        notice = "הטשטוש עוקב %.1f שניות. בדוק וקצר אם צריך.".format(secs)
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
