package com.elad.faceblur.batch

import android.content.Context
import android.os.SystemClock
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.ProcessingErrors
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.prefs.AppPrefs
import com.elad.faceblur.project.ProjectStore
import com.elad.faceblur.project.projectFields
import com.elad.faceblur.subs.ModelManager
import com.elad.faceblur.template.TemplateRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Processes a [Batch] one video after another, with no screen: the same steps as the single
 * automatic flow (analyze, then the template's processing, then render), each video saved to
 * Movies/ONIT and as a project. One video failing doesn't stop the rest. Everything runs on the
 * main thread's dispatcher, like the view model's work (Transformer wants its application thread).
 */
object BatchRunner {
    /** Runs the whole [batch]; [onChange] is called now and then as progress moves. */
    suspend fun run(context: Context, batch: Batch, scope: CoroutineScope, onChange: () -> Unit) {
        val ctx = context.applicationContext
        val models = ModelManager(ctx)
        val prefs = AppPrefs(ctx)
        try {
            for (item in batch.items) {
                if (batch.cancelled) {
                    if (item.status == ItemStatus.WAITING) item.status = ItemStatus.SKIPPED
                    continue
                }
                if (item.status == ItemStatus.SKIPPED) continue
                val job = scope.launch { processOne(ctx, batch, item, models, prefs, scope, onChange) }
                item.job = job
                job.join()
                onChange()
            }
        } finally {
            // Every video copied what it needs into its project: the batch's own copies can go.
            withContext(NonCancellable + Dispatchers.IO) { BatchOwnFiles.delete(BatchOwnFiles.dir(ctx.filesDir), batch.id) }
        }
        batch.finished = true
        onChange()
    }

    private suspend fun processOne(
        ctx: Context, batch: Batch, item: BatchItem, models: ModelManager, prefs: AppPrefs,
        scope: CoroutineScope, onChange: () -> Unit,
    ) {
        val s = batch.settings
        var session: EditSession? = null
        var ctrl: EditorController? = null
        var projectId: String? = null
        // The export of this video: the template's, with a logo the project owns a copy of.
        var export = batch.settings.export
        item.startedAt = SystemClock.elapsedRealtime()
        try {
            // Without face blur the analysis is a quick read of the frame times: it gets a sliver of the bar.
            val analysisShare = BatchMath.analysisShare(s)
            item.begin(ItemStatus.ANALYZING)
            item.progress = 0f
            onChange()
            val info = withContext(Dispatchers.IO) { VideoInfo.probe(ctx, item.uri) }
            val settings = Settings(s.mode, s.strength, s.padding, s.sensitive, s.fast, s.sensitivity, s.selfie)

            // Analysis: faces (and the camera's motion). With no face blur, only the frames' times.
            val t0 = SystemClock.elapsedRealtime()
            val analysis = if (s.blurOff) {
                withContext(Dispatchers.IO) { FaceBlurProcessor.framesOnly(ctx, info) }
            } else {
                FaceBlurProcessor(ctx).analyze(
                    info, settings, null,
                    { snap -> item.analysisInfo = snap.summary() },
                ) { p -> item.progress = p * analysisShare; onChange() }
            }
            val analysisMs = if (s.blurOff) 0L else SystemClock.elapsedRealtime() - t0
            if (!s.blurOff) {
                // The same facts the single video's stats panel shows, plus how the system treats this process.
                val speed = if (analysisMs > 0) "%.1fx".format(info.durationMs.toFloat() / analysisMs) else "-"
                item.analysisInfo = (item.analysisInfo ?: "") + " · " + speed + " realtime · " + environment(ctx)
                android.util.Log.i("ONIT", "batch analysis of ${item.index + 1}: ${item.analysisInfo}")
            }
            if (!s.blurOff && info.durationMs > 0) prefs.analysisRatio = analysisMs.toFloat() / info.durationMs
            val sess = EditSession(info, settings, analysis, analysisMs, subtitlesOnly = s.blurOff)
            session = sess
            // No prefs: a template's choices (translation) must not change the user's own.
            val c = EditorController(sess, scope, ctx, models, null)
            ctrl = c
            if (s.blurOff) c.previewAvailable = false
            // Fast and selfie detection may miss a face: flagged for a look too, as the web app does.
            item.lowConfidence = !s.blurOff && (settings.fast || settings.selfie || BatchMath.lowConfidenceTracks(analysis.tracks, analysis.dets) > 0)

            // The project exists from here on, so a failure later can still be opened in the editor.
            val id = ProjectStore.newId()
            projectId = id
            val dir = ProjectStore.dir(ctx, id)
            sess.projectDir = dir
            export = withContext(Dispatchers.IO) {
                runCatching { s.export.copy(watermark = Watermark.withOwnCopy(s.export.watermark, dir)) }.getOrDefault(s.export)
            }
            val first = c.projectFields(s.subStyle, export)
                .put("v", 1)
                .put("name", info.name)
                .put("uri", item.uri.toString())
                .put("created", System.currentTimeMillis())
                .put("durationMs", info.durationMs)
                .put("subtitlesOnly", s.blurOff)
                .put("analysisMs", analysisMs)
                .put("batchId", batch.id)
                .put("batchAt", batch.startedAt)
            val edits0 = c.model.exportState()
            withContext(Dispatchers.IO) { ProjectStore.create(ctx, id, analysis, info.thumbnail, first, edits0) }
            item.projectId = id

            // The template's processing.
            if (s.hasProcessing) {
                item.begin(ItemStatus.PROCESSING)
                item.progress = analysisShare
                onChange()
                val notes = TemplateRunner.applyProcessing(c, s)
                item.failures = notes.failures
                item.warnings = notes.warnings
            }

            val noFaces = !s.blurOff && sess.model.tracks.isEmpty()
            if (noFaces && !worthSaving(ctx, batch, c)) {
                // Nothing would change in the video: no copy is saved; the project is there to open.
                saveProject(ctx, id, c, batch, export)
                item.status = ItemStatus.NO_FACES
                item.progress = 1f
                return
            }

            item.begin(ItemStatus.EXPORTING)
            item.progress = analysisShare + PROCESSING_SHARE
            onChange()
            if (com.elad.faceblur.edit.MaskPolicy.exportBlocked(c.background, c.peopleMasksOk)) throw com.elad.faceblur.pipeline.MissingMasksException()
            val saved = FaceBlurProcessor(ctx).render(
                info, sess.settings, analysis, sess.model, analysisMs, export, s.subStyle, c.audio, c.background, sess.masks,
                stabilizer = c.stabilizer,
            ) { p -> item.progress = analysisShare + PROCESSING_SHARE + p * (1f - analysisShare - PROCESSING_SHARE); onChange() }
            if (analysisMs > 0 && saved.renderMs > 0) prefs.renderRatio = saved.renderMs.toFloat() / analysisMs
            item.output = saved.uri
            saveProject(ctx, id, c, batch, export)
            item.progress = 1f
            item.status = if (noFaces) ItemStatus.NO_FACES else ItemStatus.SAVED
        } catch (e: CancellationException) {
            // Skipped or cancelled: the half-made project goes.
            item.status = ItemStatus.SKIPPED
            projectId?.let { id -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { ProjectStore.delete(ctx, id) } } }
            item.projectId = null
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ONIT", "batch video failed", e)
            item.status = ItemStatus.FAILED
            item.error = ProcessingErrors.friendly(e)
            item.details = ProcessingErrors.technical(e)
            // Kept as a project (when there is one) so the editor can try again.
            val id = projectId
            val c = ctrl
            if (id != null && c != null) {
                try {
                    saveProject(ctx, id, c, batch, export)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ignored: Exception) {
                    // The project stays as it was first saved.
                }
            } else item.projectId = null
        } finally {
            item.endedAt = SystemClock.elapsedRealtime()
            ctrl?.cancelSubtitles()
            ctrl?.cancelMotion()
            session?.close()
            onChange()
        }
    }

    /** Whether the system treats the app as on screen, and how hot the phone is: both can slow a long batch. */
    private fun environment(ctx: Context): String {
        val importance = runCatching {
            val i = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(i)
            when {
                i.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "app on screen"
                i.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
                else -> "background"
            }
        }.getOrDefault("?")
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus }.getOrDefault(-1)
        } else -1
        return importance + ", thermal " + thermal
    }

    /** What's in the editor now, into the project (on the IO thread; the state is copied first). */
    private suspend fun saveProject(ctx: Context, id: String, c: EditorController, batch: Batch, export: com.elad.faceblur.pipeline.ExportOptions) {
        val fields: JSONObject = c.projectFields(batch.settings.subStyle, export)
        val edits = c.model.exportState()
        withContext(Dispatchers.IO) { ProjectStore.save(ctx, id, fields, edits) }
    }

    /**
     * A video without faces is still saved if the processing changed it (what it really did, not
     * what the template asked for) or it's exported differently.
     */
    private fun worthSaving(ctx: Context, batch: Batch, c: EditorController): Boolean =
        TemplateRunner.changesVideo(c, batch.settings) || TemplateRunner.exportChangesVideo(ctx, batch.settings.export)

    private const val PROCESSING_SHARE = BatchMath.PROCESSING_SHARE
}
