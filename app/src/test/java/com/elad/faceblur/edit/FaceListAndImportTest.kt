package com.elad.faceblur.edit

import com.elad.faceblur.analysis.Box
import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceListAndImportTest {
    private fun path(vararg frames: Int) = TreeMap<Int, Box>().apply { frames.forEach { put(it, Box(0.5f, 0.4f, 0.2f, 0.25f)) } }

    private fun model(tracks: Int = 2): EditModel {
        val n = 60
        return EditModel(
            LongArray(n) { it * 33_333L }, IntArray(0), FloatArray(n * 2), 16f / 9f, emptyMap(), 30f,
            List(tracks) { path(*(0 until n).toList().toIntArray()) },
        )
    }

    @Test
    fun aBlurTurnedOffIsNotShownNorExportedAndComesBackWithUndo() {
        val m = model()
        val id = m.tracks[0].id
        assertNotNull(m.boxAt(m.tracks[0], 10))
        m.toggleOff(id)
        assertTrue(m.track(id)!!.off)
        assertNull(m.boxAt(m.track(id)!!, 10))
        assertEquals(1, m.blursOn)
        assertEquals(1, m.renderTracks().size)
        assertEquals(1, m.shapesAt(10, 1.6f).size)
        m.toggleOff(id)
        assertEquals(2, m.renderTracks().size)
        assertTrue(m.undo())
        assertTrue(m.track(id)!!.off)
    }

    @Test
    fun allAndNoneAreOneUndoStepEach() {
        val m = model(3)
        m.setAllOff(true)
        assertEquals(0, m.blursOn)
        assertTrue(m.renderTracks().isEmpty())
        // Already all off: no new step.
        m.setAllOff(true)
        m.setAllOff(false)
        assertEquals(3, m.blursOn)
        assertTrue(m.undo())
        assertEquals(0, m.blursOn)
        assertTrue(m.undo())
        assertEquals(3, m.blursOn)
        assertFalse(m.undo())
    }

    @Test
    fun aTurnedOffStateSurvivesSplittingAndSavingTheState() {
        val m = model(1)
        val id = m.tracks[0].id
        m.toggleOff(id)
        val second = m.split(id, 30)!!
        assertTrue(m.track(second)!!.off)
        val saved = m.exportState()
        assertEquals(setOf(id, second), saved.tracks.filter { it.off }.map { it.id }.toSet())
    }

    @Test
    fun importedSubtitlesReplaceTheOldOnesAndBleepsInOneUndoStep() {
        val m = model(0)
        m.setSentences(listOf(NewCue(1000, 2000, "", "old one", "en", null)))
        m.bleeps[1200L] = 1400L
        m.importCues(listOf(NewCue(0, 500, "חדש", "", "he", null), NewCue(600, 900, "", "new", "en", null)))
        assertEquals(listOf("חדש", ""), m.cues.map { it.he })
        assertEquals(2, m.cues.size)
        assertTrue(m.bleeps.isEmpty())
        assertTrue(m.undo())
        assertEquals(listOf("old one"), m.cues.map { it.en })
        assertEquals(1, m.bleeps.size)
    }

    @Test
    fun thePictureOfAFaceIsTheMiddleOfItsFramesAndStaysInsideTheFrame() {
        val t = EditTrack(1, false, path(10, 20, 30, 40, 50), 10, 50)
        assertEquals(30, FaceThumbs.frameOf(t))
        val c = FaceThumbs.crop(Box(0.02f, 0.98f, 0.2f, 0.2f), 1000, 500)
        assertTrue(c[0] >= 0 && c[1] >= 0)
        assertTrue(c[0] + c[2] <= 1000 && c[1] + c[2] <= 500)
    }
}
