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
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the whole pipeline on the sample clips (CI copies them into assets/samples) and writes
 * side-by-side frame sheets + the processed videos for inspection.
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

        val runs = ArrayList<Pair<String, Settings>>()
        for (name in names) runs += name to Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        names.firstOrNull { it.startsWith("headpose") }?.let { runs += it to Settings(BlurMode.PIXELATE, 0.6f, 1.6f, false) }
        names.firstOrNull { it.startsWith("classroom.") }?.let { runs += it to Settings(BlurMode.BLUR, 0.6f, 1.6f, true) }

        // One export with options: smaller, lower frame rate, H.264.
        val exportRun = names.firstOrNull { it.startsWith("classroom60") }
        val exportOpts = ExportOptions(shortSide = 720, fps = 30, quality = ExportQuality.SAVER, codec = ExportCodec.H264)
        if (exportRun != null) runs += exportRun to Settings(BlurMode.BLUR, 0.6f, 1.6f, false)

        // CI runs this test in parts on parallel emulators: -e e2eShards N -e e2eShard I (0-based)
        // takes every Nth run. Without them every run is done.
        val args = InstrumentationRegistry.getArguments()
        val shards = args.getString("e2eShards")?.toIntOrNull() ?: 1
        val shard = args.getString("e2eShard")?.toIntOrNull() ?: 0

        for ((idx, run) in runs.withIndex()) {
            if (shards > 1 && idx % shards != shard) continue
            val (name, settings) = run
            val withOpts = exportRun != null && idx == runs.size - 1
            val tag = name.removeSuffix(".mp4") + "_" + settings.mode.name.lowercase() +
                (if (settings.sensitive) "_sens" else "") + (if (withOpts) "_720p30" else "")
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
                    Outcome.NoFaces -> log.appendLine("   NO FACES")
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
