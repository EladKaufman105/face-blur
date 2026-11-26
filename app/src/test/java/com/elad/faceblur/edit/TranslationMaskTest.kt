package com.elad.faceblur.edit

import com.elad.faceblur.project.ProjectStore
import com.elad.faceblur.subs.Srt
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A translation never shows a bleeped word: it is made from the masked text, and hidden when it was not. */
class TranslationMaskTest {
    @get:Rule val tmp = TemporaryFolder()

    private val three = listOf(TimedWord(0, 1000), TimedWord(1000, 2000), TimedWord(2000, 3000))

    /** A model with one Hebrew subtitle "שלום עולם יפה" and, if [bleep], its middle word bleeped. */
    private fun model(bleep: Boolean, en: String = "Hello beautiful world", arabic: Boolean = false): EditModel {
        val n = 10
        val m = EditModel(LongArray(n) { it * 33_333L }, IntArray(0), FloatArray(n * 2), 16f / 9f, emptyMap(), 30f, emptyList())
        if (arabic) m.setSentences(listOf(NewCue(0, 3000, "مرحبا بالعالم الجميل", en, "ar", three)))
        else m.setSentences(listOf(NewCue(0, 3000, "שלום עולם יפה", en, "he", three)))
        if (bleep) m.toggleBleep(m.cues[0].id, 1)
        return m
    }

    @Test
    fun withNothingMaskedAnyTranslationShows() {
        val m = model(bleep = false)
        assertTrue(m.translationShown(m.cues[0], true))
        assertEquals("Hello beautiful world", m.exportCues(true)[0].en)
        assertTrue(m.translationJobs(true).isEmpty())
    }

    @Test
    fun anOldOrManualTranslationOverABleepedWordIsHiddenButKept() {
        val m = model(bleep = true)
        val c = m.cues[0]
        assertNull(c.trm)
        assertFalse(m.translationShown(c, true))
        // Exported (video and .srt): the spoken text masked, no translation.
        val e = m.exportCues(true)[0]
        assertEquals("שלום **** יפה", e.he)
        assertEquals("", e.en)
        assertEquals("", Srt.format(listOf(e), english = true))
        assertTrue(Srt.format(listOf(e), english = false).contains("שלום **** יפה"))
        // The preview: the same, in every mode.
        for (lang in listOf(SubLang.TRANSLATED, SubLang.BOTH, SubLang.ORIGINAL)) {
            assertEquals(listOf("שלום **** יפה"), c.blocks(lang, m.bleepedWords(c), translation = m.translationShown(c, true)))
        }
        // Nothing is deleted, and with the masks off (or the bleep gone) it shows again.
        assertEquals("Hello beautiful world", c.en)
        assertEquals("Hello beautiful world", m.exportCues(false)[0].en)
        assertTrue(m.translationShown(c, false))
        m.toggleBleep(c.id, 1)
        assertTrue(m.translationShown(c, true))
    }

    @Test
    fun bothModeShowsTheTranslationOnlyWhenItIsShown() {
        val m = model(bleep = false)
        assertEquals(listOf("שלום עולם יפה", "Hello beautiful world"), m.exportCues(true)[0].blocks(SubLang.BOTH, emptySet(), translation = true))
        val b = model(bleep = true)
        assertEquals(listOf("שלום **** יפה"), b.exportCues(true)[0].blocks(SubLang.BOTH, emptySet(), translation = true))
    }

    @Test
    fun translatingUsesTheMaskedTextAndThenTheTranslationShows() {
        val m = model(bleep = true)
        val jobs = m.translationJobs(true)
        assertEquals(1, jobs.size)
        // The word never reaches the translator, and a stale translation is translated again.
        assertEquals("שלום **** יפה" to "", jobs[0].pair)
        assertEquals("he", jobs[0].src)
        assertEquals("שלום **** יפה", jobs[0].source)
        m.fillGroup(jobs[0].group, "Hello **** beautiful", intoEnglish = true, source = jobs[0].source)
        val c = m.cues[0]
        assertTrue(m.translationShown(c, true))
        assertEquals("Hello **** beautiful", m.exportCues(true)[0].en)
        assertTrue(m.translationJobs(true).isEmpty())
    }

