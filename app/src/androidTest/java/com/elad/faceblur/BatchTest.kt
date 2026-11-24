package com.elad.faceblur

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elad.faceblur.batch.Batch
import com.elad.faceblur.batch.BatchItem
import com.elad.faceblur.batch.BatchService
import com.elad.faceblur.batch.BatchState
import com.elad.faceblur.batch.ItemStatus
import com.elad.faceblur.project.ProjectStore
import com.elad.faceblur.subs.ModelManager
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.template.BuiltInTemplates
import com.elad.faceblur.template.TemplateSettings
import com.elad.faceblur.template.applyAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Batch processing end to end (see also SubtitlesTest, which puts whisper's tiny model in the
 * test assets). A batch of two public test clips (and one file that isn't a video, which fails without
 * stopping the others), run by the foreground service with a template: the Clean look.
 * Every good video must be saved to Movies/ONIT and as a project.
 */
@RunWith(AndroidJUnit4::class)
class BatchTest {
    @Test
    fun batchOfTwoWithAFailureInTheMiddle() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        fun copy(name: String): File = File(ctx.cacheDir, name).also { f -> assets.open("samples/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }

        val bogus = File(ctx.cacheDir, "not_a_video.mp4").apply { writeText("this is not a video") }
        val clips = listOf("headpose.mp4", "portrait_rot90.mp4").map { copy(it) }
        val files = listOf(clips[0], bogus, clips[1])
        val items = files.mapIndexed { i, f -> BatchItem(i, Uri.fromFile(f), f.nameWithoutExtension, 5_000, null) }
        // The look creates subtitles, which needs the speech model: this test is about the rest.
        val settings = applyAll(TemplateSettings(), listOf(BuiltInTemplates.CLEAN)).copy(createSubs = false)
        val batch = Batch(items, settings, "Clean")
        val made = ArrayList<String>()
        try {
            inst.runOnMainSync { BatchState.current = batch }
            BatchService.start(ctx)
            val t0 = System.currentTimeMillis()
            while (!batch.finished && System.currentTimeMillis() - t0 < 600_000) Thread.sleep(500)
            assertTrue("the batch finished", batch.finished)

            val status = items.map { it.status }
            assertEquals("a video that can't be read fails: $status", ItemStatus.FAILED, items[1].status)
            assertNotNull(items[1].error)
            for (i in listOf(0, 2)) {
                val item = items[i]
                assertTrue("${item.name} was saved or flagged for no faces, not ${item.status} (${item.error})", item.status == ItemStatus.SAVED || item.status == ItemStatus.NO_FACES)
                val out = item.output
                assertNotNull("${item.name} has its output in Movies/ONIT", out)
                val size = ctx.contentResolver.openAssetFileDescriptor(out!!, "r")!!.use { d -> d.length }
                assertTrue("${item.name}'s output isn't empty", size > 10_000)
                val id = item.projectId
                assertNotNull("${item.name} is a project", id)
                made += id!!
                assertTrue("the project is listed", ProjectStore.list(ctx).any { p -> p.id == id })
            }
            // The batch's projects are one folder: they share the batch's id.
            val metas = ProjectStore.list(ctx).filter { it.id in made }
            assertEquals("both projects carry the batch id", setOf(batch.id), metas.mapNotNull { it.batchId }.toSet())
            assertEquals(2, metas.size)
        } finally {
            for (id in made) ProjectStore.delete(ctx, id)
            inst.runOnMainSync { BatchState.current = null }
        }
    }

    /** The speech model of a test: whisper's tiny one from the test assets, put where the app looks for [SpeechModel.MULTILINGUAL]. */
    private fun installTinyModel(): File {
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = ModelManager(inst.targetContext).file(SpeechModel.MULTILINGUAL)
        file.parentFile?.mkdirs()
        inst.context.assets.open("models/ggml-tiny.bin").use { i -> file.outputStream().use { i.copyTo(it) } }
        return file
    }

    private fun runBatch(items: List<BatchItem>, settings: TemplateSettings): Batch {
        val inst = InstrumentationRegistry.getInstrumentation()
        val batch = Batch(items, settings, "test")
        inst.runOnMainSync { BatchState.current = batch }
        BatchService.start(inst.targetContext)
        val t0 = System.currentTimeMillis()
        while (!batch.finished && System.currentTimeMillis() - t0 < 900_000) Thread.sleep(500)
        assertTrue("the batch finished", batch.finished)
        return batch
    }

    /**
     * A template that creates subtitles (a built-in look, which turns it on) must give a video with
     * speech its subtitles in a batch: they are in the project and nothing is flagged.
     */
    @Test
    fun aLookCreatesSubtitlesInABatch() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val clip = File(ctx.cacheDir, "speech_en_batch.mp4")
        inst.context.assets.open("samples/speech_en.mp4").use { i -> clip.outputStream().use { i.copyTo(it) } }
        val look = applyAll(TemplateSettings(), listOf(BuiltInTemplates.BOLD))
        assertTrue("the look asks for subtitles", look.createSubs)
        // Whisper's tiny model stands in for the big ones, which CI doesn't download (the code path is the same).
        val settings = look.copy(speechModel = SpeechModel.MULTILINGUAL, language = "en", translate = false)
        val model = ModelManager(ctx).file(SpeechModel.MULTILINGUAL)
        val hadModel = model.exists()
        val backup = if (hadModel) File(ctx.cacheDir, "model_backup").also { model.copyTo(it, overwrite = true) } else null
        val item = BatchItem(0, Uri.fromFile(clip), "speech", 10_000, null)
        var projectId: String? = null
        try {
            installTinyModel()
            val batch = runBatch(listOf(item), settings)
            projectId = item.projectId
            assertTrue("saved, not ${item.status} (${item.error}; ${item.failures})", item.status == ItemStatus.SAVED || item.status == ItemStatus.NO_FACES)
            assertTrue("no template step failed: ${item.failures}", item.failures.isEmpty() && !item.stepFailed)
            assertNotNull("a project", projectId)
            val meta = ProjectStore.list(ctx).first { it.id == projectId }
            assertTrue("the project has subtitles (${meta.cues})", meta.cues > 0)
            assertNotNull("its output", item.output)
            assertEquals(batch.id, meta.batchId)
        } finally {
            projectId?.let { ProjectStore.delete(ctx, it) }
            if (backup != null) backup.copyTo(model, overwrite = true) else model.delete()
            inst.runOnMainSync { BatchState.current = null }
        }
    }

