package com.elad.faceblur.template

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.Det
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.batch.BatchMath
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubEffect
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

class TemplateTest {
    private val full = Template(
        id = "u1", name = "My template",
        blur = BlurPart(false, BlurMode.PIXELATE, 0.8f, 1.8f, true),
        subs = SubsPart(
            create = true, model = SpeechModel.MULTILINGUAL, language = "auto", translate = true,
            style = SubtitleStyle(font = SubFont.RUBIK, size = 1.3f, effect = SubEffect.POP, position = 0.6f, weight = 700),
            splitWords = 4, splitChars = 0, burn = SubLang.BOTH, saveSrt = false,
        ),
        voice = VoicePart(AudioEdit(voice = VoiceFx.DEEP, denoise = 2, enhance = true, loud = true, gainDb = 5f)),
        cuts = CutsPart(silences = true, minPauseMs = 1200L, fillers = true),
        background = Background(fill = BgFill.BLUR),
        stabilize = 2,
        export = ExportPart(ExportAspect.VERTICAL, 1080, 30, ExportQuality.HIGH, ExportCodec.H264, Watermark(text = "ONIT")),
    )

    private fun roundTrip(t: Template): Template = Template.fromJson(JSONObject(t.toJson().toString()))!!

    @Test
    fun fullTemplateSurvivesJson() {
        val back = roundTrip(full)
        // The loudness gain is measured per video: it isn't kept.
        assertEquals(full.copy(voice = VoicePart(full.voice!!.audio.copy(gainDb = 0f))), back)
    }

    @Test
    fun builtInsSurviveJson() {
        // The built-in flag is not stored: a template read from a file is never a built-in.
        for (t in BuiltInTemplates.all) assertEquals(t.id, t.copy(builtIn = false), roundTrip(t))
    }

    @Test
    fun missingCategoriesStayMissing() {
        val t = Template(id = "x", name = "only export", export = ExportPart(aspect = ExportAspect.SQUARE))
        val back = roundTrip(t)
        assertNull(back.blur)
        assertNull(back.subs)
        assertNull(back.voice)
        assertNull(back.cuts)
        assertNull(back.background)
        assertNull(back.stabilize)
        assertEquals(ExportAspect.SQUARE, back.export!!.aspect)
        assertNull(back.export!!.shortSide)
    }

    @Test
    fun brokenJsonIsNotATemplate() {
        assertNull(Template.fromJson(null))
        assertNull(Template.fromJson(JSONObject("{\"name\":\"no id\"}")))
        assertNotNull(Template.fromJson(JSONObject("{\"id\":\"a\",\"name\":\"n\",\"export\":{\"aspect\":\"NOPE\"}}")))
    }

    @Test
    fun applyingReplacesIncludedCategories() {
        val s = full.applyTo(TemplateSettings())
        assertEquals(BlurMode.PIXELATE, s.mode)
        assertEquals(0.8f, s.strength, 0f)
        assertEquals(1.8f, s.padding, 0f)
        assertTrue(s.sensitive)
        assertTrue(s.createSubs)
        assertEquals(SpeechModel.MULTILINGUAL, s.speechModel)
        assertEquals("auto", s.language)
        assertTrue(s.translate)
        assertEquals(SubEffect.POP, s.subStyle.effect)
        assertEquals(SplitRule(4, 0), s.split)
        assertEquals(SubLang.BOTH, s.export.subs)
        assertFalse(s.export.saveSrt)
        assertEquals(VoiceFx.DEEP, s.audio.voice)
        assertEquals(2, s.audio.denoise)
        assertTrue(s.audio.enhance)
        assertTrue(s.audio.loud)
        assertTrue(s.cutSilences)
        assertEquals(1200L, s.minPauseMs)
        assertTrue(s.cutFillers)
        assertEquals(BgFill.BLUR, s.background.fill)
        assertEquals(2, s.stabilize)
        assertEquals(ExportAspect.VERTICAL, s.export.aspect)
        assertEquals(1080, s.export.shortSide)
        assertEquals(30, s.export.fps)
        assertEquals(ExportQuality.HIGH, s.export.quality)
        assertEquals(ExportCodec.H264, s.export.codec)
        assertEquals("ONIT", s.export.watermark.text)
        assertTrue(s.hasProcessing)
    }

