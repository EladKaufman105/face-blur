package com.elad.faceblur.template

import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.subs.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateLogoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files() = TemplateFiles(File(tmp.root, "templates"))
    private fun staged(text: String): File = File(tmp.root, "stage-" + text + ".png").apply { writeText(text) }

    @Test
    fun savingCopiesTheStagedLogoIn() {
        val f = files()
        val path = f.commitLogo("u1", staged("a").path)
        assertEquals(f.logoFile("u1").path, path)
        assertEquals("a", f.logoFile("u1").readText())
        // A newer pick replaces it.
        f.commitLogo("u1", staged("b").path)
        assertEquals("b", f.logoFile("u1").readText())
        // Saving again with the template's own file keeps it.
        assertEquals(f.logoFile("u1").path, f.commitLogo("u1", f.logoFile("u1").path))
        assertEquals("b", f.logoFile("u1").readText())
    }

    @Test
    fun discardingNeverTouchesTheSavedLogo() {
        val f = files()
        f.commitLogo("u1", staged("a").path)
        // The editor picks another logo into its staging file and the user leaves without saving: no commit.
        val stage = staged("b")
        stage.delete()
        assertEquals("a", f.logoFile("u1").readText())
    }

    @Test
    fun noLogoOnSaveRemovesTheFile() {
        val f = files()
        f.commitLogo("u1", staged("a").path)
        assertNull(f.commitLogo("u1", null))
        assertFalse(f.logoFile("u1").exists())
    }

    @Test
    fun aMissingStagingFileKeepsTheSavedLogo() {
        val f = files()
        f.commitLogo("u1", staged("a").path)
        assertEquals(f.logoFile("u1").path, f.commitLogo("u1", File(tmp.root, "gone.png").path))
        assertNull(files().commitLogo("u2", File(tmp.root, "gone.png").path))
    }

    @Test
    fun restoringTheOriginalRemovesTheOverridesLogo() {
        val f = files()
        val id = BuiltInTemplates.BOLD.id
        f.writeOverride(BuiltInTemplates.BOLD.copy(name = "x"))
        f.commitLogo(id, staged("a").path)
        assertTrue(f.logoFile(id).path.contains(TemplateFiles.OVERRIDES))
        f.deleteOverride(id)
        assertFalse(f.logoFile(id).exists())
    }

    @Test
    fun deletingATemplateOrPruningRemovesItsLogo() {
        val f = files()
        f.writeUser(Template(id = "u1", name = "a"))
        f.writeUser(Template(id = "u2", name = "b"))
        f.commitLogo("u1", staged("a").path)
        f.commitLogo("u2", staged("b").path)
        f.deleteUser("u1")
        assertFalse(f.logoFile("u1").exists())
        f.pruneImages(emptySet())
        assertFalse(f.logoFile("u2").exists())
    }

    @Test
    fun theLogoPathSurvivesTheJsonAndTheProjectKeepsItsOwnCopy() {
        val w = Watermark(image = true, logo = "/x/u1.wm.png")
        assertEquals("/x/u1.wm.png", Watermark.fromJson(w.toJson()).logo)
        assertNull(Watermark.fromJson(Watermark(image = true).toJson()).logo)

        val src = staged("a")
        val dir = File(tmp.root, "project")
        val own = Watermark.withOwnCopy(Watermark(image = true, logo = src.path), dir)
        assertEquals(File(dir, "watermark.png").path, own.logo)
        src.delete()
        assertEquals("a", File(own.logo!!).readText())
        // No logo of its own: as it is.
        assertEquals(Watermark(image = true), Watermark.withOwnCopy(Watermark(image = true), dir))
    }

    private fun subs(model: SpeechModel, lang: String) = TemplateSettings(createSubs = true, speechModel = model, language = lang)

    @Test
    fun speechModelFallbackOnlyWhenTheOtherModelCanDoTheJob() {
        val onlyMulti = { m: SpeechModel -> m == SpeechModel.MULTILINGUAL }
        val onlyHebrew = { m: SpeechModel -> m == SpeechModel.HEBREW }
        // Hebrew asked, multilingual there: it is used, the language stays Hebrew.
        assertEquals(subs(SpeechModel.MULTILINGUAL, "he"), fitSpeechModel(subs(SpeechModel.HEBREW, "he"), onlyMulti))
        // Multilingual asked in English or auto: the Hebrew model can't do it, the download is offered.
        assertEquals(subs(SpeechModel.MULTILINGUAL, "en"), fitSpeechModel(subs(SpeechModel.MULTILINGUAL, "en"), onlyHebrew))
        assertEquals(subs(SpeechModel.MULTILINGUAL, "auto"), fitSpeechModel(subs(SpeechModel.MULTILINGUAL, "auto"), onlyHebrew))
        // Multilingual asked for Hebrew: the Hebrew model is used.
        assertEquals(subs(SpeechModel.HEBREW, "he"), fitSpeechModel(subs(SpeechModel.MULTILINGUAL, "he"), onlyHebrew))
        // Nothing to change: the model is there, or no subtitles are made, or nothing is there.
        assertEquals(subs(SpeechModel.HEBREW, "he"), fitSpeechModel(subs(SpeechModel.HEBREW, "he"), onlyHebrew))
        val off = TemplateSettings(createSubs = false, speechModel = SpeechModel.HEBREW)
        assertEquals(off, fitSpeechModel(off, onlyMulti))
        assertEquals(subs(SpeechModel.HEBREW, "he"), fitSpeechModel(subs(SpeechModel.HEBREW, "he"), { false }))
    }
}