    /** A step that can't run (no speech model) flags the video with the reason; it is not skipped silently. */
    @Test
    fun aMissingSpeechModelIsFlaggedOnTheVideo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val model = ModelManager(ctx).file(SpeechModel.HEBREW)
        org.junit.Assume.assumeFalse("the Hebrew model is on this device", model.exists())
        val clip = File(ctx.cacheDir, "speech_en_batch2.mp4")
        inst.context.assets.open("samples/speech_en.mp4").use { i -> clip.outputStream().use { i.copyTo(it) } }
        val settings = applyAll(TemplateSettings(), listOf(BuiltInTemplates.CLEAN)).copy(blurOff = true)
        val item = BatchItem(0, Uri.fromFile(clip), "speech", 10_000, null)
        var projectId: String? = null
        try {
            runBatch(listOf(item), settings)
            projectId = item.projectId
            assertTrue("it has a reason", item.failures.isNotEmpty())
            assertTrue("flagged for a look", item.stepFailed && item.needsCheck)
        } finally {
            projectId?.let { ProjectStore.delete(ctx, it) }
            inst.runOnMainSync { BatchState.current = null }
        }
    }

    /** A project keeps its own copy of a template's background picture. */
    @Test
    fun backgroundPictureIsCopiedForTheProject() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(ctx.cacheDir, "tpl_bg_test.jpg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val bg = com.elad.faceblur.effects.Background(fill = com.elad.faceblur.effects.BgFill.IMAGE, image = original.absolutePath)
        val own = com.elad.faceblur.effects.Background.withOwnCopy(ctx, bg)
        try {
            assertTrue("the copy is another file", own.image != original.absolutePath)
            original.delete()
            assertTrue("the copy stays when the template's picture is gone", File(own.image!!).exists())
        } finally {
            own.image?.let { File(it).delete() }
            original.delete()
        }
    }

    /** Choosing a template is for one video: the user's own export options and subtitle look are not written. */
    @Test
    fun templateDoesNotChangeTheUsersOwnSettings() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as android.app.Application
        inst.runOnMainSync {
            val vm = MainViewModel(app)
            // The user's own template with a 720p export (the built-in looks leave the export alone).
            val id = vm.templates.newId()
            vm.templates.save(
                com.elad.faceblur.template.Template(
                    id = id, name = "test 720",
                    subs = com.elad.faceblur.template.SubsPart(style = BuiltInTemplates.CLEAN.subs!!.style),
                    export = com.elad.faceblur.template.ExportPart(shortSide = 720),
                ),
            )
            try {
                val exportBefore = vm.prefs.export
                val styleBefore = vm.prefs.subStyle
                vm.chooseTemplates(com.elad.faceblur.template.TemplateChoice(id))
                assertEquals("the template's resolution is used for this video", 720, vm.exportOptions.shortSide)
                assertEquals("the user's own export options are untouched", exportBefore, vm.prefs.export)
                assertEquals("the user's own subtitle look is untouched", styleBefore, vm.prefs.subStyle)
                vm.chooseTemplates(com.elad.faceblur.template.TemplateChoice())
                assertEquals(exportBefore, vm.exportOptions)
                assertEquals(styleBefore, vm.subtitleStyle)
            } finally {
                vm.templates.delete(id)
            }
        }
    }
}
