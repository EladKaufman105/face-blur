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
 * A batch of two public test clips (and one file that isn't a video, which fails without
 * stopping the others), run by the foreground service with a template: the Clean look and the
 * WhatsApp destination. Every good video must be saved to Movies/ONIT and as a project.
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
        val settings = applyAll(TemplateSettings(), listOf(BuiltInTemplates.CLEAN, BuiltInTemplates.WHATSAPP))
        val batch = Batch(items, settings, "Clean + WhatsApp")
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
            // 720p, as the WhatsApp destination says.
            val info = com.elad.faceblur.pipeline.VideoInfo.probe(ctx, items[0].output!!)
            assertTrue("short side at most 720 (was ${minOf(info.displayWidth, info.displayHeight)})", minOf(info.displayWidth, info.displayHeight) <= 720)
        } finally {
            for (id in made) ProjectStore.delete(ctx, id)
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
            val exportBefore = vm.prefs.export
            val styleBefore = vm.prefs.subStyle
            vm.chooseTemplates(com.elad.faceblur.template.TemplateChoice(look = BuiltInTemplates.CLEAN.id, dest = BuiltInTemplates.WHATSAPP.id))
            assertEquals("the template's resolution is used for this video", 720, vm.exportOptions.shortSide)
            assertEquals("the user's own export options are untouched", exportBefore, vm.prefs.export)
            assertEquals("the user's own subtitle look is untouched", styleBefore, vm.prefs.subStyle)
            vm.chooseTemplates(com.elad.faceblur.template.TemplateChoice())
            assertEquals(exportBefore, vm.exportOptions)
            assertEquals(styleBefore, vm.subtitleStyle)
        }
    }
}
