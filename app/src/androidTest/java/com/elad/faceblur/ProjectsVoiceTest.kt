package com.elad.faceblur

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.BgKey
import com.elad.faceblur.pipeline.Watermark
import android.media.MediaMetadataRetriever
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.NewCue
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.edit.TimedWord
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.project.ProjectStore
import com.elad.faceblur.subs.AudioDecoder
import com.elad.faceblur.subs.ModelManager
import com.elad.faceblur.subs.SpeakerDetector
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.ui.EditorScreen
import com.elad.faceblur.ui.EditorTheme
import com.elad.faceblur.ui.FaceBlurTheme
import com.elad.faceblur.ui.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import java.io.File
import kotlin.math.PI
import kotlin.math.cos

/**
 * Saved projects (close, reopen with every edit and the analysis frames), the voice (bleeps and
 * voice change heard in the export) and speaker detection on a clip with two voices.
 */
@RunWith(AndroidJUnit4::class)
class ProjectsVoiceTest {

    @get:Rule
    val rule = createComposeRule()

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val outDir get() = File(ctx.getExternalFilesDir(null), "projects").apply { mkdirs() }
    private val log = StringBuilder()
    private var logName = "log.txt"

    private fun note(s: String) {
        log.appendLine(s)
        File(outDir, logName).writeText(log.toString())
    }

    private fun sample(name: String): File {
        val f = File(ctx.cacheDir, "pv_$name")
        inst.context.assets.open("samples/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!inst.onMain(cond)) {
            if (System.currentTimeMillis() - t0 > ms) fail("timed out: $what")
            Thread.sleep(100)
        }
    }

