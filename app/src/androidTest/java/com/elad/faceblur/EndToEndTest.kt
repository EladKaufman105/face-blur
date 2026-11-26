package com.elad.faceblur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Outcome
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the whole pipeline on the sample clips (CI copies them into assets/samples) and writes
 * side-by-side frame sheets + the processed videos for inspection.
 *
 * By default only the runs with something no other test covers: the rotated clip, pixelate, scene
 * cuts, sensitive detection, fast detection with a sensitivity and the export with options (the other clips are exported by other
 * tests). `-e full true` (the weekly run) does all 13.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    /** The model packs, from the test assets (ci/fetch-models.sh), installed as the app installs a download. */
    @org.junit.Before
    fun seedModels() = com.elad.faceblur.models.ModelSeed.install()


    @Test
    fun processSamples() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        val outDir = File(ctx.getExternalFilesDir(null), "results").apply { deleteRecursively(); mkdirs() }
        val log = StringBuilder()
        val failures = ArrayList<String>()
        val names = (assets.list("samples") ?: emptyArray()).filter { it.endsWith(".mp4") }.sorted()
        log.appendLine("samples: $names")

        val args = InstrumentationRegistry.getArguments()
        val full = args.getString("full") == "true"
        // A run: the clip, its settings, whether it's the export with options.
        data class Run(val name: String, val settings: Settings, val withOpts: Boolean = false)
        val all = ArrayList<Run>()
        for (name in names) all += Run(name, Settings(BlurMode.BLUR, 0.6f, 1.6f, false))
        names.firstOrNull { it.startsWith("headpose") }?.let { all += Run(it, Settings(BlurMode.PIXELATE, 0.6f, 1.6f, false)) }
        names.firstOrNull { it.startsWith("classroom.") }?.let { all += Run(it, Settings(BlurMode.BLUR, 0.6f, 1.6f, true)) }

        // One export with options: smaller, lower frame rate, H.264.
        val exportRun = names.firstOrNull { it.startsWith("classroom60") }
        val exportOpts = ExportOptions(shortSide = 720, fps = 30, quality = ExportQuality.SAVER, codec = ExportCodec.H264)
        if (exportRun != null) all += Run(exportRun, Settings(BlurMode.BLUR, 0.6f, 1.6f, false), withOpts = true)
        // Fast detection (5 a second) with a sensitivity above 0: the whole path (the second YuNet, the extra
        // detections and their crops, the tracker's steps) on a short clip with faces. Last, so the CI's
        // two parts keep their runs (this one joins part 1).
        names.firstOrNull { it.startsWith("walk") }?.let { all += Run(it, Settings(BlurMode.BLUR, 0.6f, 1.6f, false, fast = true, sensitivity = 0.5f)) }

        // Without `full`: portrait_rot90 (rotation), headpose pixelated, stress_cuts (scene cuts),
        // classroom sensitive, walk fast at sensitivity 50% and classroom60 with options. walk,
        // speech_*, green_screen and plain classroom are exported by other tests.
        val runs = if (full) all else all.filter { r ->
            r.withOpts || r.settings.sensitive || r.settings.fast || r.settings.sensitivity > 0f || r.settings.mode == BlurMode.PIXELATE ||
                r.name.startsWith("portrait_rot90") || r.name.startsWith("stress_cuts")
        }
        log.appendLine("runs (${if (full) "full" else "default"}): ${runs.size} of ${all.size}")

        // CI runs this test in parts on parallel emulators: -e e2eShards N -e e2eShard I (0-based)
        // takes every Nth run. Without them every run is done.
        val shards = args.getString("e2eShards")?.toIntOrNull() ?: 1
        val shard = args.getString("e2eShard")?.toIntOrNull() ?: 0

        for ((idx, run) in runs.withIndex()) {
            if (shards > 1 && idx % shards != shard) continue
            val (name, settings, withOpts) = run
            val tag = name.removeSuffix(".mp4") + "_" + settings.mode.name.lowercase() +
                (if (settings.sensitive) "_sens" else "") + (if (settings.fast) "_fast" else "") +
                (if (settings.sensitivity > 0f) "_s${(settings.sensitivity * 100).roundToInt()}" else "") + (if (withOpts) "_720p30" else "")
            try {
                val src = File(ctx.cacheDir, name)
                assets.open("samples/$name").use { i -> src.outputStream().use { i.copyTo(it) } }
                val info = VideoInfo.probe(ctx, Uri.fromFile(src))
                log.appendLine("== $tag: ${info.width}x${info.height} rot=${info.rotation} fps=${info.fps} dur=${info.durationMs}ms")
                val outcome = runBlocking(Dispatchers.Main) {
                    FaceBlurProcessor(ctx).process(info, settings, {}, {}, if (withOpts) exportOpts else ExportOptions())
                }
                when (outcome) {
                    is Outcome.Saved -> {
                        log.appendLine(
                            "   tracks=${outcome.faces} frames=${outcome.frames} analysis=${outcome.analysisMs}ms " +
                                "render=${outcome.renderMs}ms"
                        )
                        val video = File(outDir, "$tag.mp4")
                        ctx.contentResolver.openInputStream(outcome.uri)!!.use { i -> video.outputStream().use { i.copyTo(it) } }
                        val mmr = MediaMetadataRetriever().apply { setDataSource(video.absolutePath) }
                        val ow = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val oh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val orot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        val ofr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                        val odur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        mmr.release()
                        val ofps = if (odur > 0) ofr * 1000f / odur else 0f
                        log.appendLine("   out ${ow}x$oh rot=$orot fps=${"%.1f".format(ofps)} size=${video.length() / 1024}KB")
                        if (withOpts) {
                            if (minOf(ow, oh) != 720) failures += "$tag: short side ${minOf(ow, oh)}"
                            if (ofps > 32f) failures += "$tag: fps $ofps"
                        }
                        sheet(Uri.fromFile(src), outcome.uri, info.durationMs, File(outDir, "$tag.jpg"))
                    }
                    Outcome.NoFaces -> {
                        log.appendLine("   NO FACES")
                        // walk has two people's faces: fast detection must still find them.
                        if (settings.fast) failures += "$tag: no faces in fast detection"
                    }
                }
            } catch (e: Throwable) {
                failures += tag
                log.appendLine("   FAILED: $e")
                e.stackTrace.take(12).forEach { log.appendLine("      at $it") }
                var c = e.cause
                while (c != null) {
                    log.appendLine("   caused by: $c")
                    c.stackTrace.take(8).forEach { log.appendLine("      at $it") }
                    c = c.cause
                }
            }
            File(outDir, "log.txt").writeText(log.toString())
        }
        File(outDir, "log.txt").writeText(log.toString())
        if (names.isEmpty()) fail("no sample videos in androidTest assets")
        if (failures.isNotEmpty()) fail("failed: $failures")
    }

    /** Original | processed, at 6 evenly spaced times. */
    private fun sheet(src: Uri, out: Uri, durationMs: Long, file: File) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val a = MediaMetadataRetriever().apply { setDataSource(ctx, src) }
        val b = MediaMetadataRetriever().apply { setDataSource(ctx, out) }
        val rows = 6
        val cellW = 480
        val frames = (0 until rows).map { r ->
            val tUs = (durationMs * 1000L * (r + 0.5) / rows).toLong()
            Pair(
                a.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST),
                b.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST),
            )
        }
        val first = frames.firstNotNullOfOrNull { it.second ?: it.first } ?: return
        val cellH = cellW * first.height / first.width
        val bmp = Bitmap.createBitmap(cellW * 2, cellH * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { r, (o, p) ->
            o?.let { canvas.drawBitmap(Bitmap.createScaledBitmap(it, cellW, cellH, true), 0f, (r * cellH).toFloat(), paint) }
            p?.let { canvas.drawBitmap(Bitmap.createScaledBitmap(it, cellW, cellH, true), cellW.toFloat(), (r * cellH).toFloat(), paint) }
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        a.release()
        b.release()
    }
}