    @Test
    fun editingOrBleepingMoreAfterTranslatingHidesItAgain() {
        val m = model(bleep = true)
        val j = m.translationJobs(true)[0]
        m.fillGroup(j.group, "Hello **** beautiful", true, j.source)
        val c = m.cues[0]
        // Another word bleeped.
        m.toggleBleep(c.id, 2)
        assertFalse(m.translationShown(c, true))
        assertEquals(1, m.translationJobs(true).size)
        m.toggleBleep(c.id, 2)
        assertTrue(m.translationShown(c, true))
        // The spoken text edited.
        m.editCue(c.id, "שלום עולם נחמד", c.en)
        assertNull(c.trm)
        assertFalse(m.translationShown(c, true))
    }

    @Test
    fun aTranslationTypedByHandIsHiddenOverMaskedWords() {
        val m = model(bleep = true)
        val j = m.translationJobs(true)[0]
        m.fillGroup(j.group, "Hello **** beautiful", true, j.source)
        val c = m.cues[0]
        m.setCueTexts(c.id, c.srcText, "Hello world")
        assertNull(c.trm)
        assertFalse(m.translationShown(c, true))
        // Typing the spoken text only keeps the translation's source.
        val m2 = model(bleep = true)
        val j2 = m2.translationJobs(true)[0]
        m2.fillGroup(j2.group, "Hello **** beautiful", true, j2.source)
        val c2 = m2.cues[0]
        m2.setCueTexts(c2.id, c2.srcText, c2.otherText)
        assertEquals("שלום **** יפה", c2.trm)
    }

    @Test
    fun arabicSentencesAreMaskedBeforeTranslating() {
        val m = model(bleep = true, arabic = true)
        val j = m.translationJobs(true)[0]
        assertEquals("مرحبا **** الجميل" to "", j.pair)
        assertEquals("ar", j.src)
        assertEquals("", m.exportCues(true)[0].en)
    }

    @Test
    fun aSentenceWithNoTranslationYetIsTranslatedFromItsMaskedText() {
        val m = model(bleep = true, en = "")
        val j = m.translationJobs(true)
        assertEquals(1, j.size)
        assertEquals("שלום **** יפה", j[0].source)
    }

    @Test
    fun splitAndMergeKeepTheSource() {
        val m = model(bleep = true)
        val j = m.translationJobs(true)[0]
        m.fillGroup(j.group, "Hello **** beautiful", true, j.source)
        var id = 100
        val parts = Subtitles.splitAt(m.cues[0], intArrayOf(0, 1, 3), m.cues[0].group) { id++ }
        assertTrue(parts.all { it.trm == "שלום **** יפה" })
        assertEquals("שלום **** יפה", Subtitles.merge(parts).trm)
    }

    private fun state(trm: String?) = EditState(
        emptyList(), listOf(Cue(1, 0, 3000, "שלום עולם יפה", "Hello", 1, "he", null, 0, trm)), emptyList(), 1, 2, emptyList(), emptyList(),
    )

    @Test
    fun theSourceIsSavedAndAnOldProjectHasNone() {
        val f = File(tmp.root, "edits.bin")
        ProjectStore.writeEdits(f, state("שלום **** יפה"))
        assertEquals("שלום **** יפה", ProjectStore.readEdits(f).cues[0].trm)

        // What version 4 wrote: its number, and no list of translation sources at the end.
        ProjectStore.writeEdits(f, state(null))
        val raw = GZIPInputStream(f.inputStream()).use { it.readBytes() }
        raw[3] = 4
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw.copyOf(raw.size - 4)) }
        f.writeBytes(out.toByteArray())
        val old = ProjectStore.readEdits(f)
        assertNull(old.cues[0].trm)
        assertEquals("Hello", old.cues[0].en)
    }
}
