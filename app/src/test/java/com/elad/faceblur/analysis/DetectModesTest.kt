package com.elad.faceblur.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fast detection's pacing and the sensitivity's mapping: normal and extended detection don't
 * change at all, sensitivity 0 is the default exactly, and a higher sensitivity never finds or
 * blurs less.
 */
class DetectModesTest {

    // ------------------------------------------------------------------ pacing

    @Test
    fun normalAndExtendedCadenceAsBefore() {
        for (fps in listOf(1f, 5f, 8f, 10f, 12f, 15f, 20f, 22.5f, 23.976f, 24f, 25f, 29.97f, 30f, 31f, 48f, 50f, 59.94f, 60f, 90f, 120f, 240f)) {
            // FaceAnalyzer's formulas before the modes.
            val base = max(1, (fps / 15f).roundToInt())
            val most = max(base, (fps / 8f).toInt())
            assertEquals("normal at $fps", base to most, DetectTuning.cadence(DetectMode.NORMAL, fps))
            assertEquals("extended at $fps", 1 to 1, DetectTuning.cadence(DetectMode.EXTENDED, fps))
        }
    }

    @Test
    fun fastIsFiveASecondAndNeverFewer() {
        for (fps in listOf(1f, 4f, 5f, 8f, 10f, 12f, 15f, 20f, 23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 120f, 240f)) {
            val (base, most) = DetectTuning.cadence(DetectMode.FAST, fps)
            assertEquals("fast never adapts the rate at $fps", base, most)
            val rate = fps / base
            // Every frame of a clip with fewer than 5 a second; else at least 5 a second (within 2%), and the fewest
            // frames that keep that (24 fps: every 4th, 6 a second; 8 fps: every frame).
            if (fps < 5f) assertEquals(1, base)
            else assertTrue("fast at $fps: $rate a second", rate >= 4.9f && fps / (base + 1) < 4.9f)
            // Never more often than normal detection (fast is lighter), except where both are every frame.
            assertTrue(base >= DetectTuning.cadence(DetectMode.NORMAL, fps).first)
        }
        assertEquals(6 to 6, DetectTuning.cadence(DetectMode.FAST, 30f))
        assertEquals(6 to 6, DetectTuning.cadence(DetectMode.FAST, 29.97f))
        assertEquals(12 to 12, DetectTuning.cadence(DetectMode.FAST, 60f))
        assertEquals(4 to 4, DetectTuning.cadence(DetectMode.FAST, 24f))
    }

    @Test
    fun modeOf() {
        assertEquals(DetectMode.NORMAL, DetectMode.of(sensitive = false, fast = false))
        assertEquals(DetectMode.EXTENDED, DetectMode.of(sensitive = true, fast = false))
        assertEquals(DetectMode.FAST, DetectMode.of(sensitive = false, fast = true))
        // Never weaker than asked: extended wins.
        assertEquals(DetectMode.EXTENDED, DetectMode.of(sensitive = true, fast = true))
    }

    // ------------------------------------------------------------------ sensitivity mapping

    @Test
    fun zeroIsTheDefaultExactly() {
        assertSame(DetectTuning.DEFAULT, DetectTuning.thresholds(0f))
        assertSame(DetectTuning.DEFAULT, DetectTuning.thresholds(-1f))
        assertSame(DetectTuning.DEFAULT, DetectTuning.thresholds(Float.NaN))
        // The tracker's thresholds before the setting.
        assertEquals(Thresholds(0.4f, 0.45f, 0.6f, 0.5f, 0.9f), DetectTuning.DEFAULT)
        assertEquals(DetectTuning.MOST, DetectTuning.thresholds(1f))
        assertEquals(DetectTuning.MOST, DetectTuning.thresholds(7f))
    }

    @Test
    fun higherIsNeverStricter() {
        var prev = DetectTuning.thresholds(0f)
        for (i in 1..100) {
            val t = DetectTuning.thresholds(i / 100f)
            for ((a, b) in listOf(t.cut to prev.cut, t.birth to prev.birth, t.strong to prev.strong, t.verified to prev.verified, t.shortVerified to prev.shortVerified)) {
                assertTrue("at ${i / 100f}: $a after $b", a <= b)
            }
            // A sane minimum: never under the most sensitive values, nor under the candidate pool.
            assertTrue(t.cut >= DetectTuning.MOST.cut && t.verified >= DetectTuning.MOST.verified && t.strong >= DetectTuning.MOST.strong)
            prev = t
        }
        assertTrue(DetectTuning.POOL_RF <= DetectTuning.MOST.cut && DetectTuning.POOL_YUNET <= DetectTuning.MOST.cut)
        // The bar of a clear face (no "check the faces" flag, no re-check needed) stays where it is.
        assertTrue(DetectTuning.MOST.strong < FaceDetector.CLEAR)
    }

