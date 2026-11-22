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
import com.elad.faceblur.analysis.Det
import com.elad.faceblur.analysis.FaceAnalyzer
import com.elad.faceblur.edit.EditModel
import com.elad.faceblur.edit.toEditModel
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.analysis.MaskStore
import com.elad.faceblur.analysis.Segmenter
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BackgroundEffect
import com.elad.faceblur.effects.BackgroundState
import com.elad.faceblur.effects.BgKey
import com.elad.faceblur.effects.GrabEffect
import com.elad.faceblur.effects.PreviewEffect
import com.elad.faceblur.audio.VoiceAudioProcessor
import com.elad.faceblur.audio.VoiceDsp
import com.elad.faceblur.audio.SfxLibrary
import com.elad.faceblur.audio.SfxPlay
import com.elad.faceblur.audio.VoiceFx
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.elad.faceblur.track.FaceTimeline
import com.elad.faceblur.effects.BlurEffect
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.effects.DetectEffect
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.subs.BurnCue
import com.elad.faceblur.subs.Srt
import com.elad.faceblur.subs.SubtitleOverlay
import com.elad.faceblur.subs.SubtitleRenderer
import com.elad.faceblur.subs.SubtitleStyle
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
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
        /** Number of .srt files saved (Download/ONIT). */
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
        analyze(info, settings, onProgress = onProgress).use { analysis ->
            val analysisMs = SystemClock.elapsedRealtime() - t0
            val model = analysis.toEditModel()
            if (model.tracks.isEmpty()) return Outcome.NoFaces
            onStage(2)
            onProgress(0f)
            return render(info, settings, analysis, model, analysisMs, export, onProgress = onProgress)
        }
    }

    /** @param onPreview now and then: a small copy of the frame being analyzed and the faces found lately. */
    suspend fun analyze(
        info: VideoInfo,
        settings: Settings,
        onPreview: ((android.graphics.Bitmap, List<Det>, String) -> Unit)? = null,
        onProgress: (Float) -> Unit,
    ): AnalysisResult {
        val analysisFile = File(context.cacheDir, "analysis.mp4")
        val analyzer = withContext(Dispatchers.IO) { FaceAnalyzer(context, settings.sensitive, info.fps) }
        analyzer.onPreview = onPreview
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
            lastAccelerator = analyzer.accelerator
            lastPrecision = analyzer.precision
            return withContext(Dispatchers.Default) { analyzer.result() }
        } finally {
            analyzer.close()
            analysisFile.delete()
        }
    }

    /**
     * Finds the people in every frame (for background removal): a pass over the video that
     * hands each frame, small, to the [Segmenter]. The masks go into [file].
     */
    suspend fun segment(info: VideoInfo, analysis: AnalysisResult, file: File, onProgress: (Float) -> Unit): MaskStore {
        val out = File(context.cacheDir, "segment.mp4")
        val store = MaskStore.create(file, analysis.frameCount)
        val segmenter = withContext(Dispatchers.IO) { Segmenter(context, analysis.times, analysis.cuts, store) }
        try {
            // The frame, small but undistorted (the segmenter cuts square tiles from it).
            val long = Segmenter.GRAB_LONG
            val landscape = info.displayWidth >= info.displayHeight
            val short = (long * minOf(info.displayWidth, info.displayHeight).toFloat() / maxOf(info.displayWidth, info.displayHeight, 1)).toInt().coerceAtLeast(16) / 2 * 2
            val grab = GrabEffect(
                if (landscape) long else short, if (landscape) short else long, info.rotation,
                displayLandscape = landscape,
            ) { buf, w, h, t, rot -> segmenter.onFrame(buf, w, h, t, rot) }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf<Effect>(grab)))
                .build()
            runWithHdrFallback(
                listOf(
                    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                    Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
                ),
            ) { hdrMode ->
                export(composition(item, hdrMode), out, bitrate = 500_000, mime = MimeTypes.VIDEO_H264, onProgress)
            }
            segmenter.finish()
            lastSegModels = segmenter.models
            store.saveIndex(File(file.parentFile, file.name + ".idx"))
            return store
        } catch (e: Throwable) {
            store.close()
            throw e
        } finally {
            segmenter.close()
            out.delete()
        }
    }

    suspend fun render(
        info: VideoInfo,
        settings: Settings,
        analysis: AnalysisResult,
        model: EditModel,
        analysisMs: Long,
        export: ExportOptions = ExportOptions(),
        subStyle: SubtitleStyle = SubtitleStyle(),
        audio: AudioEdit = AudioEdit(),
        background: Background = Background(),
        masks: MaskStore? = null,
        /** Now and then, a small copy of the finished frame being saved. */
        onPreview: ((android.graphics.Bitmap) -> Unit)? = null,
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
        val cues = model.exportCues(audio.maskWords)
        val burn = cues.mapNotNull { c ->
            c.blocks(export.subs).takeIf { it.isNotEmpty() }?.let { BurnCue(c.startMs * 1000, c.endMs * 1000, it, c.blockTimes(export.subs), c.speaker) }
        }
        val overlays = ArrayList<TextureOverlay>()
        if (burn.isNotEmpty()) overlays += SubtitleOverlay(burn, subStyle, SubtitleRenderer.get(context))
        if (export.watermark.enabled(context)) overlays += WatermarkOverlay(context, export.watermark)
        val subsEffect = if (overlays.isEmpty()) emptyList() else listOf<Effect>(OverlayEffect(ImmutableList.copyOf(overlays)))
        // The voice: the pitch, then bleeps and robot sound (so the beep isn't shifted).
        val audioFx = ArrayList<AudioProcessor>()
        val bleeps = model.bleeps.map { (a, b) -> longArrayOf(a, b) }
        val sfx = model.sfx.mapNotNull { c -> SfxLibrary.load(context, c.sound)?.let { SfxPlay(c.startMs * 1000, it, c.gain) } }
        val dsp = VoiceDsp(bleeps, audio.voice, audio.tone, sfx = sfx)
        // No sound at all: the track is dropped, unless sound effects play over the silence.
        val removeAudio = audio.voice == VoiceFx.MUTE && sfx.isEmpty()
        if (!removeAudio) {
            if (audio.voice.pitch != 1f) audioFx += SonicAudioProcessor().apply { setPitch(audio.voice.pitch) }
            if (dsp.active) audioFx += VoiceAudioProcessor(dsp)
        }
        // The background: replaced or blurred first, then faces are blurred over it.
        val bgEffect = if (background.active && (background.key != BgKey.PERSON || masks != null)) {
            listOf<Effect>(BackgroundEffect(BackgroundState(background, masks, analysis.times)))
        } else {
            emptyList()
        }
        // A vertical / square frame follows the faces.
        val reframe = if (export.aspect != ExportAspect.ORIGINAL) {
            val (cw, ch) = export.crop(info)
            val (px, py) = model.reframePath(settings.padding, cw, ch)
            ReframeEffect(analysis.times, px, py, cw, ch)
        } else {
            null
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
            .setRemoveAudio(removeAudio)
            .setEffects(
                Effects(
                    audioFx,
                    bgEffect + listOf<Effect>(blur) + export.effects(info, reframe) + subsEffect +
                        listOfNotNull<Effect>(onPreview?.let { PreviewEffect(onFrame = it) }),
                ),
            )
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
            // A video without sound still gets one for its sound effects.
            export(composition(item, hdrMode, forceAudio = sfx.isNotEmpty()), out, bitrate, mime = export.mimeType(), onProgress)
        }

        val base = "${info.name}_blur_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val saved = withContext(Dispatchers.IO) { saveToGallery(out, base) }
        out.delete()
        val srts = if (export.saveSrt && cues.isNotEmpty()) withContext(Dispatchers.IO) {
            listOfNotNull(Srt.save(context, base, cues, english = false), Srt.save(context, base, cues, english = true)).size
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

    private fun composition(item: EditedMediaItem, hdrMode: Int, forceAudio: Boolean = false): Composition =
        Composition.Builder(listOf(EditedMediaItemSequence(listOf(item))))
            .experimentalSetForceAudioTrack(forceAudio)
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
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ONIT")
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

    companion object {
        /** Where the last analysis ran its face detection ("gpu", "gpu-checking" or "cpu"). */
        @Volatile var lastAccelerator = "cpu"

        /** The precision of the last analysis's CPU detection ("fp16", "fp16-checking", "fp32"). */
        @Volatile
        var lastPrecision = "fp32"

        /** The models the last background pass used (for the self-test's report). */
        @Volatile
        var lastSegModels = ""

        /**
         * The video's frame times only (from the container, no decoding), for editing
         * subtitles without face detection.
         */
        fun framesOnly(context: Context, info: VideoInfo): AnalysisResult {
            val ex = android.media.MediaExtractor()
            val times = ArrayList<Long>()
            try {
                ex.setDataSource(context, info.uri, null)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: error(t("לא נמצא וידאו בקובץ"))
                ex.selectTrack(track)
                while (true) {
                    val tUs = ex.sampleTime
                    if (tUs < 0) break
                    times += tUs
                    if (!ex.advance()) break
                }
            } finally {
                ex.release()
            }
            times.sort()
            if (times.isEmpty()) error(t("לא נמצא וידאו בקובץ"))
            return AnalysisResult(
                times.toLongArray(), IntArray(0), FloatArray(times.size * 2), emptyMap(), emptyList(),
                info.displayWidth, info.displayHeight, info.rotation, info.fps, null,
            )
        }
    }
}
