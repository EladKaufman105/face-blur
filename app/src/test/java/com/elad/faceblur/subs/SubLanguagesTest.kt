package com.elad.faceblur.subs

import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubLanguagesTest {
    @Test
    fun speechCodeForcesAKnownLanguageAndOtherwiseDetects() {
        assertEquals("ar", SubLanguages.speechCode("ar"))
        assertEquals("fa", SubLanguages.speechCode("fa"))
        assertEquals("he", SubLanguages.speechCode("he"))
        assertEquals("en", SubLanguages.speechCode("en"))
        assertEquals("auto", SubLanguages.speechCode("auto"))
        assertEquals("auto", SubLanguages.speechCode(null))
        assertEquals("auto", SubLanguages.speechCode("xx"))
    }

    @Test
    fun speechChoicesAreInTheWebsOrder() {
        assertEquals(listOf("auto", "he", "ar", "en", "fa"), SubLanguages.SPEECH)
        assertEquals(setOf("he", "en", "ar", "fa"), SubLanguages.TRANSLATION.toSet())
    }

    @Test
    fun mlKitCodesExistForTheFourLanguagesOnly() {
        for (l in listOf("he", "en", "ar", "fa")) assertEquals(l, SubLanguages.mlKitTag(l))
        assertNull(SubLanguages.mlKitTag("auto"))
        assertNull(SubLanguages.mlKitTag(""))
    }

    @Test
    fun targetIsEnglishExceptForEnglishSpeechAndAPick() {
        assertEquals("en", SubLanguages.target("he", null))
        assertEquals("en", SubLanguages.target("ar", null))
        assertEquals("en", SubLanguages.target("fa", null))
        assertEquals("he", SubLanguages.target("en", null))
        assertEquals("ar", SubLanguages.target("he", "ar"))
        assertEquals("fa", SubLanguages.target("en", "fa"))
        assertEquals("he", SubLanguages.target("ar", "he"))
        // A pick equal to the source falls back to the automatic target; a bad pick is ignored.
        assertEquals("en", SubLanguages.target("ar", "ar"))
        assertEquals("he", SubLanguages.target("en", "en"))
        assertEquals("en", SubLanguages.target("fa", "xx"))
    }

    @Test
    fun scriptLangReadsTheLetters() {
        assertEquals("he", SubLanguages.scriptLang("שלום עולם"))
        assertEquals("ar", SubLanguages.scriptLang("مرحبا بالعالم"))
        // پ چ ژ گ ک ی only Persian has.
        assertEquals("fa", SubLanguages.scriptLang("سلام چطوری"))
        assertEquals("fa", SubLanguages.scriptLang("گربه"))
        assertEquals("en", SubLanguages.scriptLang("Hello"))
        assertEquals("", SubLanguages.scriptLang("123 ..."))
        // The script with most of the letters wins.
        assertEquals("ar", SubLanguages.scriptLang("مرحبا بك iPhone"))
        assertEquals("en", SubLanguages.scriptLang("Hello world مرحبا"))
    }

    @Test
    fun directionComesFromTheFirstLetter() {
        assertTrue(SubLanguages.isRtlText("  مرحبا"))
        assertTrue(SubLanguages.isRtlText("\"שלום\""))
        assertFalse(SubLanguages.isRtlText("Hello مرحبا"))
        assertFalse(SubLanguages.isRtlText("123"))
        assertTrue(SubLanguages.isRtl("ar") && SubLanguages.isRtl("fa") && SubLanguages.isRtl("he"))
        assertFalse(SubLanguages.isRtl("en"))
    }

    @Test
    fun onlyRealArabicLettersCount() {
        // The BOM, non-characters, and symbols or digits of the Arabic blocks are not letters.
        assertEquals("", SubLanguages.scriptLang("\uFEFF"))
        assertEquals("", SubLanguages.scriptLang("\uFDD0\uFDEF"))
        assertEquals("", SubLanguages.scriptLang("\u0660\u0661\u06DD"))
        assertEquals("ar", SubLanguages.scriptLang("\uFEFB\uFEFC"))
        assertEquals("ar", SubLanguages.scriptLang("\uFEFFمرحبا"))
    }

    @Test
    fun arabicQuestionMarkEndsASentence() {
        assertTrue(SubLanguages.endsSentence("كيف؟"))
        assertTrue(SubLanguages.endsSentence("چطوری؟ "))
        assertTrue(SubLanguages.endsSentence("end."))
        assertFalse(SubLanguages.endsSentence("مرحبا،"))
    }
}

