package com.elad.faceblur.pipeline

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4PrivacyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun box(type: String, body: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply { writeInt(8 + body.size); write(type.toByteArray(Charsets.ISO_8859_1)); write(body) }
        return b.toByteArray()
    }

    @Test
    fun locationAndMetaBoxesAreHidden() {
        val moov = box("moov", box("udta", box("©xyz", ByteArray(6))) + box("meta", ByteArray(4)))
        val f = tmp.newFile("a.mp4").apply { writeBytes(box("ftyp", ByteArray(4)) + moov) }
        assertEquals(2, Mp4Privacy.scrubOrFail(f))
        val text = String(f.readBytes(), Charsets.ISO_8859_1)
        assertTrue(!text.contains("©xyz") && !text.contains("meta"))
    }

    @Test
    fun aFileThatCannotBeScrubbedFailsInsteadOfPassing() {
        // A folder cannot be opened as a file: the scrub fails, and the caller must not save anything.
        try {
            Mp4Privacy.scrubOrFail(tmp.newFolder("not-a-file"))
            fail("the scrub should have failed")
        } catch (e: ScrubFailedException) {
            // expected
        }
    }
}
