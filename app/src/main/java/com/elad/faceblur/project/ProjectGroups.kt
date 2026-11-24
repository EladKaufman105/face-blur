package com.elad.faceblur.project

/** One line of the Projects list: a single project, or a folder holding one batch's projects. */
sealed interface ProjectEntry<T> {
    /** When it was last worked on (the list's order). */
    val sortKey: Long

    class Single<T>(val item: T, override val sortKey: Long) : ProjectEntry<T>
    class Folder<T>(val batchId: String, val items: List<T>, override val sortKey: Long) : ProjectEntry<T>
}

/**
 * Projects of one batch (same non-null [batchOf]) become one folder, the rest stay single. The
 * entries are ordered by [sortKey] (newest first), a folder by its newest project; inside a
 * folder the videos are in the order they were made, by [madeAt] (oldest first).
 */
fun <T> groupByBatch(items: List<T>, batchOf: (T) -> String?, sortKey: (T) -> Long, madeAt: (T) -> Long = sortKey): List<ProjectEntry<T>> {
    val folders = LinkedHashMap<String, MutableList<T>>()
    val out = ArrayList<ProjectEntry<T>>()
    for (p in items) {
        val b = batchOf(p)
        if (b == null) out += ProjectEntry.Single(p, sortKey(p)) else folders.getOrPut(b) { ArrayList() } += p
    }
    for ((b, ps) in folders) {
        // A batch's videos are made one after another: the earliest saved first, as they were queued.
        val ordered = ps.sortedBy(madeAt)
        out += ProjectEntry.Folder(b, ordered, ps.maxOf(sortKey))
    }
    return out.sortedByDescending { it.sortKey }
}
