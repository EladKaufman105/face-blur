package com.elad.faceblur.models

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.MainViewModel
import com.elad.faceblur.UiState
import com.elad.faceblur.analysis.FaceAnalyzer
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.pipeline.FaceBlurProcessor
import com.elad.faceblur.pipeline.Settings
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.ui.FaceBlurTheme
import com.elad.faceblur.ui.MainScreen
import com.elad.faceblur.ui.ModelsIntroSheet
import com.elad.faceblur.ui.ModelsSettingsSheet
import com.elad.faceblur.ui.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The privacy gate around the face model, and the store's checks: face blur never runs without
 * the whole face pack as the registry pins it (no weaker detector, no partial pack, no changed
 * file); a saved project still exports without it; the screens show "download to use" instead;
 * older installs' files are adopted. No network: the packs come from the test assets ([ModelSeed]).
 */
@RunWith(AndroidJUnit4::class)
class ModelGateTest {
    @get:Rule
    val rule = createComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val store = ModelStore.get(ctx)
    private val outDir = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    @After
    fun putBack() {
        // The other tests need the packs.
        ModelSeed.install()
    }

    private fun sample(): File {
        val names = inst.context.assets.list("samples")?.toList().orEmpty()
        val name = listOf("walk.mp4", "headpose.mp4").firstOrNull { it in names } ?: names.firstOrNull { it.endsWith(".mp4") } ?: run {
            fail("no sample video in the test assets")
            error("unreachable")
        }
        return File(ctx.cacheDir, "gate_$name").also { f -> inst.context.assets.open("samples/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
    }

    private fun assertMissing(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what ran without the face pack")
        } catch (e: ModelMissing) {
            assertEquals(PackId.FACES, e.pack)
        }
    }

    private fun shot(name: String) {
        Thread.sleep(500)
        val b: Bitmap = inst.uiAutomation.takeScreenshot() ?: return
        File(outDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun faceBlurNeedsTheWholePack() {
        ModelSeed.install(listOf(PackId.FACES))
        assertTrue(store.delete(PackId.FACES))
        assertEquals(Installed.MISSING, store.status(PackId.FACES).installed)
        assertMissing("the analyzer") { FaceAnalyzer(ctx, false, 30f).close() }
        val info = VideoInfo.probe(ctx, Uri.fromFile(sample()))
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        assertMissing("an analysis") { runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).analyze(info, settings) {} } }

        // Part of the pack: one GPU model gone. Not "the CPU will do": nothing runs.
        ModelSeed.install(listOf(PackId.FACES))
        assertTrue(File(store.dir, "retinaface-mv2-736x1280.tflite").delete())
        assertMissing("a partial pack") { store.require(PackId.FACES) }
        assertFalse("a partial pack isn't usable", store.isUsable(PackId.FACES))

        // A changed file of the same size: caught by its SHA-256 before use.
        ModelSeed.install(listOf(PackId.FACES))
        val yunet = File(store.dir, "face_detection_yunet_2023mar.onnx")
        val bytes = yunet.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        yunet.writeBytes(bytes)
        assertMissing("a changed model") { FaceAnalyzer(ctx, false, 30f).close() }
        assertEquals(Installed.MISSING, store.status(PackId.FACES).installed)

        // Whole again: it runs.
        ModelSeed.install(listOf(PackId.FACES))
        FaceAnalyzer(ctx, false, 30f).close()
    }

    @Test
    fun aPackInUseIsNotDeleted() {
        ModelSeed.install(listOf(PackId.FACES))
        store.acquire(PackId.FACES)
        assertFalse("deleted while in use", store.delete(PackId.FACES))
        store.release(PackId.FACES)
        assertTrue(store.delete(PackId.FACES))
        assertFalse(File(store.dir, "retinaface_mv2.onnx").exists())
    }

    /** A saved project keeps its analysis: it exports with the face pack deleted. */
    @Test
    fun aSavedAnalysisExportsWithoutTheModel() {
        ModelSeed.install(listOf(PackId.FACES))
        val info = VideoInfo.probe(ctx, Uri.fromFile(sample()))
        val settings = Settings(BlurMode.BLUR, 0.6f, 1.6f, false)
        val analysis = runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).analyze(info, settings) {} }
        assertTrue(store.delete(PackId.FACES))
        val session = com.elad.faceblur.edit.EditSession(info, settings, analysis, 0)
        try {
            val saved = runBlocking(Dispatchers.Main) { FaceBlurProcessor(ctx).render(info, settings, analysis, session.model, 0) {} }
            assertTrue("exported", saved.uri.toString().isNotEmpty())
        } finally {
            session.close()
        }
    }

    /** Older versions copied the bundled models to filesDir and downloaded the GPU ones: adopted when their SHA-256 is the registry's. */
    @Test
    fun olderInstallsAreAdopted() {
        ModelSeed.install(listOf(PackId.FACES))
        val keep = File(ctx.cacheDir, "adopt").apply { deleteRecursively(); mkdirs() }
        val files = store.registry[PackId.FACES].files.map { it.name }
        for (n in files) File(store.dir, n).copyTo(File(keep, n), overwrite = true)
        assertTrue(store.delete(PackId.FACES))
        // As version 2.0 left them: the CPU models in filesDir, the GPU ones in filesDir/models.
        File(keep, "retinaface_mv2.onnx").copyTo(File(ctx.filesDir, "retinaface_mv2.onnx"), overwrite = true)
        File(keep, "face_detection_yunet_2023mar.onnx").copyTo(File(ctx.filesDir, "face_detection_yunet_2023mar.onnx"), overwrite = true)
        for (n in files.filter { it.endsWith(".tflite") }) File(keep, n).copyTo(File(store.dir, n), overwrite = true)
        // An old copy that isn't the registry's: not adopted, removed.
        File(ctx.filesDir, "human_segmentation_pphumanseg_2023mar.onnx").writeText("an older model")
        store.migrate()
        assertEquals(Installed.READY, store.status(PackId.FACES).installed)
        assertEquals(files.size, store.require(PackId.FACES).size)
        assertFalse(File(ctx.filesDir, "retinaface_mv2.onnx").exists())
        assertFalse(File(ctx.filesDir, "human_segmentation_pphumanseg_2023mar.onnx").exists())
        keep.deleteRecursively()
    }

    /** The Ready screen without the face pack: its "download to use" card, and no way to start blurring. */
    @Test
    fun readyScreenAsksForTheModel() {
        ModelSeed.install()
        assertTrue(store.delete(PackId.FACES))
        val vm = onMain { MainViewModel(ctx.applicationContext as android.app.Application) }
        rule.setContent {
            val prefs = vm.prefs
            FaceBlurTheme(prefs.themeMode, prefs.palette) {
                val d = if (prefs.lang == Lang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides d) { MainScreen(vm) }
            }
        }
        inst.runOnMainSync { vm.load(Uri.fromFile(sample())) }
        val t0 = System.currentTimeMillis()
        while (onMain { vm.state !is UiState.Ready } && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(100)
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.modelCard(PackId.FACES)).performScrollTo()
        rule.onNodeWithTag(Tags.modelDownload(PackId.FACES)).assertIsEnabled()
        rule.onNodeWithTag(Tags.BLUR_FACES).assertIsNotEnabled()
        shot("1_ready_needs_model.png")
        // Starting anyway does nothing.
        inst.runOnMainSync { vm.start() }
        assertTrue(onMain { vm.state is UiState.Ready })

        ModelSeed.install(listOf(PackId.FACES))
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag(Tags.modelCard(PackId.FACES)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(Tags.BLUR_FACES).assertIsEnabled()
        shot("2_ready_with_model.png")
    }

    /** The first-launch sheet and Settings › Models (nothing is downloaded: no button that downloads is tapped). */
    @Test
    fun sheets() {
        ModelSeed.install()
        assertTrue(store.delete(PackId.PEOPLE))
        var which by mutableStateOf(0)
        rule.setContent {
            FaceBlurTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    when (which) {
                        0 -> ModelsIntroSheet(onDismiss = {})
                        else -> ModelsSettingsSheet(onDismiss = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.MODELS_SHEET).assertExists()
        shot("3_first_launch.png")
        rule.onNodeWithTag(Tags.MODELS_CHOOSE).performClick()
        rule.waitForIdle()
        shot("4_first_launch_choose.png")
        rule.runOnIdle { which = 1 }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.MODELS_SCREEN).assertExists()
        rule.onNodeWithTag(Tags.modelDownload(PackId.PEOPLE)).assertExists()
        shot("5_settings_models.png")
        // Nothing was asked for: no download started.
        assertTrue(store.status(PackId.PEOPLE).job == null)
    }

    private fun <T> onMain(block: () -> T): T {
        var r: Any? = null
        inst.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }
}
