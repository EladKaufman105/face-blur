package com.elad.faceblur.edit

import com.elad.faceblur.analysis.Box
import com.elad.faceblur.analysis.DetectMode
import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** "Run detection again": the merge rules (the web's tests/choices.cjs checks the same ones). */
class RerunMergeTest {
    private val n = 120

    private fun path(first: Int, last: Int, cx: Float, cy: Float) =
        TreeMap<Int, Box>().apply { for (f in first..last) put(f, Box(cx, cy, 0.1f, 0.12f)) }

    private fun model(vararg autos: TreeMap<Int, Box>) = EditModel(
        LongArray(n) { it * 33_333L }, IntArray(0), FloatArray(n * 2), 16f / 9f, emptyMap(), 30f, autos.toList(),
    )

    private fun ids(m: EditModel) = m.tracks.map { it.id }

    @Test
    fun addKeepsEveryBlurAsItWasAndOnlyTakesFacesThatAreNotOneOfThem() {
        val m = model(path(0, 49, 0.2f, 0.3f), path(60, 79, 0.4f, 0.4f))
        val manualId = m.add(10, 0.7f, 0.3f, 0.1f, 0.12f)
        m.toggleOff(m.tracks[1].id)
        val before = m.tracks.map { it.id to (it.start to it.end) }
        // The same face again (a little moved), another face, the face of the turned-off blur, and one of the manual blur.
        val found = listOf(path(0, 49, 0.21f, 0.31f), path(0, 59, 0.9f, 0.1f), path(60, 79, 0.4f, 0.41f), path(10, 40, 0.7f, 0.3f))
        val (added, removed) = m.rerun(found, RerunMode.ADD)
        assertEquals(1 to 0, added to removed)
        assertEquals(before, m.tracks.take(before.size).map { it.id to (it.start to it.end) })
        assertEquals(before.size + 1, m.tracks.size)
        assertTrue(m.track(manualId)!!.manual)
        assertTrue(m.tracks[1].off)
        assertFalse(m.tracks.last().manual)
        assertEquals(m.tracks.size, ids(m).toSet().size)
    }

    @Test
    fun replaceTakesOutTheAutomaticBlursNobodyTouchedAndKeepsTheRest() {
        val m = model(path(0, 49, 0.2f, 0.3f), path(0, 49, 0.5f, 0.7f), path(60, 79, 0.4f, 0.4f), path(5, 30, 0.85f, 0.85f), path(70, 100, 0.1f, 0.1f))
        val untouched = m.tracks[0].id
        val moved = m.tracks[1].id
        val scaled = m.tracks[2].id
        val off = m.tracks[3].id
        val trimmed = m.tracks[4].id
        val manual = m.add(10, 0.7f, 0.3f, 0.1f, 0.12f)
        m.beginGesture(); m.moveBy(moved, 20, 0.01f, 0f)
        m.beginGesture(); m.setScale(scaled, 1.4f)
        m.toggleOff(off)
        m.setEnd(trimmed, 90)
        val found = listOf(path(0, 49, 0.21f, 0.31f), path(0, 49, 0.5f, 0.71f), path(60, 79, 0.9f, 0.9f))
        val (added, removed) = m.rerun(found, RerunMode.REPLACE)
        // Only the untouched one went; of the found faces, the one on the moved blur is not added twice.
        assertEquals(setOf(moved, scaled, off, trimmed, manual), m.tracks.map { it.id }.filter { it in setOf(moved, scaled, off, trimmed, manual) }.toSet())
        assertNull(m.track(untouched))
        assertEquals(1, removed)
        assertEquals(2, added)
        assertEquals(5 + 1 + 1, m.tracks.size)
    }

    @Test
    fun addAndReplaceAreOneUndoStepEach() {
        val m = model(path(0, 49, 0.2f, 0.3f))
        val base = m.tracks.size
        val depth = m.undoDepth
        m.rerun(listOf(path(0, 49, 0.8f, 0.8f), path(10, 30, 0.1f, 0.9f)), RerunMode.ADD)
        assertEquals(base + 2, m.tracks.size)
        assertEquals(depth + 1, m.undoDepth)
        assertTrue(m.undo())
        assertEquals(base, m.tracks.size)
        assertTrue(m.redo())
        m.rerun(listOf(path(0, 49, 0.2f, 0.3f)), RerunMode.REPLACE)
        assertEquals(1, m.tracks.size)
        assertTrue(m.undo())
        assertEquals(base + 2, m.tracks.size)
    }

    @Test
    fun nothingNewIsNoChangeAndNoUndoStep() {
        val m = model(path(0, 49, 0.2f, 0.3f))
        val depth = m.undoDepth
        val v = m.version
        assertEquals(0 to 0, m.rerun(listOf(path(0, 49, 0.2f, 0.3f)), RerunMode.ADD))
        assertEquals(depth, m.undoDepth)
        assertEquals(v, m.version)
    }

    @Test
    fun addNeverLeavesFewerBlursOnRandomRuns() {
        val r = Random(7)
        repeat(300) {
            fun any(k: Int) = List(k) { val a = r.nextInt(0, 80); path(a, a + r.nextInt(5, 40), r.nextFloat(), r.nextFloat()) }
            val m = model(*any(r.nextInt(0, 6)).toTypedArray())
            repeat(r.nextInt(0, 3)) { m.add(r.nextInt(0, 100), r.nextFloat(), r.nextFloat(), 0.1f, 0.12f) }
            val old = m.tracks.toList()
            val paths = old.map { it.id to it.path.toSortedMap().toMap() }
            m.rerun(any(r.nextInt(0, 6)), RerunMode.ADD)
            assertTrue(m.tracks.size >= old.size)
            old.forEachIndexed { k, t -> assertSame(t, m.tracks[k]) }
            assertEquals(paths, old.map { it.id to it.path.toSortedMap().toMap() })
            val m2 = model(*any(3).toTypedArray())
            val manual = m2.add(5, 0.5f, 0.5f, 0.1f, 0.12f)
            m2.rerun(any(4), RerunMode.REPLACE)
            assertNotNull(m2.track(manual))
        }
    }

    @Test
    fun theProjectsDetectionNeverGetsWeakerByAnAdd() {
        val D = DetectMode.entries
        // Replace: the new run's own.
        for (a in D) for (b in D) assertEquals(b, RerunMerge.detectAfter(a, b, RerunMode.REPLACE))
        // Add: a notice that either run had stays (selfie over fast), else extended over normal.
        assertEquals(DetectMode.FAST, RerunMerge.detectAfter(DetectMode.FAST, DetectMode.NORMAL, RerunMode.ADD))
        assertEquals(DetectMode.FAST, RerunMerge.detectAfter(DetectMode.NORMAL, DetectMode.FAST, RerunMode.ADD))
        assertEquals(DetectMode.FAST, RerunMerge.detectAfter(DetectMode.FAST, DetectMode.EXTENDED, RerunMode.ADD))
        assertEquals(DetectMode.SELFIE, RerunMerge.detectAfter(DetectMode.FAST, DetectMode.SELFIE, RerunMode.ADD))
        assertEquals(DetectMode.SELFIE, RerunMerge.detectAfter(DetectMode.SELFIE, DetectMode.NORMAL, RerunMode.ADD))
        assertEquals(DetectMode.EXTENDED, RerunMerge.detectAfter(DetectMode.NORMAL, DetectMode.EXTENDED, RerunMode.ADD))
        assertEquals(DetectMode.EXTENDED, RerunMerge.detectAfter(DetectMode.EXTENDED, DetectMode.NORMAL, RerunMode.ADD))
        assertEquals(DetectMode.NORMAL, RerunMerge.detectAfter(DetectMode.NORMAL, DetectMode.NORMAL, RerunMode.ADD))
        for (a in D) for (b in D) {
            val r = RerunMerge.detectAfter(a, b, RerunMode.ADD)
            if (a == DetectMode.FAST || a == DetectMode.SELFIE || b == DetectMode.FAST || b == DetectMode.SELFIE) assertTrue("$a $b", r == DetectMode.FAST || r == DetectMode.SELFIE)
        }
        assertEquals(0.5f, RerunMerge.sensitivityAfter(0.5f, 0.2f, RerunMode.ADD))
        assertEquals(0.2f, RerunMerge.sensitivityAfter(0.5f, 0.2f, RerunMode.REPLACE))
    }

    @Test
    fun foundFacesMoveToThisModelsFramesWhenTheTimesDiffer() {
        val from = LongArray(100) { it * 33_333L }
        val to = LongArray(50) { it * 66_666L }
        val moved = RerunMerge.remap(path(20, 50, 0.3f, 0.3f), from, to)
        assertEquals(10, moved.firstKey())
        assertEquals(25, moved.lastKey())
        assertEquals(16, moved.size)
        // The same frames: the very same path.
        val p = path(1, 5, 0.1f, 0.1f)
        assertSame(p, RerunMerge.remap(p, from, from.copyOf()))
    }

    @Test
    fun theRememberedChoiceIdsAreTheRegistrys() {
        assertEquals(RerunMode.ADD, RerunMode.of("add"))
        assertEquals(RerunMode.REPLACE, RerunMode.of("replace"))
        assertNull(RerunMode.of(null))
        assertNull(RerunMode.of("nope"))
        assertEquals("rerun.merge", RerunMerge.CHOICE_KEY)
    }
}
