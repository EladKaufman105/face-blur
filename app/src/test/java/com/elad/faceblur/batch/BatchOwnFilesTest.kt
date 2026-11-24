package com.elad.faceblur.batch

import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.Background
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.template.TemplateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchOwnFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = File(tmp.root, name).apply { writeText(text) }

    private fun settings(logo: File, bg: File) = TemplateSettings().let {
        it.copy(
            export = it.export.copy(watermark = Watermark(image = true, logo = logo.path)),
            background = Background(fill = BgFill.IMAGE, image = bg.path),
        )
    }

    @Test
    fun batchKeepsItsOwnCopiesWhenTheTemplateChanges() {
        val logo = file("t1.wm.png", "logo-v1")
        val bg = file("t1.bg.jpg", "bg-v1")
        val dir = File(tmp.root, "batches")
        val own = BatchOwnFiles.own(settings(logo, bg), dir, "b1")
        val ownLogo = File(own.export.watermark.logo!!)
        val ownBg = File(own.background.image!!)
        assertNotEquals(logo.path, ownLogo.path)
        assertNotEquals(bg.path, ownBg.path)
        // The template is re-saved, then deleted, while the batch runs.
        logo.writeText("logo-v2")
        logo.delete()
        bg.delete()
        assertEquals("logo-v1", ownLogo.readText())
        assertEquals("bg-v1", ownBg.readText())
    }

    @Test
    fun deleteRemovesTheCopies() {
        val dir = File(tmp.root, "batches")
        val own = BatchOwnFiles.own(settings(file("a.wm.png", "x"), file("a.bg.jpg", "y")), dir, "b1")
        BatchOwnFiles.delete(dir, "b1")
        assertFalse(File(own.export.watermark.logo!!).exists())
        assertFalse(File(own.background.image!!).exists())
    }

    @Test
    fun noLogoOrPictureLeavesSettingsAlone() {
        val s = TemplateSettings()
        assertEquals(s, BatchOwnFiles.own(s, File(tmp.root, "batches"), "b1"))
    }

    @Test
    fun staleCopiesOfEarlierBatchesAreRemoved() {
        val dir = File(tmp.root, "batches").apply { mkdirs() }
        val old = File(dir, "old.wm.png").apply { writeText("z") }
        BatchOwnFiles.own(TemplateSettings(), dir, "b2")
        assertFalse(old.exists())
        assertTrue(dir.exists())
    }
}
