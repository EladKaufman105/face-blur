package com.elad.faceblur

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.subs.AudioDecoder
import com.elad.faceblur.subs.ModelManager
import com.elad.faceblur.subs.Srt
import com.elad.faceblur.subs.SubtitleEngine
import com.elad.faceblur.ui.EditorScreen
import com.elad.faceblur.ui.EditorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Subtitles on a clip with English speech (CI puts whisper's "tiny" model in the test assets):
 * audio decoding, transcription, translation (if the language pack can be downloaded), the
 * editor's subtitle lane and sheet, and an export with burned-in subtitles and .srt files.
 */
@RunWith(AndroidJUnit4::class)
class SubtitlesTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun transcribeEditAndBurn() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        val outDir = File(ctx.getExternalFilesDir(null), "subs").apply { deleteRecursively(); mkdirs() }
        val log = StringBuilder()
        fun note(s: String) {
            log.appendLine(s)
            File(outDir, "log.txt").writeText(log.toString())
        }
        if ("speech_en.mp4" !in (assets.list("samples") ?: emptyArray())) fail("no speech sample")
        val src = File(ctx.cacheDir, "speech_en.mp4")
        assets.open("samples/speech_en.mp4").use { i -> src.outputStream().use { i.copyTo(it) } }
        val model = File(ctx.cacheDir, "ggml-tiny.bin")
        assets.open("models/ggml-tiny.bin").use { i -> model.outputStream().use { i.copyTo(it) } }
        val uri = Uri.fromFile(src)

        // Audio: 44.1 kHz stereo AAC -> 16 kHz mono.
        val pcm = AudioDecoder.decode(ctx, uri, { true }) {} ?: run { fail("no audio"); return }
        val rms = sqrt(pcm.fold(0.0) { a, v -> a + v * v } / pcm.size)
        note("audio: ${pcm.size} samples = ${pcm.size / 16000f}s rms=%.4f".format(rms))
        if (pcm.size / 16000f !in 10.5f..11.9f) fail("audio length ${pcm.size / 16000f}s")
        if (rms < 0.01) fail("audio is silent")

        // Speech -> text (+ translation).
        val t0 = System.currentTimeMillis()
        val r = runBlocking { SubtitleEngine.run(ctx, uri, model.absolutePath, "en", translate = true) { _, _ -> } }
        note("transcribe: ${System.currentTimeMillis() - t0}ms lang=${r.speechLang} translated=${r.translated} lines=${r.lines.size}")
        for (l in r.lines) note("  ${l.startMs}-${l.endMs} en='${l.en}' he='${l.he}'")
        val english = r.lines.joinToString(" ") { it.en }.lowercase()
        if (!english.contains("country")) fail("transcript: $english")
        if (r.lines.first().startMs > 2500 || r.lines.last().endMs < 7000) fail("timing off")
        if (SubtitleEngine.lineLanguage("שלום world עולם", "he") != "he" || SubtitleEngine.lineLanguage("Hello עולם", "he") != "en") {
            fail("line language")
        }

        // Editor with the subtitles.
        val info = VideoInfo.probe(ctx, uri)
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).analyze(info, settings) {} }
        val session = EditSession(info, settings, analysis, 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ctrl = EditorController(session, scope, ctx, ModelManager(ctx))
        inst.runOnMainSync { session.model.setCues(r.lines.map { Triple(it.startMs, it.endMs, it.he to it.en) }) }
        var export by mutableStateOf(ExportOptions(subs = SubLang.BOTH))
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(ctrl, onClose = {}, onExport = {}, export = export, onExportChange = { export = it })
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(2500)
        val longest = rule.runOnIdle { session.model.cues.maxBy { it.endMs - it.startMs } }
        rule.runOnIdle {
            ctrl.focusCue(longest.id)
            ctrl.seek(ctrl.frameAt(ctrl.cueRelUs((longest.startMs + longest.endMs) / 2)))
        }
        Thread.sleep(1500)
        shot(outDir, "1_subs_timeline.png")
        rule.runOnIdle { ctrl.cueEditor = true }
        Thread.sleep(1200)
        screen(outDir, "2_cue_editor.png")
        rule.runOnIdle {
            ctrl.cueEditor = false
            ctrl.editSelectedCue("שלום עולם", "Hello world")
            ctrl.deselect()
        }
        if (rule.runOnIdle { session.model.cue(longest.id)?.en } != "Hello world") fail("edit")
        rule.runOnIdle { ctrl.undo() }
        if (rule.runOnIdle { session.model.cue(longest.id)?.en } == "Hello world") fail("undo")
        rule.runOnIdle { ctrl.subsSheet = true }
        runCatching { rule.waitUntil(5000) { rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1 } }
        Thread.sleep(1200)
        screen(outDir, "3_subs_sheet.png")
        rule.runOnIdle { ctrl.subsSheet = false }

        // The editor's own path: generate again with the model (replaces the subtitles).
        inst.runOnMainSync { ctrl.generateSubtitles(model.absolutePath, "en") }
        val g0 = System.currentTimeMillis()
        Thread.sleep(300)
        while (inst.onMain { ctrl.subsProgress != null } && System.currentTimeMillis() - g0 < 180_000) Thread.sleep(200)
        val n = inst.onMain { session.model.cues.size }
        note("editor generate: ${System.currentTimeMillis() - g0}ms cues=$n notice=${inst.onMain { ctrl.notice }}")
        if (n == 0) fail("editor made no subtitles")
        Thread.sleep(800)
        shot(outDir, "4_after_generate.png")

        // Export: burned-in English + .srt files.
        val out = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, session.model, 0, ExportOptions(subs = SubLang.EN, saveSrt = true)) {}
        }
        note("export: srt=${out.srtFiles} renderMs=${out.renderMs}")
        if (out.srtFiles < 1) fail("no srt saved")
        File(outDir, "subs.en.srt").writeText(Srt.format(session.model.cues, true))
        File(outDir, "subs.he.srt").writeText(Srt.format(session.model.cues, false))
        val video = File(outDir, "burned.mp4")
        ctx.contentResolver.openInputStream(out.uri)!!.use { i -> video.outputStream().use { i.copyTo(it) } }

        val cue = session.model.cues.filter { it.en.isNotBlank() }.maxBy { it.endMs - it.startMs }
        val atUs = (cue.startMs + cue.endMs) / 2 * 1000
        val before = frame(src.absolutePath, atUs)
        val after = frame(video.absolutePath, atUs)
        File(outDir, "5_frame_source.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(outDir, "5_frame_burned.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wb = whiteBottom(before)
        val wa = whiteBottom(Bitmap.createScaledBitmap(after, before.width, before.height, true))
        note("white pixels at the bottom: source=$wb burned=$wa ('${cue.en}')")
        if (wa - wb < 300) fail("subtitle not burned in")

        scope.cancel()
        session.close()
    }

    private fun frame(path: String, us: Long): Bitmap {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            return mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            mmr.release()
        }
    }

    /** Near-white pixels in the bottom quarter. */
    private fun whiteBottom(b: Bitmap): Int {
        var n = 0
        for (y in b.height * 3 / 4 until b.height) {
            for (x in 0 until b.width) {
                val c = b.getPixel(x, y)
                if ((c shr 16 and 255) > 225 && (c shr 8 and 255) > 225 && (c and 255) > 225) n++
            }
        }
        return n
    }

    private fun <T> android.app.Instrumentation.onMain(block: () -> T): T {
        var r: Any? = null
        runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun screen(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(dir: File, name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