    @Test
    fun excludedCategoriesChangeNothing() {
        val base = TemplateSettings(
            mode = BlurMode.SOLID, strength = 0.3f, padding = 1.3f, sensitive = true,
            createSubs = true, translate = true, subStyle = SubtitleStyle(size = 2f),
            audio = AudioEdit(voice = VoiceFx.ROBOT, denoise = 1),
            cutSilences = true, background = Background(fill = BgFill.COLOR), stabilize = 1,
            export = ExportOptions(shortSide = 720, fps = 24, aspect = ExportAspect.SQUARE),
        )
        // Nothing included: nothing changes.
        assertEquals(base, full.only(emptySet()).applyTo(base))
        // Only the export included: the rest stays.
        val s = full.only(setOf(TemplateCategory.EXPORT)).applyTo(base)
        assertEquals(base.copy(export = s.export), s)
        assertEquals(ExportAspect.VERTICAL, s.export.aspect)
        assertEquals(BlurMode.SOLID, s.mode)
        assertEquals(VoiceFx.ROBOT, s.audio.voice)
        assertEquals(2f, s.subStyle.size, 0f)
        // Without the subtitles category the subtitle choices (and the burned-in language) stay.
        val noSubs = full.only(TemplateCategory.entries.toSet() - TemplateCategory.SUBTITLES).applyTo(base)
        assertTrue(noSubs.createSubs)
        assertEquals(2f, noSubs.subStyle.size, 0f)
        assertEquals(base.export.subs, noSubs.export.subs)
        assertEquals(BlurMode.PIXELATE, noSubs.mode)
    }

    @Test
    fun aLookChangesOnlyTheLook() {
        val base = TemplateSettings(createSubs = true, export = ExportOptions(shortSide = 720), padding = 1.4f, sensitive = true)
        for (look in BuiltInTemplates.LOOKS) {
            val s = look.applyTo(base)
            assertEquals(look.subs!!.style, s.subStyle)
            assertEquals(look.blur!!.mode, s.mode)
            assertEquals(1.4f, s.padding, 0f)
            assertTrue(s.sensitive)
            assertTrue(s.createSubs)
            assertEquals(720, s.export.shortSide)
            assertEquals(base.audio, s.audio)
        }
        assertEquals(SubEffect.HIGHLIGHT, BuiltInTemplates.BOLD.subs!!.style!!.effect)
        assertTrue(BuiltInTemplates.NEWS.subs!!.style!!.box)
        assertEquals(SplitRule(0, 32), BuiltInTemplates.MINIMAL.applyTo(base).split)
    }

    @Test
    fun everyBuiltInLookCreatesSubtitlesInTheHebrewModel() {
        // The batch added no subtitles because a look only styled them: now applying one turns creation on.
        for (look in BuiltInTemplates.LOOKS) {
            val s = look.applyTo(TemplateSettings())
            assertTrue(look.id, s.createSubs)
            assertTrue(look.id, s.needsSpeechModel && s.hasProcessing)
            assertEquals(look.id, SpeechModel.HEBREW, s.speechModel)
            assertEquals(look.id, SpeechModel.HEBREW.language, s.language)
            assertEquals(look.id, SubLang.ORIGINAL, s.export.subs)
        }
    }

    @Test
    fun aLookBurnsSubtitlesEvenIfTheUsersExportHadThemOff() {
        val base = TemplateSettings(export = ExportOptions(subs = SubLang.NONE))
        assertEquals(SubLang.ORIGINAL, BuiltInTemplates.BOLD.applyTo(base).export.subs)
    }

    @Test
    fun aLookChangesNeitherTheFrameShapeNorTheSize() {
        val base = TemplateSettings(export = ExportOptions(shortSide = 720, fps = 24, aspect = ExportAspect.SQUARE, quality = ExportQuality.SAVER))
        for (look in BuiltInTemplates.LOOKS) assertEquals(base.export, look.applyTo(base).export.copy(subs = base.export.subs))
    }

    @Test
    fun noBuiltInIsAPlatform() {
        assertEquals(setOf("look.clean", "look.bold", "look.news", "look.minimal"), BuiltInTemplates.all.map { it.id }.toSet())
        for (t in BuiltInTemplates.all) {
            assertEquals(TemplateGroup.LOOK, t.group)
            assertNull(t.export)
        }
    }

    @Test
    fun subtitleCreationCanBeTurnedOffAfterwards() {
        val s = BuiltInTemplates.CLEAN.applyTo(TemplateSettings()).copy(createSubs = false)
        assertFalse(s.hasProcessing)
        assertFalse(s.needsSpeechModel)
    }

