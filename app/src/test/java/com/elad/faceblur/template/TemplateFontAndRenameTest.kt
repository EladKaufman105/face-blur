package com.elad.faceblur.template

import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateFontAndRenameTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files() = TemplateFiles(File(tmp.root, "templates"))

    private fun withStyle(style: SubtitleStyle) = Template(id = "u7", name = "Mine", subs = SubsPart(create = true, style = style))

    private fun viaFile(t: Template): Template {
        val f = files()
        f.writeUser(t)
        return f.readUser().single()
    }

    @Test
    fun aTemplatesFontSurvivesTheFileAndLaysOverTheSettings() {
        val styles = listOf(
            SubtitleStyle(font = SubFont.ALLO, weight = 900, round = 100),
            SubtitleStyle(font = SubFont.SECULAR),
            SubtitleStyle(customFont = "My Font.ttf"),
        )
        for (style in styles) {
            val back = viaFile(withStyle(style))
            assertEquals(style, back.subs!!.style)
            // And it is what the video gets: the font of the template, over the user's own look.
            val applied = back.applyTo(TemplateSettings(subStyle = SubtitleStyle(font = SubFont.HEEBO)))
            assertEquals(style.font, applied.subStyle.font)
            assertEquals(style.customFont, applied.subStyle.customFont)
            assertEquals(style.weight, applied.subStyle.weight)
            assertEquals(style.round, applied.subStyle.round)
        }
    }

    @Test
    fun aTemplateWithoutSubtitlesLeavesTheFontAlone() {
        val own = SubtitleStyle(font = SubFont.FREDOKA)
        val t = withStyle(SubtitleStyle(font = SubFont.ALLO)).only(setOf(TemplateCategory.BLUR))
        assertEquals(own, t.applyTo(TemplateSettings(subStyle = own)).subStyle)
    }

    @Test
    fun aRenamedTemplateKeepsItsNameAndEverythingElseOnDisk() {
        val t = withStyle(SubtitleStyle(font = SubFont.RUBIK, weight = 600))
        val f = files()
        f.writeUser(t)
        f.writeUser(t.copy(name = "Renamed"))
        val all = f.readUser()
        // The same file: one template, not two.
        assertEquals(1, all.size)
        assertEquals("Renamed", all.single().name)
        assertEquals(t.copy(name = "Renamed"), all.single())
    }

    @Test
    fun theNameIsInTheJson() {
        val o: JSONObject = withStyle(SubtitleStyle()).copy(name = "שם חדש").toJson()
        assertEquals("שם חדש", Template.fromJson(JSONObject(o.toString()))!!.name)
    }

    @Test
    fun noFacesTemplateAsksForNoFaceBlurAndNoAnalysisShare() {
        val off = Template(id = "u8", name = "Subs only", blur = BlurPart(none = true)).applyTo(TemplateSettings())
        assertTrue(off.blurOff)
        assertEquals(com.elad.faceblur.batch.BatchMath.FRAMES_ONLY_SHARE, com.elad.faceblur.batch.BatchMath.analysisShare(off), 0f)
        val on = Template(id = "u9", name = "Blur", blur = BlurPart(none = false)).applyTo(TemplateSettings(blurOff = true))
        assertFalse(on.blurOff)
    }
}
