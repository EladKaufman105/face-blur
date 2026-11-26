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

    @Test
    fun foundFacesKeepTheSubtitlesBleepsSoundsCutsAndTheBlursPlacedByHand() {
        val old = model(0)
        old.setSentences(listOf(NewCue(1000, 2000, "", "kept", "en", null)))
        old.bleeps[1200L] = 1400L
        old.addSfx("builtin:whoosh", 500, 300)
        old.removed[3000L] = 3500L
        val hand = old.add(5, 0.3f, 0.3f, 0.1f, 0.1f)
        val v = old.version
        val fresh = model(2)
        val m = EditModel.withDetection(old, fresh)
        assertEquals(listOf("kept"), m.cues.map { it.en })
        assertEquals(1, m.bleeps.size)
        assertEquals(1, m.sfx.size)
        assertEquals(3500L, m.removed[3000L])
        // The two found faces, then the one placed by hand with a number of its own.
        assertEquals(3, m.tracks.size)
        assertEquals(3, m.tracks.map { it.id }.toSet().size)
        assertTrue(m.tracks.last().manual)
        assertEquals(old.track(hand)!!.start, m.tracks.last().start)
        assertTrue(m.tracks.dropLast(1).none { it.manual })
        // The number a watcher sees always goes up across the swap, even when both models are new.
        assertTrue(m.version > v)
        assertFalse(m.canUndo)
    }

    @Test
    fun aHandPlacedBlurFollowsItsMomentWhenTheFramesDiffer() {
        val from = LongArray(10) { it * 100_000L }
        val to = LongArray(20) { it * 50_000L }
        val t = EditTrack(4, true, path(2, 4), 2, 4)
        val r = EditModel.remapTrack(t, from, to, 9)
        assertEquals(9, r.id)
        assertEquals(listOf(4, 8), r.path.keys.toList())
        assertEquals(4, r.start)
        assertEquals(8, r.end)
    }
}
