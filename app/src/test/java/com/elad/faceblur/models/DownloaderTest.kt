package com.elad.faceblur.models

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** The model downloader against a local server: resume, checksum, redirects. */
class DownloaderTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val body = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    /** The Range header of each request ("" without one). */
    private val ranges: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** What the server does with a request (the number of requests so far is passed). */
    @Volatile private var handler: (HttpExchange, Int) -> Unit = { ex, _ -> serve(ex, body) }

    // Plain HTTP to this machine only (the app allows HTTPS only).
    private val downloader = Downloader(
        allowUrl = { it.protocol == "https" || (it.protocol == "http" && it.host == "127.0.0.1") },
        sleep = {},
    )

    @Before
    fun start() {
        dir = Files.createTempDirectory("dl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val n = ranges.size
            ranges += ex.requestHeaders.getFirst("Range") ?: ""
            try {
                handler(ex, n)
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun url(path: String = "/m.onnx") = "http://127.0.0.1:${server.address.port}$path"

    private fun file(size: Long = body.size.toLong(), sum: String = sha) = ModelFile("m.onnx", url(), size, sum)

    /** Serves [data], honoring "Range: bytes=N-". */
    private fun serve(ex: HttpExchange, data: ByteArray, honorRange: Boolean = true) {
        val range = ex.requestHeaders.getFirst("Range")
        val from = if (honorRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        if (from >= data.size && range != null) {
            ex.sendResponseHeaders(416, -1)
            return
        }
        if (from > 0) {
            ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            ex.sendResponseHeaders(206, (data.size - from).toLong())
        } else {
            ex.sendResponseHeaders(200, data.size.toLong())
        }
        ex.responseBody.write(data, from, data.size - from)
    }

    private fun fetch(m: ModelFile = file(), part: File = File(dir, "m.onnx.part"), dest: File = File(dir, "m.onnx")) =
        downloader.fetch(m, part, dest)

    @Test
    fun downloadsAndChecks() {
        var verifying = false
        downloader.fetch(file(), File(dir, "m.onnx.part"), File(dir, "m.onnx"), onVerifying = { verifying = true })
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
        assertFalse(File(dir, "m.onnx.part").exists())
        assertTrue(verifying)
        assertEquals(listOf(""), ranges.toList())
    }

    @Test
    fun resumesAPartialFile() {
        File(dir, "m.onnx.part").writeBytes(body.copyOf(100_000))
        fetch()
        assertEquals(listOf("bytes=100000-"), ranges.toList())
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresRange() {
        File(dir, "m.onnx.part").writeBytes(ByteArray(100_000))
        handler = { ex, _ -> serve(ex, body, honorRange = false) }
        fetch()
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun aDamagedPartIsDownloadedAgainFromTheStart() {
        // The first bytes on disk are wrong: the resumed file fails the check, then a clean one passes.
        File(dir, "m.onnx.part").writeBytes(ByteArray(100_000))
        fetch()
        assertEquals(listOf("bytes=100000-", ""), ranges.toList())
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun resumesAfterABrokenConnection() {
        handler = { ex, n ->
            if (n == 0) {
                // Says the whole size, sends a third, and hangs up.
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.write(body, 0, 100_000)
                ex.responseBody.flush()
                throw java.io.IOException("connection dropped")
            } else {
                serve(ex, body)
            }
        }
        fetch()
        assertEquals("", ranges[0])
        assertTrue("resumed: $ranges", ranges.drop(1).any { it.startsWith("bytes=") })
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun wrongContentIsNeverKept() {
        val other = Random(8).nextBytes(body.size)
        handler = { ex, _ -> serve(ex, other) }
        val dest = File(dir, "m.onnx")
        try {
            fetch()
            fail("accepted a file with the wrong checksum")
        } catch (e: Downloader.ChecksumMismatch) {
            // expected
        }
        assertFalse(dest.exists())
        assertFalse(File(dir, "m.onnx.part").exists())
        // Once resumed (nothing to resume here), once from scratch: then it gives up.
        assertEquals(2, ranges.size)
    }

    @Test
    fun aGoodFileIsNotReplacedByABadOne() {
        val dest = File(dir, "m.onnx").apply { writeBytes(body) }
        handler = { ex, _ -> serve(ex, Random(9).nextBytes(body.size)) }
        try {
            fetch()
            fail()
        } catch (e: Downloader.ChecksumMismatch) {
            // expected
        }
        assertArrayEquals(body, dest.readBytes())
    }

    @Test
    fun tooLongIsRefused() {
        val longer = body + ByteArray(10)
        handler = { ex, _ -> serve(ex, longer) }
        try {
            fetch()
            fail()
        } catch (e: java.io.IOException) {
            assertFalse(File(dir, "m.onnx").exists())
        }
    }

    @Test
    fun redirectToPlainHttpIsRefused() {
        handler = { ex, _ ->
            ex.responseHeaders.add("Location", "http://example.invalid/m.onnx")
            ex.sendResponseHeaders(302, -1)
        }
        try {
            fetch()
            fail("followed a redirect to http")
        } catch (e: Downloader.RefusedUrl) {
            // expected
        }
        assertEquals(1, ranges.size)
        assertFalse(File(dir, "m.onnx").exists())
    }

    @Test
    fun plainHttpIsRefusedByDefault() {
        try {
            Downloader(sleep = {}).fetch(file(), File(dir, "p"), File(dir, "d"))
            fail()
        } catch (e: Downloader.RefusedUrl) {
            assertTrue(ranges.isEmpty())
        }
    }

    @Test
    fun followsAnAllowedRedirect() {
        handler = { ex, _ ->
            if (ex.requestURI.path == "/m.onnx") {
                ex.responseHeaders.add("Location", "/real/m.onnx")
                ex.sendResponseHeaders(302, -1)
            } else {
                serve(ex, body)
            }
        }
        fetch()
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun notFoundIsNotRetried() {
        handler = { ex, _ -> ex.sendResponseHeaders(404, -1) }
        try {
            fetch()
            fail()
        } catch (e: Downloader.HttpStatus) {
            assertEquals(404, e.code)
        }
        assertEquals(1, ranges.size)
    }

    @Test
    fun serverErrorsAreRetried() {
        handler = { ex, n -> if (n < 2) ex.sendResponseHeaders(503, -1) else serve(ex, body) }
        fetch()
        assertEquals(3, ranges.size)
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun aWholePartNeedsNoRequest() {
        File(dir, "m.onnx.part").writeBytes(body)
        fetch()
        assertTrue(ranges.isEmpty())
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun cancellingKeepsThePart() {
        var calls = 0
        try {
            downloader.fetch(file(), File(dir, "m.onnx.part"), File(dir, "m.onnx"), cancelled = { ++calls > 2 })
            fail()
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse(File(dir, "m.onnx").exists())
    }

    private fun unpinned(min: Long = 100_000) = ModelFile("m.onnx", url(), 0, "", minSize = min, approxSize = 574_000_000)

    @Test
    fun anUnpinnedFileIsCheckedBySizeOnly() {
        downloader.fetch(unpinned(), File(dir, "m.onnx.part"), File(dir, "m.onnx"))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun anUnpinnedFileResumes() {
        File(dir, "m.onnx.part").writeBytes(body.copyOf(100_000))
        downloader.fetch(unpinned(), File(dir, "m.onnx.part"), File(dir, "m.onnx"))
        assertEquals(listOf("bytes=100000-"), ranges.toList())
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun aWholeUnpinnedPartIsKept() {
        // The server says the range is past the end (416): the part is whole, as the app took it before.
        File(dir, "m.onnx.part").writeBytes(body)
        downloader.fetch(unpinned(), File(dir, "m.onnx.part"), File(dir, "m.onnx"))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun aTooSmallUnpinnedFileIsRefused() {
        try {
            downloader.fetch(unpinned(min = body.size + 1L), File(dir, "m.onnx.part"), File(dir, "m.onnx"))
            fail("kept a file smaller than its smallest size")
        } catch (e: java.io.IOException) {
            assertFalse(File(dir, "m.onnx").exists())
        }
    }

    @Test
    fun anUnpinnedFileCutShortIsResumed() {
        handler = { ex, n ->
            if (n == 0) {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.write(body, 0, 150_000)
                ex.responseBody.flush()
                throw java.io.IOException("connection dropped")
            } else {
                serve(ex, body)
            }
        }
        downloader.fetch(unpinned(), File(dir, "m.onnx.part"), File(dir, "m.onnx"))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun unpinnedMatchesBySize() {
        val f = File(dir, "x").apply { writeBytes(body) }
        assertTrue(Hashing.matches(f, unpinned()))
        assertFalse(Hashing.matches(f, unpinned(min = body.size + 1L)))
    }

    @Test
    fun hashingMatches() {
        val f = File(dir, "x").apply { writeBytes(body) }
        assertEquals(sha, Hashing.sha256(f))
        assertTrue(Hashing.matches(f, file()))
        assertFalse(Hashing.matches(f, file(sum = "0".repeat(64))))
        assertFalse(Hashing.matches(File(dir, "none"), file()))
    }
}