    @Test
    fun anOldTemplateWithAPlatformStillLoadsAndKeepsItsExportSettings() {
        val old = JSONObject(
            """{"v":1,"id":"u9","name":"Old TikTok","group":"DESTINATION",
               "export":{"aspect":"VERTICAL","shortSide":1080,"fps":30,"quality":"HIGH","codec":"SAME","platform":"TIKTOK"}}""",
        )
        val t = Template.fromJson(old)!!
        assertEquals(TemplateGroup.USER, t.group)
        assertEquals(ExportPart(ExportAspect.VERTICAL, 1080, 30, ExportQuality.HIGH, ExportCodec.SAME), t.export)
        // Saved again, the platform is gone.
        assertFalse(t.toJson().getJSONObject("export").has("platform"))
        val s = t.applyTo(TemplateSettings())
        assertEquals(ExportAspect.VERTICAL, s.export.aspect)
        assertEquals(1080, s.export.shortSide)
    }

    @Test
    fun anOldProjectExportWithAPlatformStillReads() {
        val o = JSONObject(ExportOptions(shortSide = 720).toJson().toString()).put("platform", "REELS")
        assertEquals(720, ExportOptions.fromJson(o).shortSide)
    }

    @Test
    fun aChoiceIsOneTemplate() {
        assertTrue(TemplateChoice().isEmpty)
        assertEquals(emptyList<String>(), TemplateChoice().ids)
        assertEquals(listOf("look.bold"), TemplateChoice("look.bold").ids)
    }

    @Test
    fun settingsMakeATemplateWithTheKeptCategories() {
        val s = full.applyTo(TemplateSettings())
        val t = s.toTemplate("n", "From editor", setOf(TemplateCategory.BLUR, TemplateCategory.CUTS))
        assertEquals(setOf(TemplateCategory.BLUR, TemplateCategory.CUTS), t.categories)
        assertEquals(TemplateGroup.USER, t.group)
        assertFalse(t.builtIn)
        // Saved and applied to fresh settings, it gives the same blur and cuts.
        val again = roundTrip(t).applyTo(TemplateSettings())
        assertEquals(BlurMode.PIXELATE, again.mode)
        assertTrue(again.cutSilences && again.cutFillers)
        assertEquals(1200L, again.minPauseMs)
        assertFalse(again.createSubs)
        // The whole editor state, kept in full, comes back as it was.
        val all = s.toTemplate("a", "all", TemplateCategory.entries.toSet())
        assertEquals(s.copy(audio = s.audio.copy(gainDb = 0f)), roundTrip(all).applyTo(TemplateSettings()))
    }

    @Test
    fun estimateGrowsWithWhatIsDone() {
        val speeds = BatchMath.Speeds(analysisRatio = 1f, renderRatio = 0.8f, subsSpeed = 0.6f)
        val plain = BatchMath.videoMs(60_000, TemplateSettings(), speeds)
        val withSubs = BatchMath.videoMs(60_000, TemplateSettings(createSubs = true), speeds)
        assertTrue(withSubs > plain + 30_000)
        // 60 s analysis + 48 s render + overhead
        assertEquals(112_000.0, plain.toDouble(), 50.0)
        assertEquals(plain + withSubs, BatchMath.totalMs(listOf(60_000, 60_000), TemplateSettings(), speeds) + (withSubs - plain))
        // No face blur: no analysis pass to wait for.
        assertTrue(BatchMath.videoMs(60_000, TemplateSettings(blurOff = true), speeds) < plain)
    }

    @Test
    fun lowConfidenceTracksAreCounted() {
        fun track(vararg frames: Int) = TreeMap<Int, Box>().apply { for (f in frames) put(f, Box(0.5f, 0.5f, 0.2f, 0.3f)) }
        val clear = mapOf(1 to listOf(Det(Box(0.5f, 0.5f, 0.2f, 0.3f), 0.95f)))
        val weak = mapOf(1 to listOf(Det(Box(0.5f, 0.5f, 0.2f, 0.3f), 0.5f)))
        val verified = mapOf(1 to listOf(Det(Box(0.5f, 0.5f, 0.2f, 0.3f), 0.5f, verify = 0.9f)))
        val elsewhere = mapOf(1 to listOf(Det(Box(0.1f, 0.1f, 0.1f, 0.1f), 0.99f)))
        assertEquals(0, BatchMath.lowConfidenceTracks(listOf(track(1, 2)), clear))
        assertEquals(1, BatchMath.lowConfidenceTracks(listOf(track(1, 2)), weak))
        assertEquals(0, BatchMath.lowConfidenceTracks(listOf(track(1, 2)), verified))
        assertEquals(1, BatchMath.lowConfidenceTracks(listOf(track(1, 2)), elsewhere))
        assertEquals(1, BatchMath.lowConfidenceTracks(listOf(track(1), track(2)), clear))
        assertEquals(0, BatchMath.lowConfidenceTracks(emptyList(), emptyMap()))
    }
}
