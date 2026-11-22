package com.elad.faceblur.analysis

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Small grayscale copies of every analysis frame (the 640 px motion frames), JPEG-compressed
 * into one cache file. The editor's "track this blur" reads them back, so following a face by
 * hand needs no second decode of the video.
 */
class FrameStore private constructor(
    private var file: File,
    private var raf: RandomAccessFile,
    private var offsets: LongArray,
    private var count: Int,
) : Closeable {
    /** A new, empty store (whatever was in [file] is deleted). */
    constructor(file: File) : this(file, RandomAccessFile(file.apply { delete() }, "rw"), LongArray(1024), 0)

    // A project opened straight from the home screen gets here before any analysis has
    // loaded OpenCV.
    init {
        OpenCv.ensureLoaded()
    }

    /** The file belongs to a saved project: closing doesn't delete it. */
    private var kept = false
    private val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85)
    var width = 0
        private set
    var height = 0
        private set

    val size get() = count

    /** Where each frame starts, and the end of the last one (for [open]). */
    @Synchronized
    fun index(): LongArray = offsets.copyOf(count + 1)

    /**
     * Moves the file to [dest] (a saved project) and keeps it there from now on.
     * Returns false if it couldn't be moved (the store keeps working where it was).
     */
    @Synchronized
    fun moveTo(dest: File): Boolean {
        if (file == dest) return true
        dest.parentFile?.mkdirs()
        if (file.renameTo(dest)) {
            file = dest
            kept = true
            return true
        }
        return try {
            raf.seek(0)
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var left = raf.length()
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            raf.close()
            file.delete()
            file = dest
            raf = RandomAccessFile(dest, "rw")
            kept = true
            true
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }

    @Synchronized
    fun reset() {
        raf.setLength(0)
        count = 0
    }

    @Synchronized
    fun append(gray: Mat) {
        width = gray.cols()
        height = gray.rows()
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", gray, buf, params)
        val bytes = buf.toArray()
        buf.release()
        if (count + 1 >= offsets.size) offsets = offsets.copyOf(offsets.size * 2)
        val at = raf.length()
        raf.seek(at)
        raf.write(bytes)
        offsets[count] = at
        offsets[count + 1] = at + bytes.size
        count++
    }

    /** Decoded frame [index] (caller releases it), or null if out of range. */
    @Synchronized
    fun read(index: Int): Mat? {
        if (index !in 0 until count) return null
        val len = (offsets[index + 1] - offsets[index]).toInt()
        val bytes = ByteArray(len)
        raf.seek(offsets[index])
        raf.readFully(bytes)
        val buf = MatOfByte(*bytes)
        val m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE)
        buf.release()
        return if (m.empty()) { m.release(); null } else m
    }

    @Synchronized
    override fun close() {
        runCatching { raf.close() }
        params.release()
        if (!kept) file.delete()
    }

    companion object {
        /** Reopens a saved store (frames are only read). */
        fun open(file: File, index: LongArray, width: Int, height: Int): FrameStore? {
            if (!file.exists() || index.isEmpty()) return null
            return FrameStore(file, RandomAccessFile(file, "r"), index.copyOf(maxOf(index.size, 2)), index.size - 1).also {
                it.kept = true
                it.width = width
                it.height = height
            }
        }
    }
}
