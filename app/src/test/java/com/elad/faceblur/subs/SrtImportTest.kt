package com.elad.faceblur.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtImportTest {
    @Test
    fun readsTimesAndText() {
        val list = Srt.parse("1\n00:00:01,500 --> 00:00:03,000\nשלום עולם\n\n2\n00:01:02,5 --> 01:00:00.250\nHello\nthere\n")
        assertEquals(2, list.size)
        assertEquals(1500L, list[0].startMs)
        assertEquals(3000L, list[0].endMs)
        assertEquals("שלום עולם", list[0].text)
        // A short fraction is milliseconds counted from the left: ",5" is 500 ms. Lines are joined with a space.
        assertEquals(62_500L, list[1].startMs)
        assertEquals(3_600_250L, list[1].endMs)
        assertEquals("Hello there", list[1].text)
    }

    @Test
    fun dropsTagsEmptyBlocksAndBackwardsTimes() {
        val list = Srt.parse(
            "1\n00:00:01,000 --> 00:00:02,000\n<i>Hi</i> <font color=\"red\">you</font>\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\n\n\n" +
                "3\n00:00:06,000 --> 00:00:05,000\nbackwards\n\n" +
                "just a note\n",
        )
        assertEquals(1, list.size)
        assertEquals("Hi you", list[0].text)
    }

    @Test
    fun sortsByStartAndAcceptsWindowsLineEndingsAndABom() {
        val list = Srt.parse("﻿2\r\n00:00:05,000 --> 00:00:06,000\r\nsecond\r\n\r\n1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n")
        assertEquals(listOf("first", "second"), list.map { it.text })
    }

    @Test
    fun nothingReadableIsAnEmptyList() {
        assertTrue(Srt.parse("").isEmpty())
        assertTrue(Srt.parse("not an srt file at all").isEmpty())
    }

    @Test
    fun hebrewTextIsSpokenHebrewAndOtherTextEnglish() {
        val cues = Srt.toNewCues(Srt.parse("1\n00:00:01,000 --> 00:00:02,000\nשלום\n\n2\n00:00:03,000 --> 00:00:04,000\nHello\n"))
        assertEquals("שלום", cues[0].he)
        assertEquals("he", cues[0].src)
        assertEquals("", cues[0].en)
        assertEquals("Hello", cues[1].en)
        assertEquals("en", cues[1].src)
        assertNull(cues[1].words)
    }
}

class SrtLimitsTest {
    @Test
    fun aTimeTooBigToBeATimeSkipsItsBlockInsteadOfCrashing() {
        val list = Srt.parse("1\n99999999999999999999:00:00,000 --> 99999999999999999999:00:01,000\nhuge\n\n2\n00:00:01,000 --> 00:00:02,000\nfine\n")
        assertEquals(listOf("fine"), list.map { it.text })
    }

    @Test
    fun aLongFileIsCutBeforeACharacterNotInTheMiddleOfIt() {
        // "שלום" is 2 bytes a letter: a cut after 3 bytes would split the second letter.
        val bytes = "שלום".toByteArray(Charsets.UTF_8)
        assertEquals("ש", Srt.cutUtf8(bytes, 3).toString(Charsets.UTF_8))
        assertEquals("של", Srt.cutUtf8(bytes, 4).toString(Charsets.UTF_8))
        assertEquals(bytes.size, Srt.cutUtf8(bytes, 8).size)
    }
}
