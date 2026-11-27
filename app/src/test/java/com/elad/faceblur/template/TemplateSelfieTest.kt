package com.elad.faceblur.template

import com.elad.faceblur.analysis.DetectMode
import com.elad.faceblur.analysis.DetectTuning
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.models.PackId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selfie detection in templates and settings: how it is chosen, saved, read back by older versions, and what it needs. */
class TemplateSelfieTest {
    private fun roundTrip(t: Template): Template = Template.fromJson(JSONObject(t.toJson().toString()))!!

    @Test
    fun selfieSurvivesJsonAndOlderVersionsReadNormal() {
        val t = Template(id = "s", name = "Selfie", blur = BlurPart(false, BlurMode.BLUR, 0.6f, 1.6f, sensitive = false, fast = false, sensitivity = 0.35f, selfie = true))
        val back = roundTrip(t)
        assertEquals(t, back)
        assertEquals(DetectMode.SELFIE, back.blur!!.detect)
        val j = t.toJson().getJSONObject("blur")
        assertTrue(j.getBoolean("selfie"))
        // An older version reads only "sensitive" and "fast": both false is normal detection there, never weaker than selfie.
        assertFalse(j.getBoolean("sensitive"))
        assertFalse(j.getBoolean("fast"))
        assertEquals(DetectMode.NORMAL, DetectMode.of(j.getBoolean("sensitive"), j.getBoolean("fast")))
    }

    @Test
    fun otherTemplatesFilesAreUnchanged() {
        // Made from settings: the selfie key is written only when on.
        for (s in listOf(TemplateSettings(), TemplateSettings(sensitive = true), TemplateSettings(fast = true, sensitivity = 0.2f))) {
            val j = s.toTemplate("x", "x", setOf(TemplateCategory.BLUR)).toJson().getJSONObject("blur")
            assertFalse(j.has("selfie"))
        }
        assertTrue(TemplateSettings(selfie = true).toTemplate("x", "x", setOf(TemplateCategory.BLUR)).toJson().getJSONObject("blur").getBoolean("selfie"))
        // Sensitive and fast win if they are set with it, and the file says only that.
        val both = TemplateSettings(selfie = true, fast = true).toTemplate("x", "x", setOf(TemplateCategory.BLUR))
        assertEquals(DetectMode.FAST, both.blur!!.detect)
        assertFalse(both.toJson().getJSONObject("blur").has("selfie"))
    }

    @Test
    fun templatesFromBeforeKeepTheirDetection() {
        val old = JSONObject("""{"v":1,"id":"o","name":"Old","blur":{"mode":"BLUR","strength":0.6,"padding":1.6,"sensitive":true,"fast":false}}""")
        val t = Template.fromJson(old)!!
        assertNull(t.blur!!.selfie)
        assertEquals(DetectMode.EXTENDED, t.blur!!.detect)
        val s = t.applyTo(TemplateSettings(selfie = true))
        assertTrue(s.sensitive)
        assertFalse(s.selfie)
        // A template with no detection at all leaves it alone.
        val look = Template(id = "l", name = "l", blur = BlurPart(mode = BlurMode.PIXELATE, strength = 0.8f))
        assertNull(look.blur!!.detect)
        assertTrue(look.applyTo(TemplateSettings(selfie = true, sensitivity = 0.4f)).selfie)
        assertEquals(DetectMode.SELFIE, look.applyTo(TemplateSettings(selfie = true)).detect)
    }

    @Test
    fun aTemplateSetsOneModeAndClearsTheOthers() {
        val selfie = Template(id = "s", name = "s", blur = BlurPart(sensitive = false, fast = false, selfie = true))
        val s = selfie.applyTo(TemplateSettings(fast = true, sensitivity = 0.5f))
        assertTrue(s.selfie)
        assertFalse(s.fast)
        assertFalse(s.sensitive)
        assertEquals(0.5f, s.sensitivity, 0f)
        val normal = Template(id = "n", name = "n", blur = BlurPart(sensitive = false, fast = false)).applyTo(TemplateSettings(selfie = true))
        assertFalse(normal.selfie)
        assertEquals(DetectMode.NORMAL, normal.detect)
        val fast = Template(id = "f", name = "f", blur = BlurPart(sensitive = false, fast = true)).applyTo(TemplateSettings(selfie = true))
        assertTrue(fast.fast)
        assertFalse(fast.selfie)
        val ext = Template(id = "e", name = "e", blur = BlurPart(sensitive = true, selfie = true)).applyTo(TemplateSettings())
        assertEquals(DetectMode.EXTENDED, ext.detect)
        assertFalse(ext.selfie)
    }

    @Test
    fun selfieNeedsItsPackUnlessThereIsNoFaceBlur() {
        assertEquals(listOf(PackId.FACES), TemplateSettings().neededPacks)
        assertEquals(listOf(PackId.FACES), TemplateSettings(fast = true).neededPacks)
        assertEquals(listOf(PackId.FACES, PackId.SELFIE), TemplateSettings(selfie = true).neededPacks)
        assertTrue(TemplateSettings(selfie = true, blurOff = true).neededPacks.isEmpty())
        // Extended wins: no selfie pack.
        assertEquals(listOf(PackId.FACES), TemplateSettings(selfie = true, sensitive = true).neededPacks)
    }

    @Test
    fun savedAsATemplateAndAppliedAgainIsTheSame() {
        val s = TemplateSettings(selfie = true, sensitivity = 0.25f)
        val back = roundTrip(s.toTemplate("s", "Saved", setOf(TemplateCategory.BLUR))).applyTo(TemplateSettings())
        assertTrue(back.selfie)
        assertEquals(0.25f, back.sensitivity, 0f)
        assertEquals(DetectMode.SELFIE, back.detect)
    }

    @Test
    fun theModesChoiceAndItsCadence() {
        assertEquals(DetectMode.NORMAL, DetectMode.of(false, false, false))
        assertEquals(DetectMode.NORMAL, DetectMode.of(false, false))
        assertEquals(DetectMode.SELFIE, DetectMode.of(false, false, true))
        assertEquals(DetectMode.FAST, DetectMode.of(false, true, true))
        assertEquals(DetectMode.EXTENDED, DetectMode.of(true, false, true))
        assertEquals(DetectMode.EXTENDED, DetectMode.of(true, true, true))
        // Every frame, and never fewer.
        for (fps in listOf(5f, 24f, 29.97f, 30f, 60f, 120f)) assertEquals(1 to 1, DetectTuning.cadence(DetectMode.SELFIE, fps))
        // The other modes' pacing is as it was.
        assertEquals(1 to 1, DetectTuning.cadence(DetectMode.EXTENDED, 30f))
        assertEquals(2 to 3, DetectTuning.cadence(DetectMode.NORMAL, 30f))
        assertEquals(6 to 6, DetectTuning.cadence(DetectMode.FAST, 30f))
    }
}