    @Test
    fun stepsOfFivePercent() {
        assertEquals(0f, DetectTuning.quantize(0.02f), 0f)
        assertEquals(0.05f, DetectTuning.quantize(0.03f), 0f)
        assertEquals(0.4f, DetectTuning.quantize(0.41f), 1e-6f)
        assertEquals(1f, DetectTuning.quantize(3f), 0f)
        assertEquals(emptyList<Float>(), DetectTuning.levels(0f))
        assertEquals(listOf(0.05f, 0.1f, 0.15f), DetectTuning.levels(0.15f))
        assertEquals(20, DetectTuning.levels(1f).size)
        // Sensitivity 0 makes no extra tracks at all.
        val base = listOf(track(0, 9, 0.5f))
        assertSame(base, DetectTuning.widen(base, 0f) { error("no tracker run at 0") })
    }

    // ------------------------------------------------------------------ detections

    /** FaceDetector.detect's fusion before it moved to DetectTuning.fuse, word for word. */
    private fun oldFuse(rf: List<Det>, yn: List<Det>): List<Det> {
        val fused = ArrayList<Det>()
        val usedYn = HashSet<Int>()
        for (d in rf) {
            var score = d.score
            yn.forEachIndexed { j, y ->
                if (Geometry.iou(d.box, y.box) > 0.3f) {
                    score = 1f - (1f - score) * (1f - y.score * 0.8f)
                    usedYn += j
                }
            }
            fused += Det(d.box, score)
        }
        yn.forEachIndexed { j, y ->
            if (j !in usedYn && y.score >= 0.6f && rf.none { Geometry.iou(y.box, it.box) > 0.3f }) {
                fused += Det(y.box, y.score * 0.85f)
            }
        }
        return Geometry.nmsIou(fused, 0.4f)
    }

    private fun randomDets(r: java.util.Random, n: Int, lo: Float): List<Det> = List(n) {
        val w = 0.03f + r.nextFloat() * 0.15f
        Det(Box(r.nextFloat(), r.nextFloat(), w, w * (1f + r.nextFloat() * 0.4f)), lo + r.nextFloat() * (1f - lo))
    }