    @Test
    fun speakers() {
        logName = "log_speakers.txt"
        val src = sample("speech_two.mp4")
        val uri = Uri.fromFile(src)
        val mm = ModelManager(ctx)
        inst.context.assets.open("models/speaker.onnx").use { i -> mm.speakerFile.outputStream().use { i.copyTo(it) } }
        // JFK (0-11 s), then the same speech pitched up as a second voice (11-22 s).
        val times = listOf(300L to 4000L, 4000L to 8000L, 8000L to 10800L, 11300L to 15000L, 15000L to 19000L, 19000L to 21800L)
        val spans = times.map { SpeakerDetector.Span(it.first, it.second) }
        val t0 = System.currentTimeMillis()
        val auto = runBlocking { SpeakerDetector.detect(ctx, uri, mm.speakerFile.path, spans, null) {} }
        val two = runBlocking { SpeakerDetector.detect(ctx, uri, mm.speakerFile.path, spans, 2) {} }
        note("speakers auto=${auto.toList()} count2=${two.toList()} in ${System.currentTimeMillis() - t0}ms")
        for (l in listOf(auto, two)) {
            if (l.take(3).distinct().size != 1 || l.drop(3).distinct().size != 1 || l[0] == l[3]) fail("speakers ${l.toList()}")
        }

        // The editor's path: subtitles, then "find speakers", then the list with each one's color.
        val info = VideoInfo.probe(ctx, uri)
        val analysis = FaceBlurProcessor.framesOnly(ctx, info)
        val session = EditSession(info, Settings(BlurMode.BLUR, 0.6f, 1.6f, false), analysis, 0, subtitlesOnly = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ctrl = EditorController(session, scope, ctx, mm)
        val lines = listOf("And so my fellow Americans", "ask not what your country can do for you", "ask what you can do for your country")
        inst.runOnMainSync {
            ctrl.previewAvailable = false
            session.model.setSentences(times.mapIndexed { i, (a, b) -> NewCue(a, b, "", lines[i % 3], "en", null) })
        }
        val style = SubPresets.all.first { it.name == "טיקטוק" }.style
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(ctrl, onClose = {}, onExport = {}, export = ExportOptions(subs = SubLang.ORIGINAL), subStyle = style)
                }
            }
        }
        rule.waitForIdle()
        inst.runOnMainSync { ctrl.detectSpeakers(null) }
        Thread.sleep(300)
        waitFor(180_000, "speaker detection") { ctrl.speakersProgress == null }
        val sp = inst.onMain { session.model.cues.sortedBy { it.startMs }.map { it.speaker } }
        note("editor speakers $sp notice=${inst.onMain { ctrl.notice }}")
        if (sp.take(3).distinct().size != 1 || sp.drop(3).distinct().size != 1 || sp[0] == sp[3]) fail("editor speakers $sp")
        // Words per subtitle keep each one's speaker.
        rule.runOnIdle { ctrl.changeSplit(SplitRule(words = 3)) }
        val after = inst.onMain { session.model.cues.sortedBy { it.startMs }.map { (if (it.startMs < 11000) "A" else "B") to it.speaker } }
        if (after.groupBy { it.first }.any { (_, v) -> v.map { it.second }.distinct().size != 1 }) fail("speakers mixed after re-split: $after")
        rule.runOnIdle { ctrl.listEditor = true }
        rule.waitForIdle()
        Thread.sleep(1200)
        shot("10_speakers_list.png")
        rule.runOnIdle {
            ctrl.listEditor = false
            ctrl.seek(ctrl.frameAt(ctrl.cueRelUs(12500)))
        }
        rule.waitForIdle()
        Thread.sleep(1500)
        shot("10b_second_speaker.png")
        scope.cancel()
        session.close()
    }

    @Test
    fun voiceExport() {
        logName = "log_voice.txt"
        val src = sample("speech_en.mp4")
        val uri = Uri.fromFile(src)
        val info = VideoInfo.probe(ctx, uri)
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = FaceBlurProcessor.framesOnly(ctx, info)
        val session = EditSession(info, settings, analysis, 0, subtitlesOnly = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ctrl = EditorController(session, scope, ctx, ModelManager(ctx))
        val words = (0 until 4).map { TimedWord(1000L + it * 500, 1400L + it * 500) }
        inst.runOnMainSync {
            ctrl.previewAvailable = false
            session.model.setSentences(listOf(NewCue(1000, 3000, "", "one two three four", "en", words)))
            val id = session.model.cues.first().id
            ctrl.toggleBleep(id, 1)
            ctrl.changeAudio(AudioEdit(VoiceFx.DEEP, tone = true, maskWords = true))
            // A "ding" at 5 s (1568 Hz).
            ctrl.seek(ctrl.frameAt(ctrl.cueRelUs(5000)))
            ctrl.addSfx("builtin:ding")
        }
        val sfx = inst.onMain { session.model.sfx.map { it.sound to it.startMs } }
        note("sfx $sfx")
        if (sfx.size != 1 || sfx[0].second !in 4900L..5100L) fail("sfx not added at the playhead: $sfx")
        val bleeps = inst.onMain { session.model.bleeps.toMap() }
        note("bleeps $bleeps")
        if (bleeps != mapOf(1500L to 1900L)) fail("bleep stretch $bleeps")
        val style = SubPresets.all.first { it.name == "קלאסי" }.style
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(ctrl, onClose = {}, onExport = {}, export = ExportOptions(subs = SubLang.ORIGINAL), subStyle = style)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            ctrl.seek(ctrl.frameAt(ctrl.cueRelUs(1700)))
            ctrl.voicePanel = true
            ctrl.soundTab = 0
        }
        rule.waitForIdle()
        Thread.sleep(1200)
        shot("11a_sfx_panel.png")
        rule.runOnIdle { ctrl.soundTab = 1 }
        rule.waitForIdle()
        Thread.sleep(800)
        shot("11_voice_panel.png")
        rule.runOnIdle {
            ctrl.voicePanel = false
            ctrl.selectSfx(session.model.sfx.first().id)
            ctrl.seek(ctrl.frameAt(ctrl.cueRelUs(5200)))
        }
        rule.waitForIdle()
        Thread.sleep(1000)
        shot("11c_sfx_timeline.png")
        rule.runOnIdle { ctrl.deselect() }
        rule.runOnIdle {
            ctrl.voicePanel = false
            ctrl.bleepMode = true
            ctrl.listEditor = true
        }
        rule.waitForIdle()
        Thread.sleep(1000)
        shot("11b_bleep_words.png")
        rule.runOnIdle {
            ctrl.listEditor = false
            ctrl.bleepMode = false
        }

        // Export with the deep voice and the beep: the beep is at 1 kHz where the word was.
        val out = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, session.model, 0, ExportOptions(subs = SubLang.ORIGINAL, saveSrt = false), style, ctrl.audio) {}
        }
        val video = File(outDir, "voice.mp4")
        ctx.contentResolver.openInputStream(out.uri)!!.use { i -> video.outputStream().use { i.copyTo(it) } }
        val pcm = AudioDecoder.decode(ctx, Uri.fromFile(video), { true }) {} ?: run { fail("export lost its sound"); return }
        val inBeep = toneShare(pcm, 1560, 1840, 1000.0)
        val outside = toneShare(pcm, 2060, 2340, 1000.0)
        val ding = toneShare(pcm, 5040, 5300, 1568.0)
        val noDing = toneShare(pcm, 4500, 4900, 1568.0)
        note("voice export: ${pcm.size / 16000f}s, 1 kHz share inside the bleep %.2f, outside %.2f; ding share %.2f (before it %.2f)".format(inBeep, outside, ding, noDing))
        if (pcm.size / 16000f < 10f) fail("audio too short")
        if (inBeep < 0.5 || outside > 0.3) fail("no beep where the word was ($inBeep / $outside)")
        if (ding < 0.3 || noDing > 0.2) fail("sound effect not mixed in ($ding / $noDing)")

        // No sound at all (no sound effects either).
        inst.runOnMainSync {
            session.model.deleteSfx(session.model.sfx.first().id)
            ctrl.changeAudio(AudioEdit(VoiceFx.MUTE))
        }
        val muted = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, session.model, 0, ExportOptions(subs = SubLang.NONE, saveSrt = false), style, ctrl.audio) {}
        }
        val mv = File(outDir, "muted.mp4")
        ctx.contentResolver.openInputStream(muted.uri)!!.use { i -> mv.outputStream().use { i.copyTo(it) } }
        if (AudioDecoder.decode(ctx, Uri.fromFile(mv), { true }) {} != null) fail("muted export still has sound")
        scope.cancel()
        session.close()
    }

    /** A green screen clip keyed out: the screen becomes blue, the box stays. */
    @Test
    fun backgroundKey() {
        logName = "log_background.txt"
        val src = sample("green_screen.mp4")
        val uri = Uri.fromFile(src)
        val info = VideoInfo.probe(ctx, uri)
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = FaceBlurProcessor.framesOnly(ctx, info)
        val session = EditSession(info, settings, analysis, 0, subtitlesOnly = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ctrl = EditorController(session, scope, ctx, ModelManager(ctx))
        val blue = 0xFF2050FF.toInt()
        inst.runOnMainSync {
            ctrl.changeBackground(Background(fill = BgFill.COLOR, key = BgKey.GREEN, color = blue))
            ctrl.bgPanel = true
        }
        val style = SubPresets.all.first { it.name == "קלאסי" }.style
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EditorScreen(ctrl, onClose = {}, onExport = {}, export = ExportOptions(subs = SubLang.NONE), subStyle = style)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { ctrl.seek(ctrl.frameAt(2_000_000)) }
        rule.waitForIdle()
        Thread.sleep(2500)
        shot("14_green_screen_panel.png")
        val bg = inst.onMain { ctrl.background }
        val out = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, session.model, 0, ExportOptions(subs = SubLang.NONE, saveSrt = false), style, AudioEdit(), bg, null) {}
        }
        val f = File(outDir, "green_keyed.mp4")
        ctx.contentResolver.openInputStream(out.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(f.absolutePath)
        val frame = mmr.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)
        mmr.release()
        if (frame == null) {
            fail("no frame")
            return
        }
        File(outDir, "14b_green_keyed_frame.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val sx = frame.width / 640f
        val sy = frame.height / 360f
        val corner = frame.getPixel((20 * sx).toInt(), (20 * sy).toInt())
        val box = frame.getPixel((270 * sx).toInt(), (175 * sy).toInt())
        note("green screen: corner ${Integer.toHexString(corner)}, box ${Integer.toHexString(box)}")
        if (colorDistance(corner, blue) > 60) fail("screen not replaced: ${Integer.toHexString(corner)}")
        if (colorDistance(box, 0xFFE0E0E0.toInt()) > 60) fail("subject changed: ${Integer.toHexString(box)}")
        scope.cancel()
        session.close()
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 255) - (b shr 16 and 255)
        val dg = (a shr 8 and 255) - (b shr 8 and 255)
        val db = (a and 255) - (b and 255)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }

    /** Share of the energy in [fromMs, toMs) at [hz] (Goertzel), 16 kHz samples. */
    private fun toneShare(pcm: FloatArray, fromMs: Int, toMs: Int, hz: Double): Double {
        val a = fromMs * 16
        val b = minOf(pcm.size, toMs * 16)
        val n = b - a
        if (n <= 0) return 0.0
        val k = 2 * cos(2 * PI * hz / 16000)
        var s1 = 0.0
        var s2 = 0.0
        var energy = 0.0
        for (i in a until b) {
            val x = pcm[i].toDouble()
            energy += x * x
            val s = x + k * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - k * s1 * s2
        // A pure tone of amplitude A gives power (A n / 2)^2 and energy A^2 n / 2.
        return if (energy <= 0) 0.0 else (2 * power / n) / energy
    }

    /** Blur a clip, edit, close: the project is on the home screen, and reopens as it was. */
    @Test
    fun projectRoundTrip() {
        logName = "log_projects.txt"
        val names = inst.context.assets.list("samples")?.toList() ?: emptyList()
        val name = listOf("walk.mp4", "classroom.mp4").firstOrNull { it in names } ?: run { fail("no sample"); return }
        val src = sample(name)
        val vm = inst.onMain { MainViewModel(ctx.applicationContext as Application) }
        rule.setContent {
            val prefs = vm.prefs
            FaceBlurTheme(prefs.themeMode, prefs.palette) {
                val d = if (prefs.lang == Lang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides d) { MainScreen(vm) }
            }
        }
        inst.runOnMainSync { vm.load(Uri.fromFile(src)) }
        waitFor(20_000, "video loaded") { vm.state is UiState.Ready }
        inst.runOnMainSync {
            vm.review = true
            vm.start()
        }
        waitFor(240_000, "analysis") { vm.state is UiState.Editing }
        val ctrl = inst.onMain { (vm.state as UiState.Editing).editor }
        val words = (0 until 4).map { TimedWord(500L + it * 450, 850L + it * 450) }
        inst.runOnMainSync {
            val m = ctrl.model
            m.setSentences(listOf(NewCue(500, 2400, "", "hello there my friend", "en", words)))
            val id = m.cues.first().id
            ctrl.setSpeaker(id, 3)
            ctrl.toggleBleep(id, 2)
            ctrl.changeAudio(AudioEdit(VoiceFx.ROBOT, tone = false))
            ctrl.changeSplit(SplitRule(words = 2))
            m.tracks.firstOrNull()?.let { m.setScale(it.id, 1.7f) }
            ctrl.seek(12)
            ctrl.addSfx("builtin:whoosh")
        }
        val before = inst.onMain { ctrl.model.exportState() }
        val frames0 = ctrl.session.analysis.frames
        val sum0 = frames0?.read(5)?.let { m -> Core.sumElems(m).`val`[0].also { m.release() } }
        note("$name: tracks=${before.tracks.size} cues=${before.cues.map { it.en + "/s" + it.speaker }} frames=${frames0?.size} sum5=$sum0")
        inst.runOnMainSync { vm.closeEditor() }
        waitFor(20_000, "project listed") { vm.projects.any { it.cues == 2 } }
        val p = inst.onMain { vm.projects.first { it.cues == 2 } }
        note("project ${p.id}: '${p.name}' blurs=${p.blurs} cues=${p.cues} bytes=${p.bytes}")
        if (!File(ProjectStore.dir(ctx, p.id), "frames.bin").exists()) fail("analysis frames not kept")
        rule.waitForIdle()
        Thread.sleep(1200)
        shot("12_home_projects.png")

        inst.runOnMainSync { vm.openProject(p.id) }
        waitFor(30_000, "reopened") { vm.state is UiState.Editing }
        val c2 = inst.onMain { (vm.state as UiState.Editing).editor }
        val after = inst.onMain { c2.model.exportState() }
        val got = after.cues.map { Triple(it.en, it.speaker, it.words?.size) }
        val want = before.cues.map { Triple(it.en, it.speaker, it.words?.size) }
        note("reopened cues=$got bleeps=${after.bleeps.map { it.toList() }} audio=${inst.onMain { c2.audio }} split=${inst.onMain { c2.split }} frame=${inst.onMain { c2.frame }}")
        if (got != want) fail("cues $got != $want")
        if (after.bleeps.map { it.toList() } != before.bleeps.map { it.toList() }) fail("bleeps")
        if (after.sfx.map { it.sound to it.startMs } != before.sfx.map { it.sound to it.startMs } || after.sfx.isEmpty()) fail("sound effects")
        if (after.tracks.size != before.tracks.size || after.tracks.zip(before.tracks).any { (a, b) -> a.scale != b.scale || a.path.size != b.path.size }) fail("blurs")
        if (inst.onMain { c2.audio } != AudioEdit(VoiceFx.ROBOT, tone = false)) fail("voice")
        if (inst.onMain { c2.split } != SplitRule(words = 2)) fail("words per subtitle")
        if (inst.onMain { c2.frame } != 12) fail("playhead")
        val frames1 = c2.session.analysis.frames
        val sum1 = frames1?.read(5)?.let { m -> Core.sumElems(m).`val`[0].also { m.release() } }
        note("frames back: ${frames1?.size} sum5=$sum1")
        if (frames0 != null && (frames1?.size != frames0.size || sum1 != sum0)) fail("analysis frames")
        rule.waitForIdle()
        Thread.sleep(2500)
        shot("12b_reopened.png")

        // A vertical export that follows the faces, with a text watermark.
        val vexport = ExportOptions(aspect = ExportAspect.VERTICAL, subs = SubLang.ORIGINAL, saveSrt = false, watermark = Watermark(text = "@editit"))
        inst.runOnMainSync { vm.prefs.updateExport(vexport) }
        rule.waitForIdle()
        Thread.sleep(1500)
        shot("12c_vertical_preview.png")
        val s2 = c2.session
        val style2 = inst.onMain { vm.prefs.subStyle }
        val audio2 = inst.onMain { c2.audio }
        val vout = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(s2.info, s2.settings, s2.analysis, s2.model, 0, vexport, style2, audio2) {}
        }
        val vfile = File(outDir, "vertical.mp4")
        ctx.contentResolver.openInputStream(vout.uri)!!.use { i -> vfile.outputStream().use { i.copyTo(it) } }
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(vfile.absolutePath)
        val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (dw, dh) = if (rot % 180 == 0) vw to vh else vh to vw
        mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { b ->
            File(outDir, "12d_vertical_frame.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        mmr.release()
        note("vertical export ${dw}x$dh")
        if (kotlin.math.abs(dw.toFloat() / dh - 9f / 16f) > 0.02f) fail("not 9:16: ${dw}x$dh")
        inst.runOnMainSync { vm.prefs.updateExport(ExportOptions()) }

        // Background removal behind the people: find them, then a flat color behind.
        inst.runOnMainSync {
            c2.bgPanel = true
            c2.findPeople()
        }
        Thread.sleep(300)
        waitFor(240_000, "people found") { c2.segmentProgress == null }
        if (!inst.onMain { c2.masksReady }) fail("people not found: ${inst.onMain { c2.notice }}")
        if (!File(ProjectStore.dir(ctx, p.id), "masks.bin.idx").exists()) fail("masks not kept with the project")
        val green = 0xFF00B140.toInt()
        inst.runOnMainSync {
            c2.changeBackground(Background(fill = BgFill.COLOR, key = BgKey.PERSON, color = green))
            c2.seek(c2.frameAt(3_000_000))
        }
        rule.waitForIdle()
        Thread.sleep(2500)
        shot("13_background_panel.png")
        val bg2 = inst.onMain { c2.background }
        val bout = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(s2.info, s2.settings, s2.analysis, s2.model, 0, ExportOptions(subs = SubLang.NONE, saveSrt = false), style2, audio2, bg2, s2.masks) {}
        }
        val bfile = File(outDir, "background.mp4")
        ctx.contentResolver.openInputStream(bout.uri)!!.use { i -> bfile.outputStream().use { i.copyTo(it) } }
        val bm = MediaMetadataRetriever()
        bm.setDataSource(bfile.absolutePath)
        val frame = bm.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST)
        bm.release()
        if (frame == null) fail("no frame") else {
            File(outDir, "13b_background_frame.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val corner = frame.getPixel((frame.width * 0.05f).toInt(), (frame.height * 0.08f).toInt())
            note("background: corner ${Integer.toHexString(corner)} (want ~${Integer.toHexString(green)})")
            if (colorDistance(corner, green) > 60) fail("background not replaced: ${Integer.toHexString(corner)}")
            // Most of the frame is green now, but not all of it (the people stay).
            var n = 0
            var g = 0
            for (y in 0 until frame.height step 8) for (x in 0 until frame.width step 8) {
                n++
                if (colorDistance(frame.getPixel(x, y), green) < 60) g++
            }
            note("background share ${g * 100 / n}%")
            if (g * 100 / n !in 40..95) fail("background share ${g * 100 / n}%")
        }
        inst.runOnMainSync { c2.bgPanel = false }

        inst.runOnMainSync { vm.closeEditor() }
        inst.runOnMainSync { vm.renameProject(p.id, "בדיקה") }
        waitFor(10_000, "renamed") { vm.projects.any { it.id == p.id && it.name == "בדיקה" } }
        inst.runOnMainSync { vm.deleteProject(p.id) }
        waitFor(10_000, "deleted") { vm.projects.none { it.id == p.id } }
        Thread.sleep(500)
        if (ProjectStore.dir(ctx, p.id).exists()) fail("project folder left")
    }

    private fun <T> android.app.Instrumentation.onMain(block: () -> T): T {
        var r: Any? = null
        runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun shot(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
