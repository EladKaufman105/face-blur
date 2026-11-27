package com.elad.faceblur.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Selfie mode's BlazeFace decoding against MediaPipe's own (see [BlazeFaceFixture]): the anchors,
 * the box decoding, the sigmoid and threshold, the weighted NMS and the undoing of the letterbox.
 */
class BlazeFaceTest {
    private class Raw(val regressors: FloatArray, val logits: FloatArray)

    private fun raw(f: BlazeFaceFixture.Frame): Raw {
        val reg = FloatArray(BlazeFace.ANCHORS * BlazeFace.VALUES)
        // Anchors the reference left out are far under any threshold.
        val logits = FloatArray(BlazeFace.ANCHORS) { -30f }
        for (a in f.anchors.split(";")) {
            val p = a.split(":")
            val i = p[0].toInt()
            logits[i] = p[1].toFloat()
            for (k in 0 until 4) reg[i * BlazeFace.VALUES + k] = p[2 + k].toFloat()
        }
        return Raw(reg, logits)
    }

    private fun expected(s: String): List<FloatArray> = s.split(";").filter { it.isNotBlank() }.map { d -> d.split(",").map { it.toFloat() }.toFloatArray() }

    private fun assertDets(name: String, want: List<FloatArray>, got: List<Det>, w: Int, h: Int) {
        assertEquals("$name: how many faces", want.size, got.size)
        for ((i, e) in want.withIndex()) {
            val d = got[i]
            assertEquals("$name #$i score", e[0], d.score, 1e-4f)
            val x0 = d.box.cx - d.box.w / 2
            val x1 = d.box.cx + d.box.w / 2
            val y0 = d.box.cy - d.box.h / 2
            val y1 = d.box.cy + d.box.h / 2
            // Fractions of the frame: a thousandth of it is 1-2 px.
            for ((k, v) in listOf(x0, y0, x1, y1).withIndex()) assertEquals("$name #$i box $k", e[1 + k], v, 1e-3f)
            assertEquals("$name #$i box in pixels", e[1] * w, x0 * w, 1f)
            assertEquals("$name #$i box in pixels", e[4] * h, y1 * h, 1f)
        }
    }

    @Test
    fun anchorsAreOneForEachCellOfA48Grid() {
        assertEquals(2304, BlazeFace.ANCHORS)
        assertEquals(2304 * 2, BlazeFace.anchors.size)
        // Row by row, at the cells' centers.
        assertEquals(0.5f / 48, BlazeFace.anchors[0], 1e-7f)
        assertEquals(0.5f / 48, BlazeFace.anchors[1], 1e-7f)
        assertEquals(1.5f / 48, BlazeFace.anchors[2], 1e-7f)
        assertEquals(0.5f / 48, BlazeFace.anchors[3], 1e-7f)
        val last = BlazeFace.ANCHORS - 1
        assertEquals(47.5f / 48, BlazeFace.anchors[2 * last], 1e-6f)
        assertEquals(47.5f / 48, BlazeFace.anchors[2 * last + 1], 1e-6f)
        // Anchor 49 is the second row's second cell.
        assertEquals(1.5f / 48, BlazeFace.anchors[2 * 49], 1e-7f)
        assertEquals(1.5f / 48, BlazeFace.anchors[2 * 49 + 1], 1e-7f)
        // Every center is inside the square, and no two anchors share one.
        val centers = (0 until BlazeFace.ANCHORS).map { BlazeFace.anchors[2 * it] to BlazeFace.anchors[2 * it + 1] }
        assertEquals(BlazeFace.ANCHORS, centers.toSet().size)
        assertTrue(centers.all { it.first in 0f..1f && it.second in 0f..1f })
    }

    @Test
    fun sigmoidIsMediaPipes() {
        assertEquals(0.5f, BlazeFace.sigmoid(0f), 0f)
        assertEquals(0.7310586f, BlazeFace.sigmoid(1f), 1e-6f)
        // Clipped at +-100: no overflow to NaN.
        assertEquals(0f, BlazeFace.sigmoid(-1e9f), 1e-30f)
        assertEquals(1f, BlazeFace.sigmoid(1e9f), 0f)
    }

    @Test
    fun decodesTheReferencesFacesAtTheDefaultThreshold() {
        for (f in BlazeFaceFixture.frames) {
            val r = raw(f)
            val res = BlazeFace.faces(r.regressors, r.logits, f.w, f.h, withExtras = false)
            assertDets(f.name, expected(f.at05), res.faces, f.w, f.h)
            assertTrue("no extras without a sensitivity", res.extras.isEmpty())
        }
    }

    @Test
    fun decodesTheReferencesFacesAtTheLowerPool() {
        for (f in BlazeFaceFixture.frames) {
            val r = raw(f)
            val pool = BlazeFace.weightedNms(BlazeFace.decode(r.regressors, r.logits, DetectTuning.SELFIE_POOL))
            assertDets(f.name + " pool", expected(f.at03), pool.map { BlazeFace.Letterbox(f.w, f.h).toDet(it) }, f.w, f.h)
        }
    }

