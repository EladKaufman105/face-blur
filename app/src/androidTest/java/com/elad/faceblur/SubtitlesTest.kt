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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.subs.AudioDecoder
import com.elad.faceblur.subs.ModelManager
import com.elad.faceblur.subs.Srt
import com.elad.faceblur.subs.FontStore
import com.elad.faceblur.subs.SubEffect
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.subs.SubtitleEngine
import com.elad.faceblur.subs.SubtitleRenderer
import com.elad.faceblur.subs.SubtitleStyle
import com.elad.faceblur.ui.EditorScreen
import com.elad.faceblur.ui.EditorTheme
import com.elad.faceblur.ui.Tags
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
    /** The model packs, from the test assets (ci/fetch-models.sh), installed as the app installs a download. */
    @org.junit.Before
    fun seedModels() = com.elad.faceblur.models.ModelSeed.install()


    @get:Rule
    val rule = createComposeRule()

    @Test
    fun transcribeEditAndBurn() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        val outDir = File(ctx.getExternalFilesDir(null), "subs").apply { mkdirs() } // not cleared: other tests here write too
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
        var style by mutableStateOf(SubtitleStyle())
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(
                        ctrl, onClose = {}, onExport = {}, export = export, onExportChange = { export = it },
                        subStyle = style, onSubStyleChange = { style = it },
                    )
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
        rule.waitForIdle()
        runCatching { rule.waitUntil(5000) { rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1 } }
        Thread.sleep(1200)
        screen(outDir, "2_cue_editor.png")
        rule.runOnIdle {
            ctrl.cueEditor = false
        }
        rule.waitForIdle()
        rule.runOnIdle {
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

        // Style panel, then a TikTok-style look on the video.
        rule.runOnIdle {
            ctrl.focusCue(longest.id)
            ctrl.stylePanel = true
        }
        rule.waitForIdle()
        Thread.sleep(1000)
        shot(outDir, "3b_style_panel.png")
        rule.runOnIdle { style = SubPresets.all.first { it.name == "תיבה צבעונית" }.style }
        rule.waitForIdle()
        Thread.sleep(800)
        shot(outDir, "3c_style_wordbox.png")
        rule.runOnIdle {
            ctrl.stylePanel = false
            ctrl.deselect()
        }

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

        // Two words per subtitle, then the list of all subtitles.
        rule.runOnIdle { ctrl.changeSplit(SplitRule(words = 2)) }
        val chunks = rule.runOnIdle { session.model.cues.map { it.copy() } }
        note("2 words per subtitle: ${chunks.size} subtitles: " + chunks.joinToString(" | ") { "${it.startMs}-${it.endMs} '${it.en}' / '${it.he}'" })
        if (chunks.size < 8 || chunks.any { it.en.split(" ").size > 2 }) fail("regroup to 2 words")
        if (chunks.zipWithNext().any { (a, b) -> a.endMs > b.startMs }) fail("overlapping subtitles")
        rule.runOnIdle { ctrl.splitPanel = true }
        rule.waitForIdle()
        Thread.sleep(600)
        shot(outDir, "4a_split_panel.png")
        rule.runOnIdle { ctrl.splitPanel = false; ctrl.listEditor = true }
        rule.waitForIdle()
        Thread.sleep(1000)
        shot(outDir, "4b_list_editor.png")
        // "Save SRT now" is in the list's header, with the files the export would save.
        rule.onNodeWithTag(Tags.SRT_SAVE).assertExists()
        if (rule.runOnIdle { ctrl.srtFiles() }.isEmpty()) fail("no srt to save now")
        rule.runOnIdle { ctrl.changeSplit(SplitRule(chars = 15)) }
        val byChars = rule.runOnIdle { session.model.cues.map { it.en } }
        note("15 characters: $byChars")
        if (byChars.any { it.length > 15 && it.contains(" ") }) fail("regroup to 15 characters")
        rule.runOnIdle {
            ctrl.listEditor = false
            ctrl.changeSplit(SplitRule())
        }
        if (rule.runOnIdle { session.model.cues.size } != n) fail("back to sentences")
        // Split the first sentence at the playhead (snaps to a word's start time), then merge back.
        val first = rule.runOnIdle { session.model.cues.minBy { it.startMs } }
        val firstWords = first.words?.size ?: 0
        rule.runOnIdle {
            ctrl.selectCue(first.id)
            ctrl.seek(ctrl.frameAt(ctrl.cueRelUs((first.startMs + first.endMs) / 2)))
            ctrl.splitSelectedCue()
        }
        val afterSplit = rule.runOnIdle { session.model.cues.sortedBy { it.startMs }.map { it.copy() } }
        val wordStarts = first.words?.map { it.startMs }.orEmpty()
        note("split at playhead: ${afterSplit.take(2).joinToString(" | ") { "${it.startMs}-${it.endMs} '${it.srcText}'" }} (word starts $wordStarts)")
        if (firstWords >= 2) {
            if (afterSplit.size != n + 1) fail("split")
            if (afterSplit[1].startMs !in wordStarts) fail("split not at a word start")
            rule.runOnIdle { ctrl.selectCue(first.id); ctrl.mergeSelectedCue() }
            if (rule.runOnIdle { session.model.cues.size } != n) fail("merge back")
        }
        // Translation after the fact.
        inst.runOnMainSync { ctrl.translateSubtitles() }
        val tr0 = System.currentTimeMillis()
        Thread.sleep(300)
        while (inst.onMain { ctrl.subsProgress != null } && System.currentTimeMillis() - tr0 < 180_000) Thread.sleep(200)
        note("translated afterwards: ${inst.onMain { ctrl.hasTranslation }} " + inst.onMain { session.model.cues.joinToString(" | ") { it.he } })

        // Export: burned-in English + .srt files.
        val out = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(
                info, settings, analysis, session.model, 0, ExportOptions(subs = SubLang.ORIGINAL, saveSrt = true),
                SubPresets.all.first { it.name == "מילה מודגשת" }.style,
            ) {}
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
        val added = newWhite(before, Bitmap.createScaledBitmap(after, before.width, before.height, true))
        note("near-white pixels added: $added ('${cue.en}')")
        if (added < 300) fail("subtitle not burned in")

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

    private fun white(c: Int) = (c shr 16 and 255) > 225 && (c shr 8 and 255) > 225 && (c and 255) > 225

    /** Pixels near-white in [b] but not in [a] (the text drawn on top). */
    private fun newWhite(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (white(b.getPixel(x, y)) && !white(a.getPixel(x, y))) n++
            }
        }
        return n
    }

    /** Every preset, and each effect over time, drawn on a gray frame: for looking at. */
    @Test
    fun styleGallery() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "subs").apply { mkdirs() }
        val r = SubtitleRenderer.get(ctx)
        // Importing a font: a bundled one, as if picked from the phone's files.
        val picked = File(ctx.cacheDir, "My Font.ttf")
        ctx.assets.open("fonts/suez_one.ttf").use { i -> picked.outputStream().use { i.copyTo(it) } }
        val name = FontStore.import(ctx, android.net.Uri.fromFile(picked))
        val tf = r.customTypeface(name) ?: run { fail("imported font doesn't load"); return }
        if (!FontStore.hasHebrew(tf)) fail("imported font has no Hebrew")
        val notFont = File(ctx.cacheDir, "notes.ttf").apply { writeText("hello") }
        if (runCatching { FontStore.import(ctx, android.net.Uri.fromFile(notFont)) }.isSuccess) fail("imported a non-font")
        val customStyle = SubtitleStyle(customFont = name, size = 1.4f, position = 0.5f)
        val cbmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        r.draw(android.graphics.Canvas(cbmp), listOf("גופן מיובא Imported"), 0L, 2_000_000L, 1_000_000L, customStyle, 640, 360)
        File(outDir, "9_imported_font.png").outputStream().use { cbmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FontStore.delete(ctx, name)
        r.forget(name)
        val blocks = listOf("החיילים מחפשים לכודים מתחת להריסות", "The soldiers search under the ruins")
        val cw = 360
        val ch = 640
        val presets = SubPresets.all
        val cols = 5
        val rows = (presets.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cw * cols, ch * rows, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        val bg = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, ch.toFloat(), 0xFF6B7A8F.toInt(), 0xFF2B3240.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        val label = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 22f; isAntiAlias = true }
        presets.forEachIndexed { i, p ->
            canvas.save()
            canvas.translate((i % cols) * cw.toFloat(), (i / cols) * ch.toFloat())
            canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), bg)
            r.draw(canvas, blocks, 0L, 3_000_000L, 1_200_000L, p.style, cw, ch)
            canvas.drawText(p.style.effect.name + " " + p.style.font.name, 10f, 30f, label)
            canvas.restore()
        }
        File(outDir, "6_presets.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Each effect at a few moments of a 3 s subtitle (landscape frame).
        val effects = SubEffect.entries
        val times = listOf(50_000L, 150_000L, 700_000L, 1_500_000L, 2_900_000L)
        val ew = 480
        val eh = 270
        val fx = Bitmap.createBitmap(ew * times.size, eh * effects.size, Bitmap.Config.ARGB_8888)
        val c2 = android.graphics.Canvas(fx)
        effects.forEachIndexed { row, e ->
            times.forEachIndexed { col, t ->
                c2.save()
                c2.translate(col * ew.toFloat(), row * eh.toFloat())
                c2.drawRect(0f, 0f, ew.toFloat(), eh.toFloat(), bg)
                r.draw(c2, blocks.take(1), 0L, 3_000_000L, t, SubtitleStyle(font = SubFont.RUBIK, effect = e, position = 0.6f, size = 1.4f), ew, eh)
                c2.drawText("${e.name} ${t / 1000}ms", 8f, 24f, label)
                c2.restore()
            }
        }
        File(outDir, "7_effects.png").outputStream().use { fx.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Letter by letter and typing, frame by frame: whole letters appear, nothing fades in.
        val steps = (0 until 10).map { 60_000L + it * 110_000L }
        val lt = Bitmap.createBitmap(ew * 5, eh * 4, Bitmap.Config.ARGB_8888)
        val c3 = android.graphics.Canvas(lt)
        listOf(SubEffect.LETTERS, SubEffect.TYPING).forEachIndexed { k, e ->
            steps.forEachIndexed { i, t ->
                c3.save()
                c3.translate((i % 5) * ew.toFloat(), (k * 2 + i / 5) * eh.toFloat())
                c3.drawRect(0f, 0f, ew.toFloat(), eh.toFloat(), bg)
                r.draw(c3, listOf("שלום לכולם, מה שלומכם?"), 0L, 3_000_000L, t, SubtitleStyle(font = SubFont.RUBIK, effect = e, position = 0.6f, size = 1.3f), ew, eh)
                c3.drawText("${e.name} ${t / 1000}ms", 8f, 24f, label)
                c3.restore()
            }
        }
        File(outDir, "7b_letters_typing.png").outputStream().use { lt.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Every font draws Hebrew and English.
        for (f in SubFont.entries) if (r.typeface(f) == android.graphics.Typeface.DEFAULT_BOLD && f.asset != null) fail("font ${f.name} didn't load")
    }

    /** Subtitles without face detection: frame times from the file, then an export. */
    @Test
    fun subtitlesOnly() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "subs").apply { mkdirs() }
        val src = File(ctx.cacheDir, "speech_only.mp4")
        inst.context.assets.open("samples/speech_en.mp4").use { i -> src.outputStream().use { i.copyTo(it) } }
        val info = VideoInfo.probe(ctx, Uri.fromFile(src))
        val analysis = FaceBlurProcessor.framesOnly(ctx, info)
        val log = StringBuilder("frames only: ${analysis.frameCount} frames, first ${analysis.times.first()} last ${analysis.times.last()}\n")
        if (analysis.frameCount !in 300..360) fail("frame count ${analysis.frameCount}")
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val session = EditSession(info, settings, analysis, 0, subtitlesOnly = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ctrl = EditorController(session, scope, ctx, ModelManager(ctx))
        inst.runOnMainSync {
            ctrl.previewAvailable = false
            session.model.setCues(listOf(Triple(500L, 4000L, "שלום לכולם" to "Hello everyone"), Triple(4200L, 9000L, "זה מבחן" to "This is a test")))
        }
        val style = SubPresets.all.first { it.name == "טיקטוק" }.style
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(ctrl, onClose = {}, onExport = {}, export = ExportOptions(subs = SubLang.BOTH), subStyle = style)
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(2500)
        rule.runOnIdle { ctrl.seek(ctrl.frameAt(2_000_000)) }
        Thread.sleep(1200)
        shot(outDir, "8_subs_only_editor.png")
        val out = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, session.model, 0, ExportOptions(subs = SubLang.BOTH, saveSrt = false), style) {}
        }
        val video = File(outDir, "subs_only.mp4")
        ctx.contentResolver.openInputStream(out.uri)!!.use { i -> video.outputStream().use { it2 -> i.copyTo(it2) } }
        val f = frame(video.absolutePath, 2_000_000)
        File(outDir, "8_subs_only_frame.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val added = newWhite(frame(src.absolutePath, 2_000_000), Bitmap.createScaledBitmap(f, info.displayWidth, info.displayHeight, true))
        log.append("subs only export: ${out.renderMs}ms, near-white added $added\n")
        File(outDir, "log_subs_only.txt").writeText(log.toString())
        if (added < 300) fail("subtitle not burned in (subtitles only)")
        scope.cancel()
        session.close()
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