class ArabicSubtitlesTest {
    private fun w(start: Long, end: Long, text: String) = Triple(start, end, text)

    @Test
    fun lineLanguageKeepsArabicAndPersianApart() {
        assertEquals("ar", SubtitleEngine.lineLanguage("مرحبا بالعالم", "ar"))
        // The model's own language wins for a loan word with a Persian-only letter.
        assertEquals("ar", SubtitleEngine.lineLanguage("نحن نأكل پيتزا", "ar"))
        assertEquals("fa", SubtitleEngine.lineLanguage("سلام چطوری", "fa"))
        assertEquals("ar", SubtitleEngine.lineLanguage("مرحبا", "auto"))
        assertEquals("he", SubtitleEngine.lineLanguage("שלום world עולם", "he"))
        assertEquals("en", SubtitleEngine.lineLanguage("Hello עולם", "he"))
        // Latin letters in speech that is in another language are names, not English speech.
        // Arabic letters in a language that is neither Arabic nor Persian (Urdu, Pashto, Kurdish): not translated as one.
        assertEquals("", SubtitleEngine.lineLanguage("یہ اردو ہے", "ur"))
        assertEquals("", SubtitleEngine.lineLanguage("مرحبا", "ps"))
        assertEquals("ar", SubtitleEngine.lineLanguage("مرحبا", ""))
        assertEquals("", SubtitleEngine.lineLanguage("iPhone", "ar"))
        assertEquals("", SubtitleEngine.lineLanguage("...", "ar"))
    }

    @Test
    fun sentencesBreakAfterAnArabicQuestionMarkAndAPause() {
        val words = listOf(
            w(0, 400, "كيف"), w(400, 800, "حالك؟"), w(900, 1300, "أنا"), w(1300, 1700, "بخير"),
            w(3000, 3400, "شكرا"),
        )
        val s = SubtitleEngine.sentences(words)
        assertEquals(listOf("كيف حالك؟", "أنا بخير", "شكرا"), s.map { it.text })
        assertEquals(2, s[0].words.size)
        // At least 0.8 s on screen, none over the next one.
        assertTrue(s[0].endMs - s[0].startMs >= 800)
        assertTrue(s[0].endMs <= s[1].startMs)
    }

    @Test
    fun sentencesBreakBeforePassing64Characters() {
        val words = (0 until 20).map { w(it * 300L, it * 300L + 250, "كلمات") }
        val s = SubtitleEngine.sentences(words)
        assertTrue(s.size > 1)
        assertTrue(s.all { it.text.length <= 64 })
        assertEquals(20, s.sumOf { it.words.size })
    }

    @Test
    fun splitByCharactersWorksOnArabicWords() {
        val ws = Subtitles.words("مرحبا بكم في برنامجنا اليوم")
        val b = Subtitles.bounds(ws, SplitRule(0, 12))
        assertEquals(0, b.first())
        assertEquals(ws.size, b.last())
        for (i in 0 until b.size - 1) {
            val line = ws.subList(b[i], b[i + 1]).joinToString(" ")
            // A line is over the limit only if it is one word.
            assertTrue(line.length <= 12 || b[i + 1] - b[i] == 1)
        }
    }

    @Test
    fun maskHidesTheWholeWordWithItsDiacritics() {
        assertEquals("قال ****", Subtitles.mask("قال كَلْبَ", setOf(1)))
        assertEquals("«****»،", Subtitles.mask("«كَتَبَ»،", setOf(0)))
        assertEquals("**** שלום", Subtitles.mask("שָׁלוֹם שלום", setOf(0)))
        assertEquals("a **** c", Subtitles.mask("a b c", setOf(1)))
    }

