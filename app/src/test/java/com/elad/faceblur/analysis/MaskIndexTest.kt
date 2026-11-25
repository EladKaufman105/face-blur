package com.elad.faceblur.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskIndexTest {
    @Test
    fun indexInsideTheFileFits() {
        assertTrue(MaskStore.indexFits(longArrayOf(0, 100, 0), intArrayOf(100, 50, 0), 150))
    }

    @Test
    fun indexPastTheEndOfAPartialFileDoesNotFit() {
        assertFalse(MaskStore.indexFits(longArrayOf(0, 100), intArrayOf(100, 50), 149))
        assertFalse(MaskStore.indexFits(longArrayOf(-1), intArrayOf(10), 100))
    }
}
