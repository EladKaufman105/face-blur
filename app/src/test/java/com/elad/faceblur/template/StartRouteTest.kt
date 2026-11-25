package com.elad.faceblur.template

import com.elad.faceblur.template.StartRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRouteTest {
    @Test
    fun normalTemplatesDetectFacesFirst() {
        assertEquals(Route.DETECT_THEN_EDIT, StartRoute.of(blurOff = false, save = false))
        assertEquals(Route.DETECT_THEN_SAVE, StartRoute.of(blurOff = false, save = true))
    }

    @Test
    fun noBlurTemplateSavesStraightInsteadOfOpeningTheEditor() {
        assertEquals(Route.SAVE_NO_BLUR, StartRoute.of(blurOff = true, save = true))
        assertEquals(Route.EDIT_NO_BLUR, StartRoute.of(blurOff = true, save = false))
    }

    @Test
    fun onlyRoutesThatDetectFacesBlurThem() {
        assertTrue(StartRoute.blursFaces(Route.DETECT_THEN_EDIT))
        assertTrue(StartRoute.blursFaces(Route.DETECT_THEN_SAVE))
        assertFalse(StartRoute.blursFaces(Route.EDIT_NO_BLUR))
        assertFalse(StartRoute.blursFaces(Route.SAVE_NO_BLUR))
    }
}
