package com.elad.faceblur.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSrtTest {
    @Test
    fun editorExportOfTheUsersOwnOptionsSavesNoSrt() {
        assertFalse(ExportOptions(saveSrt = true).forEditorExport(ownedBySession = false).saveSrt)
    }

    @Test
    fun aTemplatesSrtChoiceSurvivesTheEditor() {
        assertTrue(ExportOptions(saveSrt = true).forEditorExport(ownedBySession = true).saveSrt)
        assertFalse(ExportOptions(saveSrt = false).forEditorExport(ownedBySession = true).saveSrt)
    }
}
