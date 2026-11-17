package com.elad.faceblur.pipeline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import com.elad.faceblur.detect.FaceDetectorEngine
import com.elad.faceblur.effects.BlurEffect
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.effects.DetectEffect
import com.elad.faceblur.effects.DetectionSink
import com.elad.faceblur.track.FaceTimeline
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
    class Saved(val uri: Uri, val faces: Int, val coverage: Float, val keptHdr: Boolean) : Outcome
    data object NoFaces : Outcome
}

/**
 * Two passes, both through Media3 Transformer (hardware decode/encode, GPU effects):
 *  1. Analysis: detect faces on downscaled frames (output discarded).
 *  2. Render: blur/pixelate the tracked faces at full resolution, keep audio, keep HDR if possible.
 *
 * Must be called on the main thread (Transformer's application thread).
 */
class FaceBlurProcessor(private val context: Context) {

    suspend fun process(
        info: VideoInfo,
        settings: Settings,
        onStage: (Int) -> Unit,
        onProgress: (Float) -> Unit,
    ): Outcome {
        val cache = context.cacheDir

        // ---- Pass 1: analysis ----
        onStage(1)
        val sink = DetectionSink()
        val engine = FaceDetectorEngine(settings.sensitive)
        val analysisFile = File(cache, "analysis.mp4")
        try {
            val intervalUs = if (settings.sensitive) 66_000L else 33_000L
            val maxSide = if (settings.sensitive) 1920 else 960
            val detect = DetectEffect(
                engine, sink, intervalUs, maxSide, info.rotation,
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
                sink.clear()
                export(composition(item, hdrMode), analysisFile, bitrate = 1_500_000, mime = MimeTypes.VIDEO_H264, onProgress)
            }
        } finally {
            engine.close()
            analysisFile.delete()
        }

        val frames = sink.snapshot()
        val aspect = if (sink.frameHeight > 0) sink.frameWidth.toFloat() / sink.frameHeight else info.aspect
        val timeline = FaceTimeline.build(frames, aspect, settings.padding)
        if (timeline.trackCount == 0) return Outcome.NoFaces
        val coverage = frames.count { it.boxes.isNotEmpty() }.toFloat() / frames.size.coerceAtLeast(1)

        // ---- Pass 2: render ----
        onStage(2)
        onProgress(0f)
        val out = File(cache, "render.mp4")
        out.delete()
        val blur = BlurEffect(timeline, settings.mode, settings.strength)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
            .setEffects(Effects(emptyList(), listOf<Effect>(blur)))
            .build()
        val bitrate = if (info.bitrate > 0) (info.bitrate * 0.95f).toInt().coerceIn(4_000_000, 120_000_000) else null
        val modes = if (info.isHdr) {
            listOf(Composition.HDR_MODE_KEEP_HDR, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        } else {
            listOf(Composition.HDR_MODE_KEEP_HDR)
        }
        var keptHdr = info.isHdr
        runWithHdrFallback(modes) { hdrMode ->
            keptHdr = info.isHdr && hdrMode == Composition.HDR_MODE_KEEP_HDR
            export(composition(item, hdrMode), out, bitrate, mime = null, onProgress)
        }

        val saved = withContext(Dispatchers.IO) { saveToGallery(out, "${info.name}_blur") }
        out.delete()
        return Outcome.Saved(saved, timeline.trackCount, coverage, keptHdr)
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
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${baseName}_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceBlur")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("לא ניתן לשמור בגלריה")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
