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
class FrameStore(private val file: File) : Closeable {
    private val raf = RandomAccessFile(file.apply { delete() }, "rw")
    private var offsets = LongArray(1024)
    private var count = 0
    private val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85)
    var width = 0
        private set
    var height = 0
        private set

    val size get() = count

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
        file.delete()
    }
}
