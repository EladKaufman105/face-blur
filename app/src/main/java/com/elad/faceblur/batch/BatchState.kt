package com.elad.faceblur.batch

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elad.faceblur.template.TemplateSettings
import kotlinx.coroutines.Job

enum class ItemStatus(val done: Boolean) {
    WAITING(false), ANALYZING(false),

    /** Subtitles, voice, cuts, background, steadying. */
    PROCESSING(false), EXPORTING(false),
    SAVED(true), FAILED(true), NO_FACES(true), SKIPPED(true),
}

/** One video of a batch and where it stands. */
class BatchItem(
    val index: Int,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val thumbnail: Bitmap?,
) {
    var status by mutableStateOf(ItemStatus.WAITING)
    /** 0..1 over the whole video's work. */
    var progress by mutableStateOf(0f)
    /** Why it failed, in words to act on. */
    var error by mutableStateOf<String?>(null)
    /** The technical part of the failure, to send. */
    var details by mutableStateOf<String?>(null)
    /** Some detected faces had low detection confidence. */
    var lowConfidence by mutableStateOf(false)
    /** Things that could not be done (the video was still saved). */
    var notes by mutableStateOf<List<String>>(emptyList())
    /** Its project, to open in the editor (once there is one). */
    var projectId by mutableStateOf<String?>(null)
    /** The video saved in Movies/ONIT, if it was saved. */
    var output by mutableStateOf<Uri?>(null)

    /** When its work started and ended (SystemClock.elapsedRealtime; 0: not yet), for the time left. */
    @Volatile var startedAt = 0L
    @Volatile var endedAt = 0L

    @Volatile var skipRequested = false
    var job: Job? = null

    /** How face detection went (where it ran, its pace), to compare with a single video's. */
    var analysisInfo by mutableStateOf<String?>(null)

    /** A step of the template didn't work out (said in [notes]): the video was still saved, but needs a look. */
    val stepFailed: Boolean get() = notes.isNotEmpty() && status != ItemStatus.FAILED

    /** Worth a look: no faces found, faces with low confidence, a template step that didn't work, or failed. */
    val needsCheck: Boolean get() = status == ItemStatus.FAILED || status == ItemStatus.NO_FACES || lowConfidence || stepFailed
}

/** A batch being processed (or finished): its videos and the settings every one gets. */
class Batch(val items: List<BatchItem>, val settings: TemplateSettings, val templateName: String) {
    /** Its projects carry this id: the Projects list shows them as one folder. */
    val id: String = com.elad.faceblur.project.ProjectStore.newId()
    val startedAt: Long = System.currentTimeMillis()

    /** The learned speeds of this phone, for the time left (null: no estimate). */
    var speeds: BatchMath.Speeds? = null

    var finished by mutableStateOf(false)
    var cancelled by mutableStateOf(false)

    val doneCount: Int get() = items.count { it.status.done }
    val savedCount: Int get() = items.count { it.status == ItemStatus.SAVED || (it.status == ItemStatus.NO_FACES && it.output != null) }
    val failedCount: Int get() = items.count { it.status == ItemStatus.FAILED }
    val checkCount: Int get() = items.count { it.needsCheck }

    /** Progress over all videos, 0..1. */
    val progress: Float get() = if (items.isEmpty()) 1f else items.sumOf { (if (it.status.done) 1f else it.progress).toDouble() }.toFloat() / items.size

    /** Time left in ms from the real pace so far and the learned speeds; null if there is no estimate. */
    fun remainingMs(now: Long = android.os.SystemClock.elapsedRealtime()): Long? {
        val sp = speeds ?: return null
        val timings = items.map { i ->
            val phase = when {
                i.status.done -> BatchMath.Phase.DONE
                i.status == ItemStatus.WAITING -> BatchMath.Phase.WAITING
                else -> BatchMath.Phase.RUNNING
            }
            val elapsed = if (i.startedAt == 0L) 0L else (if (i.endedAt > 0) i.endedAt else now) - i.startedAt
            BatchMath.ItemTiming(i.durationMs, i.progress, phase, elapsed)
        }
        return BatchMath.remainingMs(timings, settings, sp)
    }

    /** The video being worked on now, if any. */
    val current: BatchItem? get() = items.firstOrNull { !it.status.done && it.status != ItemStatus.WAITING }

    /** Skips one video: the one waiting never starts, the one running is stopped. */
    fun skip(item: BatchItem) {
        when {
            item.status == ItemStatus.WAITING -> item.status = ItemStatus.SKIPPED
            !item.status.done -> {
                item.skipRequested = true
                item.job?.cancel()
            }
        }
    }

    /** Stops everything: the video being worked on and all that wait. */
    fun cancelAll() {
        cancelled = true
        for (i in items) if (i.status == ItemStatus.WAITING) i.status = ItemStatus.SKIPPED
        current?.let { it.skipRequested = true; it.job?.cancel() }
    }
}

/** The batch of this run of the app (a batch lives in the process; its results are saved as projects). */
object BatchState {
    var current by mutableStateOf<Batch?>(null)
}

/** The time left as words: "About 3 minutes left". */
fun remainingText(ms: Long): String {
    val min = kotlin.math.ceil(ms / 60_000.0).toInt()
    return when {
        ms < 45_000L -> com.elad.faceblur.i18n.t("נותרה פחות מדקה")
        min < 60 -> com.elad.faceblur.i18n.t("נותרו כ-%d דקות").format(min)
        else -> com.elad.faceblur.i18n.t("נותרו כ-%d:%02d שעות").format(min / 60, min % 60)
    }
}

/** "37% · About 3 minutes left" (the time only when there is an estimate). */
fun Batch.progressLine(): String {
    val pct = (progress * 100).toInt().coerceIn(0, 100)
    val left = remainingMs()
    return if (left == null) "$pct%" else "$pct% · " + remainingText(left)
}
