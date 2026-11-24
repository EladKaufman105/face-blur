package com.elad.faceblur.template

import com.elad.faceblur.edit.ExportAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZoneTest {
    private val tiktok = SafeZone.of(Platform.TIKTOK)
    private val reels = SafeZone.of(Platform.REELS)
    private val shorts = SafeZone.of(Platform.SHORTS)

    @Test
    fun theZonesAreTheMeasuredFractionsOfA1080x1920Frame() {
        assertEquals(240f / 1920f, tiktok.top, 1e-6f)
        assertEquals(660f / 1920f, tiktok.bottom, 1e-6f)
        assertEquals(120f / 1080f, tiktok.left, 1e-6f)
        assertEquals(180f / 1080f, tiktok.right, 1e-6f)
        assertEquals(0.14f, reels.top, 1e-6f)
        assertEquals(0.35f, reels.bottom, 1e-6f)
        assertEquals(0.06f, reels.left, 1e-6f)
        assertEquals(288f / 1920f, shorts.top, 1e-6f)
        assertEquals(672f / 1920f, shorts.bottom, 1e-6f)
        assertEquals(192f / 1080f, shorts.right, 1e-6f)
    }

    @Test
    fun tiktokAndReelsDifferMateriallyAtTheSides() {
        assertTrue(tiktok.left - reels.left > 0.04f)
        assertTrue(tiktok.right - reels.right > 0.08f)
    }

    @Test
    fun theSafeAreaIsWhatTheMarginsLeave() {
        assertEquals(tiktok.left, tiktok.safeLeft, 0f)
        assertEquals(1f - tiktok.bottom, tiktok.safeBottom, 1e-6f)
    }

    @Test
    fun theViewAidIsOnlyForAVerticalFrame() {
        // A vertical export, whatever the video is.
        assertTrue(SafeZone.appliesTo(ExportAspect.VERTICAL, 1920, 1080))
        // As shot: a vertical video yes, a landscape or square one no.
        assertTrue(SafeZone.appliesTo(ExportAspect.ORIGINAL, 1080, 1920))
        assertFalse(SafeZone.appliesTo(ExportAspect.ORIGINAL, 1920, 1080))
        assertFalse(SafeZone.appliesTo(ExportAspect.ORIGINAL, 1080, 1080))
        // A square or 4:5 export is not vertical 9:16.
        assertFalse(SafeZone.appliesTo(ExportAspect.SQUARE, 1080, 1920))
        assertFalse(SafeZone.appliesTo(ExportAspect.PORTRAIT, 1080, 1920))
        assertFalse(SafeZone.appliesTo(ExportAspect.ORIGINAL, 0, 0))
    }
}
