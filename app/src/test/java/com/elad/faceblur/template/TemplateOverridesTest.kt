package com.elad.faceblur.template

import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.subs.SubEffect
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateOverridesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files() = TemplateFiles(File(tmp.root, "templates"))

    /** The user's version of Bold: a bigger text, the cover instead of the blur. */
    private val myBold = BuiltInTemplates.BOLD.copy(
        name = "My bold",
        blur = BuiltInTemplates.BOLD.blur!!.copy(mode = BlurMode.SOLID),
        subs = BuiltInTemplates.BOLD.subs!!.copy(style = SubtitleStyle(size = 1.5f, effect = SubEffect.POP)),
    )

    @Test
    fun anOverrideIsSavedUnderTheSameIdAndReadBackAsABuiltIn() {
        val f = files()
        f.writeOverride(myBold)
        val back = f.readOverrides()
        assertEquals(setOf("look.bold"), back.keys)
        val t = back.getValue("look.bold")
        assertTrue(t.builtIn)
        assertEquals(TemplateGroup.LOOK, t.group)
        assertEquals(myBold, t)
        // The shipped look itself is untouched.
        assertEquals(BlurMode.BLUR, BuiltInTemplates.BOLD.blur!!.mode)
    }

    @Test
    fun restoringBringsTheShippedLookBack() {
        val f = files()
        f.writeOverride(myBold)
        f.writeOverride(BuiltInTemplates.NEWS.copy(name = "x"))
        f.deleteOverride("look.bold")
        assertEquals(setOf("look.news"), f.readOverrides().keys)
        f.deleteOverride("look.news")
        assertTrue(f.readOverrides().isEmpty())
    }

    @Test
    fun overridesAndUserTemplatesStayApart() {
        val f = files()
        val mine = Template(id = "u1", name = "Mine", export = ExportPart(shortSide = 720))
        f.writeUser(mine)
        f.writeOverride(myBold)
        assertEquals(listOf(mine), f.readUser())
        assertEquals(setOf("look.bold"), f.readOverrides().keys)
        f.deleteUser("u1")
        assertTrue(f.readUser().isEmpty())
        assertEquals(setOf("look.bold"), f.readOverrides().keys)
    }

    @Test
    fun anOverrideOfALookThatIsNoLongerShippedIsIgnored() {
        val f = files()
        val dir = File(tmp.root, "templates/" + TemplateFiles.OVERRIDES).apply { mkdirs() }
        File(dir, "look.gone.json").writeText(Template(id = "look.gone", name = "Gone").toJson().toString())
        assertTrue(f.readOverrides().isEmpty())
        // Writing one for a user template is refused.
        val failed = runCatching { f.writeOverride(Template(id = "u2", name = "x")) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun aBrokenOverrideFileFallsBackToTheShippedLook() {
        val f = files()
        f.writeOverride(myBold)
        File(tmp.root, "templates/builtin/look.news.json").writeText("{ not json")
        assertEquals(setOf("look.bold"), f.readOverrides().keys)
    }

    @Test
    fun anOverrideWinsOverALaterShippedChange() {
        // The user's version is a full copy: whatever a newer app version ships for that look, it is read back as saved.
        val f = files()
        f.writeOverride(myBold)
        val shippedLater = BuiltInTemplates.BOLD.copy(subs = BuiltInTemplates.BOLD.subs!!.copy(style = SubtitleStyle(size = 3f)))
        val read = f.readOverrides().getValue("look.bold")
        assertNotNull(read)
        assertEquals(1.5f, read.subs!!.style!!.size, 0f)
        assertTrue(read.subs!!.style != shippedLater.subs!!.style)
    }

    @Test
    fun anOldOverrideFileWithoutTheNewerFieldsStillLoads() {
        // As an older version wrote a template: no "v", no "group", unknown fields.
        val dir = File(tmp.root, "templates/" + TemplateFiles.OVERRIDES).apply { mkdirs() }
        File(dir, "look.clean.json").writeText(
            """{"id":"look.clean","name":"נקי","somethingNew":{"a":1},"blur":{"mode":"PIXELATE","strength":0.4,"future":true}}""",
        )
        val t = files().readOverrides().getValue("look.clean")
        assertEquals(BlurMode.PIXELATE, t.blur!!.mode)
        assertEquals(0.4f, t.blur!!.strength!!, 0f)
        assertTrue(t.builtIn)
        assertNull(t.subs)
    }

    @Test
    fun userTemplatesFromEarlierVersionsStillLoad() {
        val dir = File(tmp.root, "templates").apply { mkdirs() }
        File(dir, "u7.json").writeText(
            """{"v":1,"id":"u7","name":"Old","group":"DESTINATION","export":{"aspect":"SQUARE","platform":"REELS"},"cuts":{"silences":true,"fillers":false}}""",
        )
        val t = files().readUser().single()
        assertEquals(TemplateGroup.USER, t.group)
        assertFalse(t.builtIn)
        assertEquals(800L, t.cuts!!.minPauseMs)
        // Saved by the new version it reads the same.
        val f = files()
        f.writeUser(t)
        assertEquals(t, f.readUser().single())
        assertEquals(t, Template.fromJson(JSONObject(t.toJson().toString())))
    }

    @Test
    fun picturesOfOverridesAreNotUserPictures() {
        val f = files()
        val lookPic = f.imageFile("look.bold")
        val userPic = f.imageFile("u1")
        assertEquals("builtin", lookPic.parentFile!!.name)
        assertEquals("templates", userPic.parentFile!!.name)
        lookPic.writeText("x"); userPic.writeText("x")
        // Pruning the user's pictures leaves the look's.
        f.pruneImages(emptySet())
        assertTrue(lookPic.exists())
        assertFalse(userPic.exists())
        f.deleteOverride("look.bold")
        assertFalse(lookPic.exists())
    }
}
