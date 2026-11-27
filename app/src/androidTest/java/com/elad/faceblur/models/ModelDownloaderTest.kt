package com.elad.faceblur.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/**
 * The model downloader on the device, with Android's own HTTP stack, against a small server on
 * this device (127.0.0.1; plain HTTP is allowed there in debug builds only): a whole download,
 * a resumed one, one cut off half way, a wrong file, and a redirect to plain HTTP. The JVM tests
 * (DownloaderTest) cover the same with the JDK's stack.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val body = Random(11).nextBytes(400_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private lateinit var server: TinyServer
    private lateinit var dir: File
    private val downloader = Downloader(
        allowUrl = { it.protocol == "https" || (it.protocol == "http" && it.host == "127.0.0.1") },
        sleep = {},
    )

    @Before
    fun start() {
        dir = File(ctx.cacheDir, "dltest").apply { deleteRecursively(); mkdirs() }
        server = TinyServer(body)
    }

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun file() = ModelFile("m.onnx", "http://127.0.0.1:${server.port}/m.onnx", body.size.toLong(), sha)
    private val part get() = File(dir, "m.onnx.part")
    private val dest get() = File(dir, "m.onnx")

    @Test
    fun downloads() {
        downloader.fetch(file(), part, dest)
        assertArrayEquals(body, dest.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun resumes() {
        part.writeBytes(body.copyOf(150_000))
        downloader.fetch(file(), part, dest)
        assertEquals(listOf("bytes=150000-"), server.ranges.toList())
        assertArrayEquals(body, dest.readBytes())
    }

    @Test
    fun resumesAfterACut() {
        server.cutFirst = true
        downloader.fetch(file(), part, dest)
        assertTrue("resumed: ${server.ranges}", server.ranges.drop(1).any { it.startsWith("bytes=") })
        assertArrayEquals(body, dest.readBytes())
    }

    @Test
    fun aWrongFileIsNotKept() {
        server.wrong = true
        try {
            downloader.fetch(file(), part, dest)
            fail("kept a file with the wrong SHA-256")
        } catch (e: Downloader.ChecksumMismatch) {
            // expected
        }
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test
    fun aRedirectToPlainHttpIsRefused() {
        server.redirect = "http://example.invalid/m.onnx"
        try {
            downloader.fetch(file(), part, dest)
            fail("followed a redirect to plain HTTP")
        } catch (e: Downloader.RefusedUrl) {
            // expected
        }
        assertFalse(dest.exists())
    }

    @Test
    fun theAppsDownloaderRefusesPlainHttp() {
        try {
            Downloader(sleep = {}).fetch(file(), part, dest)
            fail()
        } catch (e: Downloader.RefusedUrl) {
            assertTrue(server.ranges.isEmpty())
        }
    }

    /** A tiny HTTP/1.1 server: one request per connection, "Range: bytes=N-" honored. */
    private class TinyServer(private val body: ByteArray) : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val ranges: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var cutFirst = false
        @Volatile var wrong = false
        @Volatile var redirect: String? = null
        private val wrongBody = Random(12).nextBytes(body.size)

        init {
            Thread {
                while (!socket.isClosed) {
                    val c = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    try {
                        handle(c)
                    } catch (e: IOException) {
                        // the client went away
                    } finally {
                        runCatching { c.close() }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(c: Socket) {
            val input = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
            input.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = input.readLine() ?: break
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("range:")) range = l.substringAfter(':').trim()
            }
            val n = ranges.size
            ranges += range ?: ""
            val out = c.getOutputStream()
            fun head(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            redirect?.let { to ->
                head("HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                out.flush()
                return
            }
            val data = if (wrong) wrongBody else body
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            if (from >= data.size) {
                head("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                out.flush()
                return
            }
            if (from > 0) {
                head("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${data.size - 1}/${data.size}\r\nContent-Length: ${data.size - from}\r\nConnection: close\r\n\r\n")
            } else {
                head("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n")
            }
            // The first answer may stop a third of the way (a dropped connection).
            val len = if (cutFirst && n == 0) (data.size - from) / 3 else data.size - from
            out.write(data, from, len)
            out.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