    private fun same(a: List<Det>, b: List<Det>) {
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.score, y.score, 0f)
            assertEquals(x.box.cx, y.box.cx, 0f)
            assertEquals(x.box.cy, y.box.cy, 0f)
            assertEquals(x.box.w, y.box.w, 0f)
            assertEquals(x.box.h, y.box.h, 0f)
        }
    }

    @Test
    fun defaultFusionUnchanged() {
        val r = java.util.Random(7)
        repeat(500) {
            val rf = randomDets(r, r.nextInt(8), 0.35f)
            val yn = randomDets(r, r.nextInt(8), 0.4f)
            same(oldFuse(rf, yn), DetectTuning.fuse(rf, yn))
        }
    }

    @Test
    fun extrasAreNestedAndNeverAFaceAlreadyFound() {
        val r = java.util.Random(11)
        repeat(500) {
            val kept = randomDets(r, r.nextInt(6), 0.4f)
            val pool = DetectTuning.fuse(randomDets(r, r.nextInt(12), DetectTuning.POOL_RF), randomDets(r, r.nextInt(12), DetectTuning.POOL_YUNET), DetectTuning.POOL_YUNET_ONLY)
            var prev: List<Det> = emptyList()
            for (i in 1..20) {
                val s = i / 20f
                val extra = DetectTuning.extras(kept, pool, DetectTuning.thresholds(s).cut)
                assertTrue("at $s: every extra of a lower sensitivity is still there", extra.containsAll(prev))
                for (e in extra) {
                    assertTrue(e.score >= DetectTuning.thresholds(s).cut)
                    assertTrue(kept.none { Geometry.iou(it.box, e.box) > DetectTuning.SAME_FACE_IOU })
                }
                prev = extra
            }
        }
    }

    // ------------------------------------------------------------------ tracks

    private fun track(first: Int, last: Int, cx: Float, cy: Float = 0.5f, w: Float = 0.1f): TreeMap<Int, Box> =
        TreeMap<Int, Box>().apply { for (f in first..last) put(f, Box(cx, cy, w, w)) }

    @Test
    fun combineKeepsEveryDefaultTrack() {
        val base = listOf(track(10, 40, 0.3f), track(0, 20, 0.7f))
        // The same first face, found earlier and followed longer; and a new face.
        val more = listOf(track(4, 50, 0.31f), track(30, 60, 0.5f, 0.2f))
        val out = DetectTuning.combine(base, more)
        assertEquals(3, out.size)
        for ((i, b) in base.withIndex()) {
            for ((f, box) in b) assertSame(box, out[i][f])
        }
        assertEquals(4, out[0].firstKey())
        assertEquals(50, out[0].lastKey())
        assertEquals(base[1], out[1])
        assertEquals(30, out[2].firstKey())
        // Nothing more: the default tracks, as they are.
        assertSame(base, DetectTuning.combine(base, emptyList()))
    }

    /** A few faces moving across a clip, each with its own (often weak) detection scores. */
    private class Scene(seed: Long) {
        val n = 150
        val fps = 30f
        val faces: List<TreeMap<Int, Box>>
        val dets = HashMap<Int, MutableList<Det>>()
        val pool = HashMap<Int, MutableList<Det>>()

        init {
            val r = java.util.Random(seed)
            faces = List(1 + r.nextInt(4)) {
                val a = r.nextInt(n / 2)
                val b = a + 10 + r.nextInt(n - a - 10)
                val x0 = 0.1f + r.nextFloat() * 0.8f
                val y0 = 0.1f + r.nextFloat() * 0.8f
                val vx = (r.nextFloat() - 0.5f) * 0.004f
                val w = 0.02f + r.nextFloat() * 0.1f
                val mean = 0.15f + r.nextFloat() * 0.8f
                val face = TreeMap<Int, Box>()
                for (f in a..b) {
                    val box = Box(x0 + vx * (f - a), y0, w, w)
                    face[f] = box
                    // Every 2nd frame is detected (15 a second): the face's score that frame, noisy.
                    if (f % 2 != 0) continue
                    val s = (mean + (r.nextFloat() - 0.5f) * 0.4f).coerceIn(0f, 1f)
                    val v = (s + (r.nextFloat() - 0.5f) * 0.3f).coerceIn(0f, 1f)
                    val det = Det(Box(box.cx + (r.nextFloat() - 0.5f) * 0.004f, box.cy, w, w), s, v)
                    if (s >= DetectTuning.DEFAULT.cut) dets.getOrPut(f) { ArrayList() } += det
                    else if (s >= DetectTuning.MOST.cut) pool.getOrPut(f) { ArrayList() } += det
                }
                face
            }
            // Clutter: single weak detections.
            repeat(r.nextInt(20)) {
                val f = 2 * r.nextInt(n / 2)
                pool.getOrPut(f) { ArrayList() } += Det(Box(r.nextFloat(), r.nextFloat(), 0.05f, 0.05f), DetectTuning.MOST.cut + r.nextFloat() * 0.15f, r.nextFloat() * 0.5f)
            }
            for (f in 0 until n step 2) dets.putIfAbsent(f, ArrayList())
        }

        /** The tracks at sensitivity [s], as FaceAnalyzer.result makes them. */
        fun tracks(s: Float, sensitive: Boolean = false): List<TreeMap<Int, Box>> {
            val motion = FloatArray(2 * n)
            val base = OfflineTracker(fps, 1f, sensitive).run(n, dets, motion, emptyList())
            if (s <= 0f) return base
            return DetectTuning.widen(base, s) { level ->
                val thr = DetectTuning.thresholds(level)
                val all = HashMap<Int, List<Det>>()
                for ((f, d) in dets) all[f] = d + DetectTuning.extras(d, pool[f] ?: emptyList(), thr.cut)
                OfflineTracker(fps, 1f, sensitive, thr).run(n, all, motion, emptyList())
            }
        }

        /** Face-frames a track box covers (IoU over 0.3). */
        fun covered(tracks: List<TreeMap<Int, Box>>): Int = faces.sumOf { face ->
            face.count { (f, box) -> tracks.any { t -> t[f]?.let { Geometry.iou(it, box) > 0.3f } == true } }
        }
    }

    @Test
    fun trackerWithDefaultThresholdsUnchanged() {
        for (seed in 1L..40L) {
            val sc = Scene(seed)
            val motion = FloatArray(2 * sc.n)
            for (sensitive in listOf(false, true)) {
                val a = OfflineTracker(sc.fps, 1f, sensitive).run(sc.n, sc.dets, motion, emptyList())
                val b = OfflineTracker(sc.fps, 1f, sensitive, DetectTuning.thresholds(0f)).run(sc.n, sc.dets, motion, emptyList())
                assertEquals(a.size, b.size)
                a.zip(b).forEach { (x, y) ->
                    assertEquals(x.keys, y.keys)
                    for ((f, box) in x) assertEquals(box.cx, y[f]!!.cx, 0f)
                }
            }
        }
    }

    @Test
    fun higherSensitivityNeverBlursLess() {
        var gained = 0
        for (seed in 1L..200L) {
            val sc = Scene(seed)
            for (sensitive in listOf(false, true)) {
                val base = sc.tracks(0f, sensitive)
                var prevCovered = sc.covered(base)
                for (i in 1..10) {
                    val tr = sc.tracks(i / 10f, sensitive)
                    // Every default track's box is still there, frame by frame.
                    for (b in base) assertTrue("seed $seed: a default track kept", tr.any { t -> b.all { (f, box) -> t[f]?.let { it.cx == box.cx && it.cy == box.cy && it.w == box.w && it.h == box.h } == true } })
                    val c = sc.covered(tr)
                    assertTrue("seed $seed at ${i / 10f}: $c face-frames covered after $prevCovered", c >= prevCovered)
                    if (i == 10 && c > sc.covered(base)) gained++
                    prevCovered = c
                }
            }
        }
        // And it does find more.
        assertTrue("faces gained in $gained scenes", gained > 20)
    }
}