    @Test
    fun extrasAreThePoolsFacesTheDefaultOnesDontHave() {
        for (f in BlazeFaceFixture.frames) {
            val r = raw(f)
            val res = BlazeFace.faces(r.regressors, r.logits, f.w, f.h, withExtras = true)
            // Same default faces as without a sensitivity (0 must equal the default).
            assertDets(f.name, expected(f.at05), res.faces, f.w, f.h)
            val pool = expected(f.at03).size
            assertTrue("${f.name}: the pool has at least the default faces", pool >= res.faces.size)
            // The classroom frame has a third face only the lower threshold finds: it is the extra one.
            assertEquals(f.name, if (f.name == "classroom") 1 else 0, res.extras.size)
            for (e in res.extras) {
                assertTrue(e.score < BlazeFace.THRESHOLD)
                assertTrue(res.faces.none { Geometry.iou(it.box, e.box) > DetectTuning.SAME_FACE_IOU })
            }
        }
    }

    @Test
    fun theTrackerTakesTheDetectorsScoreAsItsOwnCheck() {
        val f = BlazeFaceFixture.frames.first()
        val r = raw(f)
        for (d in BlazeFace.faces(r.regressors, r.logits, f.w, f.h, false).faces) assertEquals(d.score, d.verify, 0f)
    }

    @Test
    fun weightedNmsAveragesAGroupByScoreAndKeepsTheTopScore() {
        val a = BlazeFace.Cand(0.9f, 0.10f, 0.10f, 0.30f, 0.30f)
        val b = BlazeFace.Cand(0.6f, 0.12f, 0.10f, 0.32f, 0.30f)
        val far = BlazeFace.Cand(0.7f, 0.60f, 0.60f, 0.80f, 0.80f)
        val out = BlazeFace.weightedNms(listOf(b, far, a))
        assertEquals(2, out.size)
        assertEquals(0.9f, out[0].score, 0f)
        assertEquals((0.10f * 0.9f + 0.12f * 0.6f) / 1.5f, out[0].x0, 1e-6f)
        assertEquals((0.30f * 0.9f + 0.32f * 0.6f) / 1.5f, out[0].x1, 1e-6f)
        assertEquals(0.10f, out[0].y0, 1e-6f)
        assertEquals(0.7f, out[1].score, 0f)
        assertEquals(0.60f, out[1].x0, 1e-6f)
        // Boxes that only touch are two faces.
        val touching = BlazeFace.weightedNms(listOf(a, BlazeFace.Cand(0.8f, 0.30f, 0.10f, 0.50f, 0.30f)))
        assertEquals(2, touching.size)
        assertTrue(BlazeFace.weightedNms(emptyList()).isEmpty())
    }

    @Test
    fun theThresholdKeepsScoresAtOrOverIt() {
        val reg = FloatArray(BlazeFace.ANCHORS * BlazeFace.VALUES)
        for (i in 0 until BlazeFace.ANCHORS) { reg[i * BlazeFace.VALUES + 2] = 20f; reg[i * BlazeFace.VALUES + 3] = 20f }
        val logits = FloatArray(BlazeFace.ANCHORS) { -30f }
        logits[100] = 0f // exactly 0.5
        logits[1000] = -0.01f // just under
        val got = BlazeFace.decode(reg, logits, 0.5f)
        assertEquals(1, got.size)
        // A box of 20 px at the anchor's center.
        assertEquals(BlazeFace.anchors[200] - 10f / 192, got[0].x0, 1e-6f)
        assertEquals(BlazeFace.anchors[201] + 10f / 192, got[0].y1, 1e-6f)
        assertEquals(2, BlazeFace.decode(reg, logits, 0.4f).size)
    }

    @Test
    fun theLetterboxIsUndone() {
        // A 16:9 frame fills the square's width; bars above and below.
        val l = BlazeFace.Letterbox(1920, 1080)
        assertEquals(0f, l.padX, 1e-7f)
        assertEquals((192 - 108f) / 2 / 192, l.padY, 1e-6f)
        // The center of the square is the center of the frame.
        val d = l.toDet(BlazeFace.Cand(0.9f, 0.4f, 0.4f, 0.6f, 0.6f))
        assertEquals(0.5f, d.box.cx, 1e-6f)
        assertEquals(0.5f, d.box.cy, 1e-6f)
        assertEquals(0.2f, d.box.w, 1e-6f)
        // A 0.2-square box is 0.2 / (1 - 2 * pad) of the frame's height.
        assertEquals(0.2f / (108f / 192), d.box.h, 1e-5f)
        // A portrait frame has its bars at the sides.
        val p = BlazeFace.Letterbox(720, 1280)
        assertEquals(0f, p.padY, 1e-7f)
        assertTrue(p.padX > 0.2f)
        assertTrue(abs(p.scale - 192f / 1280) < 1e-7f)
        // The bars themselves are off the frame.
        assertTrue(p.toDet(BlazeFace.Cand(0.9f, 0.0f, 0.4f, 0.1f, 0.6f)).box.cx < 0f)
    }

    @Test
    fun theSensitivityLowersTheThresholdFrom05To03() {
        assertEquals(0.5f, DetectTuning.selfieThreshold(0f), 0f)
        assertEquals(0.3f, DetectTuning.selfieThreshold(1f), 1e-6f)
        assertEquals(0.5f, DetectTuning.selfieThreshold(Float.NaN), 0f)
        assertEquals(0.5f, DetectTuning.selfieThreshold(-1f), 0f)
        assertEquals(0.3f, DetectTuning.selfieThreshold(7f), 1e-6f)
        var last = DetectTuning.selfieThreshold(0f)
        for (i in 1..20) {
            val t = DetectTuning.selfieThreshold(i / 20f)
            assertTrue("strictly lower at step $i", t < last)
            last = t
        }
        // Never under the pool the extras are taken from.
        assertTrue(DetectTuning.selfieThreshold(1f) >= DetectTuning.SELFIE_POOL - 1e-6f)
    }
}
