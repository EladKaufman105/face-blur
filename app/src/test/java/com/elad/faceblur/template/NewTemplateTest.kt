package com.elad.faceblur.template

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** "New template" (Settings): a template from scratch, as the web app's. */
class NewTemplateTest {
    @Test
    fun startsWithTheDefaultBlurAndSubtitlesOnly() {
        val tpl = blankTemplate("u1", "New template")
        assertEquals("u1", tpl.id)
        assertEquals("New template", tpl.name)
        assertEquals(TemplateGroup.USER, tpl.group)
        assertFalse(tpl.builtIn)
        assertEquals(setOf(TemplateCategory.BLUR, TemplateCategory.SUBTITLES), tpl.categories)
        // Laid over the default settings it changes nothing: it starts at the defaults.
        assertEquals(TemplateSettings(), tpl.applyTo(TemplateSettings()))
    }

    @Test
    fun savesAndReadsBackTheSame() {
        val tpl = blankTemplate("u2", "Mine")
        val back = Template.fromJson(JSONObject(tpl.toJson().toString()))!!
        assertEquals("Mine", back.name)
        assertEquals(tpl.categories, back.categories)
        assertEquals(tpl.applyTo(TemplateSettings()), back.applyTo(TemplateSettings()))
    }
}
