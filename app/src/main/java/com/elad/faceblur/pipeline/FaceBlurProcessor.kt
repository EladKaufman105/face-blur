package com.elad.faceblur.pipeline

import com.elad.faceblur.i18n.t
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.elad.faceblur.analysis.AnalysisResult
import com.elad.faceblur.analysis.FaceAnalyzer
import com.elad.faceblur.edit.EditModel
import com.elad.faceblur.edit.toEditModel
import com.elad.faceblur.track.FaceTimeline
import com.elad.faceblur.effects.BlurEffect
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.effects.DetectEffect
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.subs.BurnCue
import com.elad.faceblur.subs.Srt
import com.elad.faceblur.subs.SubtitleOverlay
import androidx.media3.effect.OverlayEffect
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Settings(
    val mode: BlurMode,
    val strength: Float,
    val padding: Float,
    val sensitive: Boolean,
)

sealed interface Outcome {
    class Saved(
        val uri: Uri,
        val faces: Int,
        val keptHdr: Boolean,
        val frames: Int,
        val analysisMs: Long,
        val renderMs: Long,
        /** Number of .srt files saved (Download/FaceBlur). */
        val srtFiles: Int = 0,
    ) : Outcome
    data object NoFaces : Outcome
}

/**
 * Two passes, both through Media3 Transformer (hardware decode/encode, GPU effects):
 *  1. [analyze]: detect faces, measure camera motion and cuts on every frame (output discarded),
 *     then track offline. The result can be edited ([EditModel]) before rendering.
 *  2. [render]: blur/pixelate the blurs at full resolution, keep audio, keep HDR if possible.
 *
 * Must be called on the main thread (Transformer's application thread).
 */
class FaceBlurProcessor(private val context: Context) {

    /** Analysis + render without editing. */
    suspend fun process(
        info: VideoInfo,
        settings: Settings,
        onStage: (Int) -> Unit,
        onProgress: (Float) -> Unit,
        export: ExportOptions = ExportOptions(),
    ): Outcome {
        onStage(1)
        val t0 = SystemClock.elapsedRealtime()
        analyze(info, settings, onProgress).use { analysis ->
            val analysisMs = SystemClock.elapsedRealtime() - t0
            val model = analysis.toEditModel()
            if (model.tracks.isEmpty()) return Outcome.NoFaces
            onStage(2)
            onProgress(0f)
            return render(info, settings, analysis, model, analysisMs, export, onProgress)
        }
    }

    suspend fun analyze(info: VideoInfo, settings: Settings, onProgress: (Float) -> Unit): AnalysisResult {
        val analysisFile = File(context.cacheDir, "analysis.mp4")
        val analyzer = withContext(Dispatchers.IO) { FaceAnalyzer(context, settings.sensitive, info.fps) }
        try {
            val detect = DetectEffect(
                analyzer, info.rotation,
                displayLandscape = info.displayWidth >= info.displayHeight,
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf<Effect>(detect)))
                .build()
            runWithHdrFallback(
                listOf(
                    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                    Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
                ),
            ) { hdrMode ->
                analyzer.reset()
                export(composition(item, hdrMode), analysisFile, bitrate = 1_500_000, mime = MimeTypes.VIDEO_H264, onProgress)
            }
            return withContext(Dispatchers.Default) { analyzer.result() }
        } finally {
            analyzer.close()
            analysisFile.delete()
        }
    }

    suspend fun render(
        info: VideoInfo,
        settings: Settings,
        analysis: AnalysisResult,
        model: EditModel,
        analysisMs: Long,
        export: ExportOptions = ExportOptions(),
        onProgress: (Float) -> Unit,
    ): Outcome.Saved {
        val t1 = SystemClock.elapsedRealtime()
        val timeline = FaceTimeline.build(
            model.renderTracks(),
            analysis.times,
            analysis.cuts.map { analysis.times[it] }.toLongArray(),
            analysis.uprightW, analysis.uprightH, analysis.rotation, settings.padding,
        )
        val out = File(context.cacheDir, "render.mp4")
        out.delete()
        val blur = BlurEffect(timeline, settings.mode, settings.strength)
        // Blur at full resolution first, then resize / drop frames for the chosen output.
        val burn = if (export.subs == SubLang.NONE) emptyList() else model.cues.mapNotNull { c ->
            c.text(export.subs).takeIf { it.isNotBlank() }?.let { BurnCue(c.startMs * 1000, c.endMs * 1000, it) }
        }
        val subsEffect = if (burn.isEmpty()) emptyList() else listOf<Effect>(OverlayEffect(ImmutableList.of(SubtitleOverlay(burn))))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
            .setEffects(Effects(emptyList(), listOf<Effect>(blur) + export.effects(info) + subsEffect))
            .build()
        val bitrate = export.videoBitrate(info)
        val modes = when {
            !info.isHdr -> listOf(Composition.HDR_MODE_KEEP_HDR)
            export.canKeepHdr(info) -> listOf(Composition.HDR_MODE_KEEP_HDR, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            else -> listOf(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        var keptHdr = info.isHdr
        runWithHdrFallback(modes) { hdrMode ->
            keptHdr = info.isHdr && hdrMode == Composition.HDR_MODE_KEEP_HDR
            export(composition(item, hdrMode), out, bitrate, mime = export.mimeType(), onProgress)
        }

        val base = "${info.name}_blur_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val saved = withContext(Dispatchers.IO) { saveToGallery(out, base) }
        out.delete()
        val srts = if (export.saveSrt && model.cues.isNotEmpty()) withContext(Dispatchers.IO) {
            listOfNotNull(Srt.save(context, base, model.cues, english = false), Srt.save(context, base, model.cues, english = true)).size
        } else 0
        val renderMs = SystemClock.elapsedRealtime() - t1
        return Outcome.Saved(saved, timeline.trackCount, keptHdr, analysis.frameCount, analysisMs, renderMs, srts)
    }

    private suspend fun runWithHdrFallback(modes: List<Int>, block: suspend (Int) -> Unit) {
        var last: ExportException? = null
        for (mode in modes) {
            try {
                block(mode)
                return
            } catch (e: ExportException) {
                last = e
            }
        }
        throw last ?: IllegalStateException("export failed")
    }

    private fun composition(item: EditedMediaItem, hdrMode: Int): Composition =
        Composition.Builder(listOf(EditedMediaItemSequence(listOf(item))))
            .setHdrMode(hdrMode)
            .build()

    private suspend fun export(
        composition: Composition,
        output: File,
        bitrate: Int?,
        mime: String?,
        onProgress: (Float) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        lateinit var poll: Runnable

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .apply {
                if (bitrate != null) {
                    setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                }
            }
            .build()

        val transformer = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory)
            .apply { if (mime != null) setVideoMimeType(mime) }
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(poll)
                    onProgress(1f)
                    if (cont.isActive) cont.resume(exportResult)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacks(poll)
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()

        poll = Runnable {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(holder.progress / 100f)
            }
            handler.postDelayed(poll, 200)
        }

        output.delete()
        transformer.start(composition, output.absolutePath)
        handler.postDelayed(poll, 200)

        cont.invokeOnCancellation {
            handler.post {
                handler.removeCallbacks(poll)
                transformer.cancel()
            }
        }
    }

    private fun saveToGallery(file: File, baseName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceBlur")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException(t("לא ניתן לשמור בגלריה"))
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
