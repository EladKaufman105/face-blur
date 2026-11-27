package com.elad.faceblur.template

import com.elad.faceblur.analysis.DetectMode
import com.elad.faceblur.effects.BlurMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast detection and the sensitivity in templates, and templates from before them. */
class TemplateDetectTest {
    private fun roundTrip(t: Template): Template = Template.fromJson(JSONObject(t.toJson().toString()))!!

    @Test
    fun fastAndSensitivitySurviveJson() {
        val t = Template(id = "f", name = "Fast", blur = BlurPart(false, BlurMode.BLUR, 0.6f, 1.6f, sensitive = false, fast = true, sensitivity = 0.35f))
        val back = roundTrip(t)
        assertEquals(t, back)
        assertEquals(DetectMode.FAST, back.blur!!.detect)
        val j = t.toJson().getJSONObject("blur")
        // Older versions read "sensitive" alone: false there, normal detection (never weaker than fast).
        assertFalse(j.getBoolean("sensitive"))
        assertTrue(j.getBoolean("fast"))
        assertEquals(0.35, j.getDouble("sensitivity"), 1e-6)
    }

    @Test
    fun templatesFromBeforeKeepTheirDetection() {
        val ext = Template.fromJson(JSONObject("{\"id\":\"a\",\"name\":\"n\",\"blur\":{\"mode\":\"BLUR\",\"sensitive\":true}}"))!!
        assertEquals(DetectMode.EXTENDED, ext.blur!!.detect)
        assertNull(ext.blur!!.sensitivity)
        val s = ext.applyTo(TemplateSettings(fast = true, sensitivity = 0.5f))
        assertTrue(s.sensitive)
        assertFalse(s.fast)
        // No sensitivity in the template: the one set stays.
        assertEquals(0.5f, s.sensitivity, 0f)

        val normal = Template.fromJson(JSONObject("{\"id\":\"b\",\"name\":\"n\",\"blur\":{\"sensitive\":false}}"))!!
        assertEquals(DetectMode.NORMAL, normal.blur!!.detect)
        val n = normal.applyTo(TemplateSettings(fast = true))
        assertFalse(n.sensitive)
        assertFalse(n.fast)

        // A blur part without either leaves the detection as it is.
        val look = Template.fromJson(JSONObject("{\"id\":\"c\",\"name\":\"n\",\"blur\":{\"mode\":\"SOLID\"}}"))!!
        assertNull(look.blur!!.detect)
        val l = look.applyTo(TemplateSettings(fast = true, sensitivity = 0.2f))
        assertTrue(l.fast)
        assertEquals(0.2f, l.sensitivity, 0f)
    }

    @Test
    fun fastAndExtendedTogetherIsExtended() {
        val t = Template(id = "x", name = "x", blur = BlurPart(sensitive = true, fast = true))
        assertEquals(DetectMode.EXTENDED, t.blur!!.detect)
        val s = t.applyTo(TemplateSettings())
        assertTrue(s.sensitive)
        assertFalse(s.fast)
    }

    @Test
    fun sensitivityIsKeptWithinZeroToOne() {
        val t = Template.fromJson(JSONObject("{\"id\":\"d\",\"name\":\"n\",\"blur\":{\"sensitivity\":4}}"))!!
        assertEquals(1f, t.blur!!.sensitivity!!, 0f)
        val u = Template.fromJson(JSONObject("{\"id\":\"e\",\"name\":\"n\",\"blur\":{\"sensitivity\":-1}}"))!!
        assertEquals(0f, u.blur!!.sensitivity!!, 0f)
        assertEquals(0f, u.applyTo(TemplateSettings(sensitivity = 0.6f)).sensitivity, 0f)
    }

    @Test
    fun sensitivityBetweenStepsRoundsUp() {
        val t = Template.fromJson(JSONObject("{\"id\":\"g\",\"name\":\"n\",\"blur\":{\"sensitivity\":0.31}}"))!!
        assertEquals(0.35f, t.blur!!.sensitivity!!, 1e-6f)
        val u = Template.fromJson(JSONObject("{\"id\":\"h\",\"name\":\"n\",\"blur\":{\"sensitivity\":0.01}}"))!!
        assertEquals(0.05f, u.blur!!.sensitivity!!, 1e-6f)
    }

    @Test
    fun savedTemplateHasTheChoices() {
        val t = TemplateSettings(fast = true, sensitivity = 0.25f).toTemplate("s", "Saved", setOf(TemplateCategory.BLUR))
        assertEquals(DetectMode.FAST, t.blur!!.detect)
        assertEquals(0.25f, t.blur!!.sensitivity!!, 0f)
        val back = roundTrip(t).applyTo(TemplateSettings())
        assertTrue(back.fast)
        assertEquals(0.25f, back.sensitivity, 0f)
    }
}
