package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Person masks, one [SIZE] x [SIZE] grayscale picture per analysis frame (255 = person),
 * compressed into one file; [saveIndex] / [open] keep it with a project.
 */
class MaskStore private constructor(
    private val file: File,
    private val raf: RandomAccessFile,
    private val offsets: LongArray,
    private val lengths: IntArray,
) : Closeable {
    private val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92)
    private var lastIndex = -1
    private var last: ByteArray? = null

    val frameCount get() = offsets.size

    /** Frames that have a mask. */
    val filled get() = lengths.count { it > 0 }

    @Synchronized
    fun put(index: Int, mask: Mat) {
        if (index !in offsets.indices) return
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", mask, buf, params)
        val bytes = buf.toArray()
        buf.release()
        val at = raf.length()
        raf.seek(at)
        raf.write(bytes)
        offsets[index] = at
        lengths[index] = bytes.size
        if (index == lastIndex) lastIndex = -1
    }

    /** The mask of frame [index] (or the nearest frame that has one): [SIZE] * [SIZE] bytes, top row first. */
    @Synchronized
    fun read(index: Int): ByteArray? {
        if (offsets.isEmpty()) return null
        val i = nearestFilled(index.coerceIn(0, offsets.size - 1)) ?: return null
        if (i == lastIndex) return last
        val bytes = ByteArray(lengths[i])
        raf.seek(offsets[i])
        raf.readFully(bytes)
        val enc = MatOfByte(*bytes)
        val m = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_GRAYSCALE)
        enc.release()
        if (m.empty() || m.cols() != SIZE || m.rows() != SIZE) {
            m.release()
            return null
        }
        val out = ByteArray(SIZE * SIZE)
        m.get(0, 0, out)
        m.release()
        lastIndex = i
        last = out
        return out
    }

    private fun nearestFilled(i: Int): Int? {
        if (lengths[i] > 0) return i
        for (d in 1 until lengths.size) {
            if (i - d >= 0 && lengths[i - d] > 0) return i - d
            if (i + d < lengths.size && lengths[i + d] > 0) return i + d
        }
        return null
    }

    @Synchronized
    fun saveIndex(index: File) {
        val tmp = File(index.parentFile, index.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(offsets.size)
            for (i in offsets.indices) { o.writeLong(offsets[i]); o.writeInt(lengths[i]) }
        }
        tmp.renameTo(index)
    }

    @Synchronized
    override fun close() {
        runCatching { raf.close() }
        params.release()
    }

    companion object {
        /** Masks are stored squeezed to this square (the model sees undistorted tiles, see Segmenter). */
        const val SIZE = 320

        /** A new, empty store for [frames] frames (whatever was in [file] is deleted). */
        fun create(file: File, frames: Int): MaskStore {
            file.parentFile?.mkdirs()
            file.delete()
            return MaskStore(file, RandomAccessFile(file, "rw"), LongArray(frames), IntArray(frames))
        }

        /** Reopens saved masks, or null if there are none. */
        fun open(file: File, index: File): MaskStore? {
            if (!file.exists() || !index.exists()) return null
            return runCatching {
                DataInputStream(index.inputStream().buffered()).use { i ->
                    val n = i.readInt()
                    val offsets = LongArray(n)
                    val lengths = IntArray(n)
                    for (k in 0 until n) { offsets[k] = i.readLong(); lengths[k] = i.readInt() }
                    MaskStore(file, RandomAccessFile(file, "r"), offsets, lengths)
                }
            }.getOrNull()
        }
    }
}
