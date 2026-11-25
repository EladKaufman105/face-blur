package com.elad.faceblur.edit

import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.BgKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskPolicyTest {
    private val person = Background(fill = BgFill.values().first { it != BgFill.NONE }, key = BgKey.PERSON)

    @Test
    fun masksStayWhenTheFrameTimesAreTheSame() {
        assertTrue(MaskPolicy.keepMasks(longArrayOf(0, 33, 66), longArrayOf(0, 33, 66)))
        assertFalse(MaskPolicy.keepMasks(longArrayOf(0, 33, 66), longArrayOf(0, 33)))
        assertFalse(MaskPolicy.keepMasks(longArrayOf(0, 33, 66), longArrayOf(0, 34, 66)))
    }

    @Test
    fun peopleAreFoundAgainOnlyForAnActivePeopleBackground() {
        assertTrue(MaskPolicy.rerunPeople(person, masksKept = false))
        assertFalse(MaskPolicy.rerunPeople(person, masksKept = true))
        assertFalse(MaskPolicy.rerunPeople(Background(), masksKept = false))
        assertFalse(MaskPolicy.rerunPeople(person.copy(key = BgKey.GREEN), masksKept = false))
    }

    @Test
    fun exportRefusesAPeopleBackgroundWithoutMasks() {
        assertTrue(MaskPolicy.exportBlocked(person, masksReady = false))
        assertFalse(MaskPolicy.exportBlocked(person, masksReady = true))
        assertFalse(MaskPolicy.exportBlocked(Background(), masksReady = false))
        assertFalse(MaskPolicy.exportBlocked(person.copy(key = BgKey.BLUE), masksReady = false))
    }
}
