package com.elad.faceblur.batch

import com.elad.faceblur.template.ProcessingNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchNotesTest {
    @Test
    fun aWarningIsNotAFailedStep() {
        val notes = ProcessingNotes(warnings = listOf("subtitles created but not burned"))
        assertFalse(notes.hasFailures)
        // An item with only a warning is saved and needs no look.
        assertFalse(isStepFailed(ItemStatus.SAVED, notes.failures))
        assertFalse(itemNeedsCheck(ItemStatus.SAVED, lowConfidence = false, failures = notes.failures))
    }

    @Test
    fun aFailureFlagsTheVideo() {
        val notes = ProcessingNotes(failures = listOf("no speech found"), warnings = listOf("fyi"))
        assertTrue(notes.hasFailures)
        assertTrue(isStepFailed(ItemStatus.SAVED, notes.failures))
        assertTrue(itemNeedsCheck(ItemStatus.SAVED, false, notes.failures))
        // A failed video is flagged as failed, not as a step.
        assertFalse(isStepFailed(ItemStatus.FAILED, notes.failures))
        assertTrue(itemNeedsCheck(ItemStatus.FAILED, false, emptyList()))
    }

    @Test
    fun theFirstNoticeIsAFailureBeforeAWarning() {
        assertEquals("f", ProcessingNotes(listOf("f"), listOf("w")).first())
        assertEquals("w", ProcessingNotes(emptyList(), listOf("w")).first())
        assertNull(ProcessingNotes().first())
    }

    @Test
    fun otherReasonsStillFlagAVideo() {
        assertTrue(itemNeedsCheck(ItemStatus.NO_FACES, false, emptyList()))
        assertTrue(itemNeedsCheck(ItemStatus.SAVED, true, emptyList()))
        assertFalse(itemNeedsCheck(ItemStatus.SAVED, false, emptyList()))
    }

    @Test
    fun onlyAVideoInTheGalleryCountsAsSaved() {
        assertTrue(isSaved(ItemStatus.SAVED, true))
        assertTrue(isSaved(ItemStatus.NO_FACES, true))
        assertFalse(isSaved(ItemStatus.NO_FACES, false))
        assertFalse(isSaved(ItemStatus.FAILED, false))
        assertFalse(isSaved(ItemStatus.SKIPPED, false))
    }
}
