package com.elad.faceblur.pipeline

import java.io.File
import java.io.RandomAccessFile

/**
 * Takes where a video was filmed, and the phone that filmed it, out of an MP4 before it's
 * shared: the location boxes (©xyz, loci) and the QuickTime metadata box (moov/meta: location,
 * make, model, software) are renamed "free", which every player skips. Sizes don't change, so
 * nothing else in the file moves.
 */
object Mp4Privacy {
    private val HIDE = setOf("©xyz", "loci")
    private val DESCEND = setOf("moov", "trak", "udta")

    /** @return how many boxes were hidden. */
    fun scrub(file: File): Int = RandomAccessFile(file, "rw").use { f -> walk(f, 0L, f.length(), "", 0) }

    private fun walk(f: RandomAccessFile, start: Long, end: Long, parent: String, depth: Int): Int {
        var pos = start
        var hidden = 0
        val type = ByteArray(4)
        while (pos + 8 <= end) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xffffffffL
            f.readFully(type)
            var header = 8L
            if (size == 1L) {
                size = f.readLong()
                header = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header || pos + size > end) break
            val t = String(type, Charsets.ISO_8859_1)
            when {
                t in HIDE || (t == "meta" && parent == "moov") -> {
                    f.seek(pos + 4)
                    f.write("free".toByteArray(Charsets.ISO_8859_1))
                    hidden++
                }
                t in DESCEND && depth < 6 -> hidden += walk(f, pos + header, pos + size, t, depth + 1)
            }
            pos += size
        }
        return hidden
    }
}
