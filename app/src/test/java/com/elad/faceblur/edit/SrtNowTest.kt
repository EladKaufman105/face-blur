package com.elad.faceblur.edit

import com.elad.faceblur.subs.Srt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Save SRT now" saves the files the export would: the same hidden words, translations and cut times. */
class SrtNowTest {
    private val three = listOf(TimedWord(0, 1000), TimedWord(1000, 2000), TimedWord(2000, 3000))

    /** A Hebrew subtitle "שלום עולם יפה" (its middle word bleeped if [bleep]) and an English one at 4-6 s. */
    private fun model(bleep: Boolean): EditModel {
        val n = 10
        val m = EditModel(LongArray(n) { it * 33_333L }, IntArray(0), FloatArray(n * 2), 16f / 9f, emptyMap(), 30f, emptyList())
        m.setSentences(
            listOf(
                NewCue(0, 3000, "שלום עולם יפה", "", "he", three),
                NewCue(4000, 6000, "", "Hello there", "en", null),
            ),
        )
        if (bleep) m.toggleBleep(m.cues.first { it.src == "he" }.id, 1)
        return m
    }

    @Test
    fun oneFilePerLanguageNamedByIt() {
        val files = Srt.files(model(bleep = false).srtCues(true))
        assertEquals(listOf("he", "en"), files.map { it.tag })
        assertTrue(files[0].text.contains("שלום עולם יפה"))
        assertTrue(files[1].text.contains("Hello there"))
    }

    @Test
    fun bleepedWordsAreHiddenAsInTheExport() {
        val m = model(bleep = true)
        val masked = Srt.files(m.srtCues(true)).first { it.tag == "he" }.text
        assertTrue(masked.contains("שלום **** יפה"))
        assertFalse(masked.contains("עולם"))
        // The same text as the export's subtitles.
        assertEquals(Srt.format(m.exportCues(true), false), masked)
        // With hiding switched off, the word is there (the app asks before saving such a file).
        assertTrue(Srt.files(m.srtCues(false)).first { it.tag == "he" }.text.contains("שלום עולם יפה"))
    }

    @Test
    fun theAutomaticFilesNeverHoldABleepedWordEvenWithTheMaskOff() {
        val m = model(bleep = true)
        val auto = Srt.files(m.automaticSrtCues()).first { it.tag == "he" }.text
        assertTrue(auto.contains("שלום **** יפה"))
        assertFalse(auto.contains("עולם"))
        assertTrue(m.automaticSrtDiffers(maskWords = false))
        assertFalse(m.automaticSrtDiffers(maskWords = true))
        assertFalse(model(bleep = false).automaticSrtDiffers(maskWords = false))
    }

    @Test
    fun timesFollowTheCutsAndACutOutSubtitleIsLeftOut() {
        val m = model(bleep = false)
        m.cut(3000, 4000)
        val moved = m.srtCues(true)
        assertEquals(2, moved.size)
        assertEquals(0L, moved[0].startMs)
        assertEquals(3000L, moved[0].endMs)
        assertEquals(3000L, moved[1].startMs)
        assertEquals(5000L, moved[1].endMs)
        // The model's own subtitles keep their times.
        assertEquals(4000L, m.cues.first { it.src == "en" }.startMs)

        val m2 = model(bleep = false)
        m2.cut(4000, 6000)
        assertEquals(listOf("he"), Srt.files(m2.srtCues(true)).map { it.tag })
    }

    @Test
    fun noCutsKeepsTheList() {
        val cues = model(bleep = false).exportCues(true)
        assertSame(cues, CutMap(emptyList()).mapCues(cues))
    }
}