    @Test
    fun arabicSpeechIsTheHeSlotWithItsTranslationInTheOtherOne() {
        val c = Cue(1, 0, 2000, "مرحبا بالعالم", "Hello world", 1, "ar")
        assertEquals("مرحبا بالعالم", c.srcText)
        assertEquals("Hello world", c.otherText)
        // The translation of a Persian line into Hebrew sits in the other slot too.
        val f = Cue(2, 0, 2000, "سلام", "שלום", 2, "fa")
        assertEquals("سلام", f.srcText)
        assertEquals("שלום", f.otherText)
        // Emptying the spoken text leaves it empty, not the translation.
        f.he = ""
        assertEquals("", f.srcText)
    }

    @Test
    fun translationItemsGetTheirLanguagesAndTargets() {
        val pairs = listOf(
            "مرحبا" to "",
            "" to "Hello",
            "سلام" to "",
            "שלום" to "",
            "done" to "גמור",
            "" to "",
        )
        val srcs = listOf("ar", "en", "fa", "", "he", "")
        val items = SubtitleEngine.items(pairs, srcs, null)
        assertEquals("ar", items[0]!!.from); assertEquals("en", items[0]!!.to); assertEquals("مرحبا", items[0]!!.text)
        assertEquals("en", items[1]!!.from); assertEquals("he", items[1]!!.to); assertEquals("Hello", items[1]!!.text)
        assertEquals("fa", items[2]!!.from); assertEquals("en", items[2]!!.to)
        // No language on the line: read from the letters.
        assertEquals("he", items[3]!!.from)
        assertNull(items[4])
        assertNull(items[5])
        // Every direction between the four languages.
        val pick = SubtitleEngine.items(pairs, srcs, "fa")
        assertEquals("fa", pick[0]!!.to)
        assertEquals("fa", pick[1]!!.to)
        // A pick equal to the source: the automatic target.
        assertEquals("en", pick[2]!!.to)
        assertEquals("fa", pick[3]!!.to)
    }

    @Test
    fun importedArabicAndPersianSrtIsSpokenInThatLanguage() {
        val cues = Srt.toNewCues(Srt.parse("1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n\n2\n00:00:03,000 --> 00:00:04,000\nسلام چطوری\n"))
        assertEquals("ar", cues[0].src)
        assertEquals("مرحبا", cues[0].he)
        assertEquals("", cues[0].en)
        assertEquals("fa", cues[1].src)
        assertEquals("سلام چطوری", cues[1].he)
    }

    @Test
    fun srtFilesAreNamedByTheirLanguage() {
        val cues = listOf(Cue(1, 0, 1000, "مرحبا", "Hello", 1, "ar"))
        assertEquals("ar", Srt.tag(cues, english = false))
        assertEquals("en", Srt.tag(cues, english = true))
        assertEquals("he", Srt.tag(emptyList(), english = false))
        val fa = listOf(Cue(1, 0, 1000, "سلام چطوری", "שלום", 1, "fa"))
        assertEquals("fa", Srt.tag(fa, english = false))
        assertEquals("he", Srt.tag(fa, english = true))
    }

    @Test
    fun srtFilesAreNamedByTheMajorityScriptAndNeverRepeated() {
        // The first line is English, most are Arabic; a line with no letters is skipped.
        val mixed = listOf(
            Cue(1, 0, 1000, "Hello", "", 1, "he"), Cue(2, 1000, 2000, "مرحبا", "", 2, "ar"),
            Cue(3, 2000, 3000, "123", "", 3, "he"), Cue(4, 3000, 4000, "شكرا", "", 4, "ar"),
        )
        assertEquals("ar", Srt.tag(mixed, english = false))
        // Both slots the same text: one file.
        val same = listOf(Cue(1, 0, 1000, "Hello", "Hello", 1, ""))
        assertEquals(1, Srt.files(same).size)
        // Two files of the same language: distinct names.
        val twice = listOf(Cue(1, 0, 1000, "Hi", "Hello", 1, ""))
        assertEquals(listOf("en", "en2"), Srt.files(twice).map { it.tag })
        // An Arabic text and its English translation.
        val pair = listOf(Cue(1, 0, 1000, "مرحبا", "Hello", 1, "ar"))
        assertEquals(listOf("ar", "en"), Srt.files(pair).map { it.tag })
        assertTrue(Srt.files(emptyList()).isEmpty())
    }
}
