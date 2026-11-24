package com.elad.faceblur.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGroupsTest {
    private data class P(val id: String, val batch: String?, val updated: Long, val created: Long = updated)

    private fun group(vararg ps: P) = groupByBatch(ps.toList(), { it.batch }, { it.updated }, { it.created })

    @Test
    fun projectsOfOneBatchAreOneFolder() {
        val g = group(P("a", "b1", 10), P("b", null, 50), P("c", "b1", 30), P("d", "b2", 20))
        assertEquals(3, g.size)
        // Newest first: the single (50), the folder b1 (newest member 30), the folder b2 (20).
        assertTrue(g[0] is ProjectEntry.Single)
        val f1 = g[1] as ProjectEntry.Folder
        assertEquals("b1", f1.batchId)
        assertEquals(listOf("a", "c"), f1.items.map { it.id })
        assertEquals("b2", (g[2] as ProjectEntry.Folder).batchId)
    }

    @Test
    fun oldProjectsWithNoBatchStayAsTheyWere() {
        val g = group(P("a", null, 1), P("b", null, 2))
        assertEquals(listOf("b", "a"), g.map { (it as ProjectEntry.Single).item.id })
    }

    @Test
    fun aFolderKeepsTheOrderTheVideosWereMade() {
        // Edited later, a video keeps its place in the folder (made-at, not updated).
        val g = group(P("first", "b", updated = 900, created = 1), P("second", "b", updated = 5, created = 2))
        assertEquals(listOf("first", "second"), (g[0] as ProjectEntry.Folder).items.map { it.id })
    }
}
