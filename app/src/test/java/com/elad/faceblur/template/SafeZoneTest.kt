package com.elad.faceblur.template

import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZoneTest {
    private val tiktok = SafeZone.of(Platform.TIKTOK)
    private val reels = SafeZone.of(Platform.REELS)
    private val shorts = SafeZone.of(Platform.SHORTS)

    @Test
    fun theZonesAreTheMeasuredFractionsOfA1080x1920Frame() {
        assertEquals(240f / 1920f, tiktok.top, 1e-6f)
        assertEquals(660f / 1920f, tiktok.bottom, 1e-6f)
        assertEquals(120f / 1080f, tiktok.left, 1e-6f)
        assertEquals(180f / 1080f, tiktok.right, 1e-6f)
        assertEquals(0.14f, reels.top, 1e-6f)
        assertEquals(0.35f, reels.bottom, 1e-6f)
        assertEquals(0.06f, reels.left, 1e-6f)
        assertEquals(288f / 1920f, shorts.top, 1e-6f)
        assertEquals(672f / 1920f, shorts.bottom, 1e-6f)
        assertEquals(192f / 1080f, shorts.right, 1e-6f)
    }

    @Test
    fun tiktokAndReelsDifferMateriallyAtTheSides() {
        assertTrue(tiktok.left - reels.left > 0.04f)
        assertTrue(tiktok.right - reels.right > 0.08f)
    }

    @Test
    fun subtitlesLowInTheFrameAreMovedAboveThePlatformsBottom() {
        for (z in listOf(tiktok, reels, shorts)) {
            val p = z.fitSubtitlePosition(0.90f, 1f)
            assertTrue("$z $p", p < 0.90f)
            // The block's bottom edge (centre plus half its height) is inside the safe area.
            val half = 2 * 1.35f * 0.055f * 1f * (9f / 16f) / 2f
            assertTrue(p + half <= z.safeBottom + 1e-4f)
        }
    }

    @Test
    fun subtitlesAlreadyInsideStayWhereTheyAre() {
        assertEquals(0.5f, tiktok.fitSubtitlePosition(0.5f, 1f), 1e-6f)
    }

    @Test
    fun subtitlesHighInTheFrameAreMovedBelowTheTop() {
        val p = reels.fitSubtitlePosition(0.02f, 1f)
        assertTrue(p > reels.safeTop)
    }

    @Test
    fun biggerTextIsKeptHigherThanSmallText() {
        assertTrue(tiktok.fitSubtitlePosition(0.95f, 1.6f) < tiktok.fitSubtitlePosition(0.95f, 0.8f))
    }

    @Test
    fun fitSubtitlesKeepsEverythingButThePosition() {
        val style = SubtitleStyle(size = 1.2f, position = 0.9f)
        val fit = tiktok.fitSubtitles(style)
        assertEquals(style.copy(position = fit.position), fit)
        assertTrue(fit.position < 0.9f)
    }

    @Test
    fun theWatermarkKeepsToTheSafeAreaOrItsUsualMargin() {
        val m = tiktok.watermarkMargins(1080f, 1920f, base = 38f)
        assertEquals(120f, m.left, 0.01f)
        assertEquals(240f, m.top, 0.01f)
        assertEquals(180f, m.right, 0.01f)
        assertEquals(660f, m.bottom, 0.01f)
        // A zone smaller than the usual margin changes nothing.
        val none = SafeZone(0f, 0f, 0f, 0f).watermarkMargins(1080f, 1920f, base = 38f)
        assertEquals(SafeZone.Margins(38f, 38f, 38f, 38f), none)
    }

    @Test
    fun cornersAreInsideTheMargins() {
        // A 200 px logo, bottom right of a 1080 wide frame, right margin 180: its right edge at 900.
        assertEquals(700f, SafeZone.place(2, 200f, 1080f, 120f, 180f), 0.01f)
        assertEquals(120f, SafeZone.place(0, 200f, 1080f, 120f, 180f), 0.01f)
        // The middle is the middle of what is left.
        assertEquals(120f + (1080f - 120f - 180f - 200f) / 2f, SafeZone.place(1, 200f, 1080f, 120f, 180f), 0.01f)
        // With equal margins it is the frame's middle, as before.
        assertEquals(440f, SafeZone.place(1, 200f, 1080f, 38f, 38f), 0.01f)
    }

    @Test
    fun aDestinationPutsTheSubtitlesInItsZoneButOtherSettingsAreLeft() {
        val low = TemplateSettings(subStyle = SubtitleStyle(position = 0.9f))
        val s = applyAll(low, listOf(BuiltInTemplates.TIKTOK))
        assertEquals(Platform.TIKTOK, s.export.platform)
        assertTrue(s.subStyle.position < 0.9f)
        assertEquals(low.subStyle.copy(position = s.subStyle.position), s.subStyle)
        // No destination: untouched. "As the source" takes the platform away again.
        assertEquals(0.9f, applyAll(low, emptyList()).subStyle.position, 0f)
        val back = applyAll(low, listOf(BuiltInTemplates.TIKTOK, BuiltInTemplates.SOURCE))
        assertNull(back.export.platform)
        assertEquals(0.9f, back.subStyle.position, 0f)
    }

    @Test
    fun aLookAndADestinationInEitherOrderEndInTheZone() {
        val z = SafeZone.of(Platform.REELS)
        val s = applyAll(TemplateSettings(), listOf(BuiltInTemplates.MINIMAL, BuiltInTemplates.REELS))
        assertTrue(s.subStyle.position + 0.02f <= z.safeBottom)
        val t = applyAll(TemplateSettings(), listOf(BuiltInTemplates.REELS, BuiltInTemplates.MINIMAL))
        assertTrue(t.subStyle.position + 0.02f <= z.safeBottom)
    }

    @Test
    fun theSettingsStandAfterTheUserMovesTheSubtitlesByHand() {
        // The fit is made once, when the template is applied; a later position is the user's own.
        val s = applyAll(TemplateSettings(), listOf(BuiltInTemplates.TIKTOK))
        val moved = s.copy(subStyle = s.subStyle.copy(position = 0.93f))
        assertEquals(0.93f, moved.subStyle.position, 0f)
    }

    @Test
    fun theSplitDestinationsAreDistinct() {
        assertNotEquals(BuiltInTemplates.TIKTOK.export!!.platform, BuiltInTemplates.REELS.export!!.platform)
        assertEquals(setOf("dest.tiktok", "dest.reels", "dest.shorts", "dest.whatsapp", "dest.source"), BuiltInTemplates.DESTINATIONS.map { it.id }.toSet())
        assertNull(BuiltInTemplates.WHATSAPP.export!!.platform)
        assertNull(BuiltInTemplates.SOURCE.export!!.platform)
    }

    @Test
    fun thePlatformSurvivesSaving() {
        val t = BuiltInTemplates.SHORTS.copy(id = "u1")
        val back = Template.fromJson(JSONObject(t.toJson().toString()))!!
        assertEquals(Platform.SHORTS, back.export!!.platform)
        // A template without one reads as none.
        assertNull(Template.fromJson(JSONObject(BuiltInTemplates.WHATSAPP.copy(id = "w").toJson().toString()))!!.export!!.platform)
        val e = com.elad.faceblur.pipeline.ExportOptions(platform = Platform.REELS)
        assertEquals(Platform.REELS, com.elad.faceblur.pipeline.ExportOptions.fromJson(JSONObject(e.toJson().toString())).platform)
        assertNull(com.elad.faceblur.pipeline.ExportOptions.fromJson(JSONObject(com.elad.faceblur.pipeline.ExportOptions().toJson().toString())).platform)
    }

    private val id: (String) -> String = { it }

    @Test
    fun theSummaryLinesSayWhatADestinationSets() {
        assertEquals("9:16 · 1080p · 30fps · איכות גבוהה · אזור בטוח", DestinationSummary.line(BuiltInTemplates.TIKTOK.export!!, id))
        assertEquals("9:16 · 1080p · 30fps · איכות גבוהה · אזור בטוח", DestinationSummary.line(BuiltInTemplates.REELS.export!!, id))
        assertEquals("9:16 · 1080p · קצב מקורי · איכות גבוהה · אזור בטוח", DestinationSummary.line(BuiltInTemplates.SHORTS.export!!, id))
        assertEquals("צורה מקורית · 720p · 30fps · קובץ קטן · H.264", DestinationSummary.line(BuiltInTemplates.WHATSAPP.export!!, id))
        assertEquals("בלי שינוי בגודל ובאיכות", DestinationSummary.line(BuiltInTemplates.SOURCE.export!!, id))
    }

    @Test
    fun theSummaryIsTranslatedWordByWord() {
        val en = mapOf("איכות גבוהה" to "high quality", "אזור בטוח" to "safe zone")
        assertEquals("9:16 · 1080p · 30fps · high quality · safe zone", DestinationSummary.line(BuiltInTemplates.TIKTOK.export!!) { en[it] ?: it })
        val sq = ExportPart(ExportAspect.SQUARE, 720, 24, ExportQuality.ORIGINAL, ExportCodec.HEVC)
        assertEquals(listOf("1:1", "720p", "24fps", "איכות מקורית", "HEVC"), DestinationSummary.parts(sq, id))
    }
}
