package com.elad.faceblur.project

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.EditState
import com.elad.faceblur.edit.EditTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditsRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: Int, off: Boolean) =
        EditTrack(id, id == 2, TreeMap(mapOf(3 to Box(0.5f, 0.5f, 0.1f, 0.1f))), 3, 9, 1.5f, TreeMap(), off)

    private fun state(vararg off: Boolean) = EditState(
        off.mapIndexed { i, o -> track(i + 1, o) },
        listOf(Cue(1, 100, 900, "שלום", "")), listOf(longArrayOf(200, 300)), 10, 5, emptyList(), listOf(longArrayOf(500, 600)),
    )

    @Test
    fun offBlursAreSavedAndReadBack() {
        val f = File(tmp.root, "edits.bin")
        ProjectStore.writeEdits(f, state(true, false, true))
        val back = ProjectStore.readEdits(f)
        assertEquals(listOf(true, false, true), back.tracks.map { it.off })
        assertEquals(1.5f, back.tracks[0].scale)
        assertEquals("שלום", back.cues[0].he)
        assertEquals(200L, back.bleeps[0][0])
        assertEquals(600L, back.removed[0][1])
    }

    @Test
    fun editedBlursAreSavedAndReadBack() {
        val f = File(tmp.root, "edits.bin")
        val st = state(false, false, false)
        st.tracks[1].edited = true
        ProjectStore.writeEdits(f, st)
        assertEquals(listOf(false, true, false), ProjectStore.readEdits(f).tracks.map { it.edited })
    }

    @Test
    fun aFileWithoutTheEditedListReadsAsNoBlurEdited() {
        val f = File(tmp.root, "edits.bin")
        ProjectStore.writeEdits(f, state(false, false))
        // Made into what an app from before the list wrote: the same version 5, without the trailing list (its count, 4 bytes, as none were edited).
        val raw = GZIPInputStream(f.inputStream()).use { it.readBytes() }
        assertEquals(5, raw[3].toInt())
        val old = raw.copyOf(raw.size - 4)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(old) }
        f.writeBytes(out.toByteArray())
        val back = ProjectStore.readEdits(f)
        assertEquals(2, back.tracks.size)
        assertTrue(back.tracks.none { it.edited })
    }

    @Test
    fun theEditedListIsTrailingDataANewAppWritesAtTheEndOfVersion5() {
        val f = File(tmp.root, "edits.bin")
        val st = state(false, false)
        st.tracks[0].edited = true
        ProjectStore.writeEdits(f, st)
        val raw = GZIPInputStream(f.inputStream()).use { it.readBytes() }
        assertEquals(5, raw[3].toInt())
        // One id (4 bytes) and its count (4 bytes) at the end: the part an older reader never reaches.
        val tail = java.nio.ByteBuffer.wrap(raw, raw.size - 8, 8)
        assertEquals(1, tail.int)
        assertEquals(st.tracks[0].id, tail.int)
        assertEquals(listOf(true, false), ProjectStore.readEdits(f).tracks.map { it.edited })
    }

    @Test
    fun aVersion3FileHasNoOffBlurs() {
        val f = File(tmp.root, "edits.bin")
        ProjectStore.writeEdits(f, state(false, false))
        // Made into what version 3 wrote: the version number 3, and no list of turned-off blurs at the end.
        val raw = GZIPInputStream(f.inputStream()).use { it.readBytes() }
        raw[3] = 3
        val v3 = raw.copyOf(raw.size - 4)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(v3) }
        f.writeBytes(out.toByteArray())
        val back = ProjectStore.readEdits(f)
        assertEquals(2, back.tracks.size)
        assertTrue(back.tracks.none { it.off })
        assertFalse(back.tracks.any { it.off })
    }
}
