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
import androidx.media3.common.C
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
import com.elad.faceblur.audio.CutAudioProcessor
import com.elad.faceblur.edit.StabilizeEffect
import com.elad.faceblur.edit.Stabilizer
import com.elad.faceblur.analysis.MotionMeter
import com.elad.faceblur.effects.CutEffect
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
import com.elad.faceblur.analysis.StatsSnapshot
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
    /** Temp files of this pass have names of their own, so two passes can never share one. */
    private val tag = java.util.UUID.randomUUID().toString().take(8)
    private fun temp(kind: String) = File(context.cacheDir, "${kind}_$tag.mp4")

    init {
        cleanStaleTemp(context)
    }

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

    /**
     * @param onPreview now and then: a small copy of the frame being analyzed, the faces found
     *   lately, where detection runs and the analysis's counters.
     */
    suspend fun analyze(
        info: VideoInfo,
        settings: Settings,
        onPreview: ((android.graphics.Bitmap, List<Det>, String, StatsSnapshot) -> Unit)? = null,
        onProgress: (Float) -> Unit,
    ): AnalysisResult = analyze(info, settings, onPreview, null, onProgress)

    /** As above; [onStats] gets the pass's final counters (also logged), for comparing runs. */
    suspend fun analyze(
        info: VideoInfo,
        settings: Settings,
        onPreview: ((android.graphics.Bitmap, List<Det>, String, StatsSnapshot) -> Unit)?,
        onStats: ((StatsSnapshot) -> Unit)?,
        onProgress: (Float) -> Unit,
    ): AnalysisResult {
        val analysisFile = temp("analysis")
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
            lastPace = analyzer.pace
            analyzer.snapshot().let { snap ->
                android.util.Log.i("ONIT", "analysis stats: ${snap.summary()}")
                onStats?.invoke(snap)
            }
            return withContext(Dispatchers.Default) { analyzer.result() }
        } finally {
            analyzer.close()
            analysisFile.delete()
        }
    }

    /**
     * The camera's motion without face detection (for steadying a video opened without it): a
     * pass that hands each frame, small, to a [MotionMeter].
     */
    suspend fun measureMotion(info: VideoInfo, onProgress: (Float) -> Unit): Stabilizer.Motion {
        val out = temp("motion")
        val meter = withContext(Dispatchers.IO) { MotionMeter() }
        try {
            val long = 640
            val landscape = info.displayWidth >= info.displayHeight
            val short = (long * minOf(info.displayWidth, info.displayHeight).toFloat() / maxOf(info.displayWidth, info.displayHeight, 1)).toInt().coerceAtLeast(16) / 2 * 2
            val grab = GrabEffect(
                if (landscape) long else short, if (landscape) short else long, info.rotation,
                displayLandscape = landscape,
            ) { buf, w, h, t, rot -> meter.onFrame(buf, w, h, t, rot) }
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
            val (times, motion, cuts) = meter.result()
            val aspect = if (meter.uprightW > 0) meter.uprightH.toFloat() / meter.uprightW else 1f
            return Stabilizer.Motion(times, motion, cuts, aspect, info.fps, meter.rotation)
        } finally {
            meter.close()
            out.delete()
        }
    }

    /**
     * Finds the people in every frame (for background removal): a pass over the video that
     * hands each frame, small, to the [Segmenter]. The masks go into [file].
     */
    suspend fun segment(info: VideoInfo, analysis: AnalysisResult, file: File, onProgress: (Float) -> Unit): MaskStore {
        val out = temp("segment")
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

    /** The sound's processors (shared by the video and the MP3 export, so both apply the same edits). */
    class AudioChain(val processors: List<AudioProcessor>, val sfx: List<SfxPlay>, val cutMap: com.elad.faceblur.edit.CutMap, val removeAudio: Boolean)

    private fun audioChain(model: EditModel, audio: AudioEdit): AudioChain {
        // The voice: the pitch, then bleeps and robot sound (so the beep isn't shifted).
        val audioFx = ArrayList<AudioProcessor>()
        val bleeps = model.bleeps.map { (a, b) -> longArrayOf(a, b) }
        val sfx = model.sfx.mapNotNull { c -> SfxLibrary.load(context, c.sound)?.let { SfxPlay(c.startMs * 1000, it, c.gain) } }
        val dsp = VoiceDsp(bleeps, audio.voice, audio.tone, sfx = sfx, denoise = audio.denoise, enhance = audio.enhance, gain = audio.gain)
        // Stretches cut out: their frames and their sound are dropped at the end of the chains.
        val cutMap = model.cutMap()
        // No sound at all: the track is dropped, unless sound effects play over the silence.
        val removeAudio = audio.voice == VoiceFx.MUTE && sfx.isEmpty()
        if (!removeAudio) {
            if (audio.voice.pitch != 1f) audioFx += SonicAudioProcessor().apply { setPitch(audio.voice.pitch) }
            if (dsp.active) audioFx += VoiceAudioProcessor(dsp)
            if (!cutMap.isEmpty) audioFx += CutAudioProcessor(cutMap)
        }
        return AudioChain(audioFx, sfx, cutMap, removeAudio)
    }

    /**
     * Only the sound, with the edits of the export (voice, bleeps, sound effects, cleanup, cuts),
     * as a temporary file for the MP3 encoder. Throws if they can't be applied: the caller must
     * not save the original sound instead.
     */
    suspend fun renderAudio(info: VideoInfo, model: EditModel, audio: AudioEdit, onProgress: (Float) -> Unit): File {
        val chain = audioChain(model, audio)
        check(!chain.removeAudio) { t("אין סאונד לשמירה: הסאונד מושתק") }
        val out = temp("audio")
        out.delete()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
            .setRemoveVideo(true)
            .setEffects(Effects(chain.processors, emptyList()))
            .build()
        try {
            export(composition(item, Composition.HDR_MODE_KEEP_HDR, forceAudio = chain.sfx.isNotEmpty()), out, null, null, onProgress)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return out
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
        /** Steadies the camera (see Stabilizer); null: as filmed. */
        stabilizer: Stabilizer? = null,
        /** Now and then, a small copy of the finished frame being saved. */
        onPreview: ((android.graphics.Bitmap) -> Unit)? = null,
        onProgress: (Float) -> Unit,
    ): Outcome.Saved {
        // No caller may save without a people background that is set: it would silently be left out.
        if (background.active && background.key == BgKey.PERSON && masks == null) throw MissingMasksException()
        val t1 = SystemClock.elapsedRealtime()
        val timeline = FaceTimeline.build(
            model.renderTracks(),
            analysis.times,
            analysis.cuts.map { analysis.times[it] }.toLongArray(),
            analysis.uprightW, analysis.uprightH, analysis.rotation, settings.padding,
        )
        val out = temp("render")
        out.delete()
        val blur = BlurEffect(timeline, settings.mode, settings.strength)
        // Blur at full resolution first, then resize / drop frames for the chosen output.
        val cues = model.exportCues(audio.maskWords)
        val burn = cues.mapNotNull { c ->
            // exportCues already left out a translation that may carry a bleeped word.
            c.blocks(export.subs, emptySet(), translation = true).takeIf { it.isNotEmpty() }?.let { BurnCue(c.startMs * 1000, c.endMs * 1000, it, c.blockTimes(export.subs, translation = true), c.speaker) }
        }
        val overlays = ArrayList<TextureOverlay>()
        if (burn.isNotEmpty()) overlays += SubtitleOverlay(burn, subStyle, SubtitleRenderer.get(context))
        if (export.watermark.enabled(context)) overlays += WatermarkOverlay(context, export.watermark)
        val subsEffect = if (overlays.isEmpty()) emptyList() else listOf<Effect>(OverlayEffect(ImmutableList.copyOf(overlays)))
        val chain = audioChain(model, audio)
        val audioFx = chain.processors
        val sfx = chain.sfx
        val cutMap = chain.cutMap
        val removeAudio = chain.removeAudio
        val steady = stabilizer
        // Steadied right after the blur (which follows the faces in the shaky frame), before
        // anything drawn on top: subtitles and the watermark don't move.
        val steadyEffect = steady?.let { s -> listOf<Effect>(StabilizeEffect { s }) } ?: emptyList()
        val cutEffect = if (cutMap.isEmpty) emptyList() else listOf<Effect>(CutEffect(cutMap))
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
                    bgEffect + listOf<Effect>(blur) + steadyEffect + export.effects(info, reframe) + subsEffect + cutEffect +
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
        val base = "${info.name}_blur_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // The temporary file goes whatever happens (each pass has its own, so a failed or
        // cancelled one isn't overwritten by the next any more).
        val saved = try {
            runWithHdrFallback(modes) { hdrMode ->
                keptHdr = info.isHdr && hdrMode == Composition.HDR_MODE_KEEP_HDR
                // A video without sound still gets one for its sound effects.
                export(composition(item, hdrMode, forceAudio = sfx.isNotEmpty()), out, bitrate, mime = export.mimeType(), onProgress)
            }
            withContext(Dispatchers.IO) {
                // No location or phone details in what's shared: if they can't be taken out, nothing is saved (the temp file goes below).
                lastScrubbed = Mp4Privacy.scrubOrFail(out)
                saveToGallery(out, base)
            }
        } finally {
            out.delete()
        }
        // Subtitle files in the cut video's time.
        val srtCues = if (cutMap.isEmpty) cues else cues.mapNotNull { c ->
            cutMap.mapMs(c.startMs, c.endMs)?.let { (a, b) -> c.copy().also { it.startMs = a; it.endMs = b } }
        }
        val srts = if (export.saveSrt && srtCues.isNotEmpty()) withContext(Dispatchers.IO) {
            listOfNotNull(Srt.save(context, base, srtCues, english = false), Srt.save(context, base, srtCues, english = true)).size
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
            // A slow device (or the face detector warming up on its GPU) can go more than 10 s
            // without a finished frame; that's slow, not stuck, so the watchdog is off.
            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
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
        /** Temp videos left behind by a pass that was killed (older than a few hours). */
        private fun cleanStaleTemp(context: Context) {
            val old = System.currentTimeMillis() - 6 * 3600_000L
            context.cacheDir.listFiles { f -> f.isFile && f.lastModified() < old && Regex("((analysis|motion|segment|render|audio)_[0-9a-f]{8}\\.mp4|frames_[0-9a-f]{8}\\.bin)").matches(f.name) }
                ?.forEach { it.delete() }
        }

        /** Where the last analysis ran its face detection ("gpu", "gpu-checking" or "cpu"). */
        @Volatile var lastAccelerator = "cpu"

        /** The precision of the last analysis's CPU detection ("fp16", "fp16-checking", "fp32"). */
        @Volatile
        var lastPrecision = "fp32"

        /** How often the last analysis ran face detection ("every 1", "every 3, lite"). */
        @Volatile
        var lastPace = ""

        /** Location / phone-detail boxes hidden in the last export (-1: couldn't check). */
        @Volatile
        var lastScrubbed = 0

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
