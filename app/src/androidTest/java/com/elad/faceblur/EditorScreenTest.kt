package com.elad.faceblur

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.prefs.Palette
import com.elad.faceblur.prefs.ThemeMode
import com.elad.faceblur.ui.FaceBlurTheme
import com.elad.faceblur.ui.MainScreen
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.elad.faceblur.i18n.I18n
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.pipeline.ExportOptions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.edit.EditSession
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
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
import kotlin.math.abs

/**
 * Drives the blur editor on a sample clip: screenshots of its states, a deleted face re-added
 * by hand and followed by the manual tracker, a few edits, and an export of the result.
 */
@RunWith(AndroidJUnit4::class)
class EditorScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun editAndExport() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        // Not cleared: the other test in this class writes here too (test order isn't fixed).
        val outDir = File(ctx.getExternalFilesDir(null), "editor").apply { mkdirs() }
        val log = StringBuilder()
        fun note(s: String) {
            log.appendLine(s)
            File(outDir, "log.txt").writeText(log.toString())
        }
        val names = inst.context.assets.list("samples")?.toList() ?: emptyList()
        val name = listOf("classroom.mp4", "walk.mp4").firstOrNull { it in names } ?: run {
            fail("no sample clip")
            return
        }
        val src = File(ctx.cacheDir, "editor_$name")
        inst.context.assets.open("samples/$name").use { i -> src.outputStream().use { i.copyTo(it) } }
        val info = VideoInfo.probe(ctx, Uri.fromFile(src))
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).analyze(info, settings) {} }
        note("$name: frames=${analysis.frameCount} tracks=${analysis.tracks.size} store=${analysis.frames?.size}")
        val session = EditSession(info, settings, analysis, 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ctrl = EditorController(session, scope)

        var dir by mutableStateOf(LayoutDirection.Rtl)
        var export by mutableStateOf(ExportOptions())
        rule.setContent {
            EditorTheme {
                CompositionLocalProvider(LocalLayoutDirection provides dir) {
                    EditorScreen(ctrl, onClose = {}, onExport = {}, export = export, onExportChange = { export = it })
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(3500)
        shot(outDir, "1_overview.png")

        // Playback: the playhead must advance on its own.
        // (While playing the UI is never idle: talk to it on the main thread directly.)
        val f0 = rule.runOnIdle { ctrl.frame }
        inst.runOnMainSync { ctrl.playToggler() }
        Thread.sleep(2000)
        var f1 = 0
        var wasPlaying = false
        inst.runOnMainSync {
            f1 = ctrl.frame
            wasPlaying = ctrl.playing
            ctrl.pauser()
        }
        Thread.sleep(500)
        shot(outDir, "1b_after_play.png")
        note("playback: frame $f0 -> $f1, playing=$wasPlaying, preview=${rule.runOnIdle { ctrl.previewAvailable }}")
        if (f1 <= f0) fail("playback did not advance")

        val first = rule.runOnIdle { ctrl.model.tracks.maxByOrNull { it.end - it.start } }
        if (first == null) {
            note("no automatic tracks")
            fail("no tracks in $name")
            return
        }
        val origStart = first.start
        val origEnd = first.end
        val mid = (origStart + origEnd) / 2
        val orig = (origStart..origEnd).associateWith { rule.runOnIdle { ctrl.model.boxAt(first, it)!! } }
        rule.runOnIdle {
            ctrl.focus(first.id)
            ctrl.seek(mid)
        }
        Thread.sleep(2000)
        shot(outDir, "2_selected.png")

        // Trim the clip's end by dragging its edge, then undo.
        rule.runOnIdle {
            ctrl.beginTrim()
            ctrl.trimTo(first.id, start = false, f = mid + 5)
        }
        Thread.sleep(800)
        shot(outDir, "2b_trimming.png")
        rule.runOnIdle { ctrl.endTrim() }
        val trimmedEnd = rule.runOnIdle { ctrl.model.track(first.id)!!.end }
        rule.runOnIdle { ctrl.undo() }
        note("trim: end $origEnd -> $trimmedEnd, after undo ${rule.runOnIdle { ctrl.model.track(first.id)!!.end }}")
        if (trimmedEnd != mid + 5) fail("trim failed")

        // Delete the face's blur, then put it back by hand and let the tracker follow it.
        val target = orig[mid]!!
        rule.runOnIdle {
            ctrl.deleteSelected()
            ctrl.addMode = true
        }
        Thread.sleep(800)
        shot(outDir, "3_add_mode.png")
        rule.runOnIdle { ctrl.tap(target.cx, target.cy - 0.06f * target.h) }
        val t0 = System.currentTimeMillis()
        while (rule.runOnIdle { ctrl.trackingProgress } != null) {
            if (System.currentTimeMillis() - t0 > 180_000) fail("tracking timed out")
            Thread.sleep(200)
        }
        note("tracking took ${System.currentTimeMillis() - t0}ms; notice=${rule.runOnIdle { ctrl.notice }}")
        val manual = rule.runOnIdle { ctrl.model.tracks.firstOrNull { it.manual } }
        if (manual == null) {
            fail("manual blur missing")
            return
        }
        var followed = 0
        for ((i, b) in orig) {
            val m = rule.runOnIdle { ctrl.model.boxAt(manual, i) } ?: continue
            if (abs(m.cx - b.cx) < b.w * 0.5f && abs(m.cy - b.cy) < b.h * 0.5f) followed++
        }
        val frac = followed.toFloat() / orig.size
        note("auto track $origStart..$origEnd; manual ${manual.start}..${manual.end}; follows ${"%.2f".format(frac)} of the face's frames")
        Thread.sleep(1500)
        shot(outDir, "4_manual_tracked.png")

        // A few more edits: split, resize, undo, trim.
        rule.runOnIdle {
            ctrl.select(manual.id)
            ctrl.splitSelected()
            ctrl.setSelectedScale(1.6f)
            ctrl.endSlider()
            ctrl.undo()
            ctrl.undo()
            ctrl.endHere()
        }
        note("after edits: ${rule.runOnIdle { ctrl.model.tracks.joinToString { "${it.id}:${it.start}-${it.end}x${"%.1f".format(it.scale)}" } }}")
        Thread.sleep(1000)
        shot(outDir, "5_after_edits.png")
        rule.runOnIdle { ctrl.togglePreview() }
        Thread.sleep(1200)
        shot(outDir, "6_outlines.png")

        // Export sheet, then the whole editor in English.
        rule.onNodeWithText("ייצוא").performClick()
        rule.waitForIdle()
        val opened = runCatching { rule.waitUntil(5000) { rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1 } }.isSuccess
        note("export sheet opened: $opened")
        Thread.sleep(1000)
        // The sheet is its own window: screenshot the whole display.
        screen(outDir, "7_export_sheet.png")
        // Back key to the sheet's window (Espresso's pressBack waits for the wrong window's focus).
        // Again if something else took it (the CI emulator sometimes shows a system "not
        // responding" dialog).
        for (attempt in 0 until 4) {
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            Thread.sleep(500)
            if (rule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1) break
        }
        rule.runOnIdle {
            I18n.lang = Lang.EN
            dir = LayoutDirection.Ltr
            ctrl.togglePreview()
            ctrl.focus(manual.id)
        }
        Thread.sleep(1500)
        shot(outDir, "8_english.png")
        rule.runOnIdle {
            I18n.lang = Lang.HE
            dir = LayoutDirection.Rtl
        }

        val saved = runBlocking(Dispatchers.Main) {
            FaceBlurProcessor(ctx).render(info, settings, analysis, ctrl.model, 0) {}
        }
        ctx.contentResolver.openInputStream(saved.uri)!!.use { i -> File(outDir, "edited.mp4").outputStream().use { i.copyTo(it) } }
        note("exported: blurs=${saved.faces} frames=${saved.frames} render=${saved.renderMs}ms")
        scope.cancel()
        session.close()
        if (frac < 0.5f) fail("manual tracker followed only $frac of the face")
    }

    /** Settings screen with the export card, then English with another palette, then app settings. */
    @Test
    fun mainScreen() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "editor").apply { mkdirs() }
        val name = inst.context.assets.list("samples")?.firstOrNull { it.startsWith("classroom60") }
            ?: inst.context.assets.list("samples")?.firstOrNull() ?: return
        val src = File(ctx.cacheDir, "main_$name")
        inst.context.assets.open("samples/$name").use { i -> src.outputStream().use { i.copyTo(it) } }
        val vm = inst.onMain { MainViewModel(ctx.applicationContext as android.app.Application) }
        rule.setContent {
            val prefs = vm.prefs
            FaceBlurTheme(prefs.themeMode, prefs.palette) {
                val d = if (prefs.lang == Lang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides d) { MainScreen(vm) }
            }
        }
        rule.waitForIdle()
        Thread.sleep(600)
        shot(outDir, "9a_home.png")
        inst.runOnMainSync { vm.load(Uri.fromFile(src)) }
        val t0 = System.currentTimeMillis()
        while (inst.onMain { vm.state !is UiState.Ready } && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(100)
        rule.waitForIdle()
        Thread.sleep(800)
        shot(outDir, "9_settings_he.png")
        rule.runOnIdle {
            vm.prefs.updateLang(Lang.EN)
            vm.prefs.updatePalette(Palette.GREEN)
            vm.prefs.updateThemeMode(ThemeMode.DARK)
            vm.prefs.updateExport(ExportOptions(shortSide = 720, fps = 30, quality = ExportQuality.SAVER))
        }
        Thread.sleep(800)
        shot(outDir, "10_settings_en.png")
        repeat(2) { rule.onRoot().performTouchInput { swipeUp() } }
        rule.waitForIdle()
        Thread.sleep(600)
        shot(outDir, "10b_export_card.png")
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        runCatching { rule.waitUntil(5000) { rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1 } }
        Thread.sleep(1000)
        screen(outDir, "11_app_settings.png")
        // Leave the defaults for other runs.
        rule.runOnIdle {
            vm.prefs.updateLang(Lang.HE)
            vm.prefs.updatePalette(Palette.BLUE)
            vm.prefs.updateThemeMode(ThemeMode.SYSTEM)
            vm.prefs.updateExport(ExportOptions())
        }
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
