package com.elad.faceblur.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** SHA-256 of files, as lowercase hex. */
object Hashing {
    fun sha256(f: File, cancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (cancelled()) throw CancellationException("hashing stopped")
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** [f] exists with [m]'s size and checksum (an unpinned file: at least its smallest size, as before the registry). */
    fun matches(f: File, m: ModelFile, cancelled: () -> Boolean = { false }): Boolean =
        if (!m.pinned) f.isFile && f.length() >= m.minSize
        else f.isFile && f.length() == m.size && sha256(f, cancelled) == m.sha256
}

/**
 * Downloads one model file: into a `.part` file (resumed with a Range request after a broken
 * connection or a restart), then checked for its size and SHA-256, then renamed over the final
 * file in one step. Nothing that fails the check is ever kept under the final name.
 *
 * Only URLs that [allowUrl] accepts are opened, the first one and every redirect (HTTPS only, by
 * default: a redirect to plain HTTP is refused). No cookies, no identifiers: a plain GET.
 * Blocking; call it off the main thread. [cancelled] is polled; it stops with a
 * [CancellationException] and keeps the partial file for a later resume.
 */
class Downloader(
    private val allowUrl: (URL) -> Boolean = { it.protocol == "https" },
    private val retries: Int = 4,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** The file came down whole but isn't the one the registry names (twice: once resumed, once from scratch). */
    class ChecksumMismatch(name: String) : IOException("$name: SHA-256 doesn't match")

    /** A URL (or a redirect) that may not be opened. */
    class RefusedUrl(url: URL) : IOException("refused URL ${url.protocol}://${url.host}")

    /** The server said no (a 4xx other than a timeout or "too many requests"). */
    class HttpStatus(val code: Int) : IOException("HTTP $code")

    /**
     * Fetches [m] into [dest] by way of [part]. [onBytes] gets the bytes on disk now and then;
     * [onVerifying] is called before the checksum is computed.
     */
    fun fetch(
        m: ModelFile,
        part: File,
        dest: File,
        cancelled: () -> Boolean = { false },
        onVerifying: () -> Unit = {},
        onBytes: (Long) -> Unit = {},
    ) {
        if (!m.pinned) return fetchUnpinned(m, part, dest, cancelled, onBytes)
        require(m.size > 0 && ModelRegistry.isSha256(m.sha256)) { "${m.name}: no size or checksum" }
        var failures = 0
        var restarted = false
        while (true) {
            if (cancelled()) throw CancellationException("download stopped")
            if (part.length() > m.size) part.delete()
            try {
                if (part.length() < m.size) transfer(m, part, cancelled, onBytes)
                if (part.length() != m.size) throw IOException("${m.name}: ${part.length()} of ${m.size} bytes")
                onBytes(part.length())
                onVerifying()
                if (Hashing.sha256(part, cancelled) != m.sha256) {
                    part.delete()
                    // A resumed file may have been damaged before: once more from the start.
                    if (!restarted) {
                        restarted = true
                        continue
                    }
                    throw ChecksumMismatch(m.name)
                }
                dest.parentFile?.mkdirs()
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChecksumMismatch) {
                throw e
            } catch (e: RefusedUrl) {
                throw e
            } catch (e: HttpStatus) {
                if (e.code != 408 && e.code != 429 && e.code < 500) throw e
                failures++
                if (failures > retries) throw e
                sleep(backoff(failures))
            } catch (e: IOException) {
                failures++
                if (failures > retries) throw e
                sleep(backoff(failures))
            }
        }
    }

    /**
     * An unpinned file (a speech model whose mirror isn't published yet): as the app did before
     * the registry, resumed, then kept if it is at least [ModelFile.minSize] bytes (and as long as
     * the server said). No checksum: there is none to check against.
     */
    private fun fetchUnpinned(m: ModelFile, part: File, dest: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        require(m.minSize > 0) { "${m.name}: no size to check" }
        var failures = 0
        while (true) {
            if (cancelled()) throw CancellationException("download stopped")
            try {
                val total = transfer(m, part, cancelled, onBytes)
                if (total > 0 && part.length() != total) throw IOException("${m.name}: ${part.length()} of $total bytes")
                if (part.length() < m.minSize) throw IOException("${m.name}: download incomplete")
                onBytes(part.length())
                dest.parentFile?.mkdirs()
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefusedUrl) {
                throw e
            } catch (e: HttpStatus) {
                if (e.code != 408 && e.code != 429 && e.code < 500) throw e
                failures++
                if (failures > retries) throw e
                sleep(backoff(failures))
            } catch (e: IOException) {
                failures++
                if (failures > retries) throw e
                sleep(backoff(failures))
            }
        }
    }

    private fun backoff(n: Int): Long = (1000L shl (n - 1).coerceAtMost(4)).coerceAtMost(15_000L)

    /**
     * One request (following redirects): appends to [part] from where it ends. Returns the whole
     * file's size as the server gave it (0: unknown).
     */
    private fun transfer(m: ModelFile, part: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit): Long {
        // A pinned file can't grow past its size; an unpinned one has no size.
        val cap = if (m.pinned) m.size else Long.MAX_VALUE
        var url = URL(m.url)
        if (!allowUrl(url)) throw RefusedUrl(url)
        var redirects = 0
        while (true) {
            val have = part.length()
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without a location")
                    val next = URL(url, loc)
                    if (!allowUrl(next)) throw RefusedUrl(next)
                    redirects++
                    if (redirects > 8) throw IOException("too many redirects")
                    url = next
                    continue
                }
                // Asked for bytes past the end: the part is whole (or too long, and the size check says so).
                if (code == 416) return 0L
                if (code != 200 && code != 206) throw HttpStatus(code)
                // A 206 that doesn't start where it was asked to: start over.
                val append = code == 206 && have > 0
                if (append) {
                    val range = conn.getHeaderField("Content-Range")
                    if (range != null && !range.startsWith("bytes $have-")) {
                        part.delete()
                        throw IOException("unexpected range $range")
                    }
                }
                var done = if (append) have else 0L
                val total = conn.contentLengthLong.let { if (it > 0) done + it else 0L }
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var shown = done
                        while (true) {
                            if (cancelled()) throw CancellationException("download stopped")
                            val n = input.read(buf)
                            if (n < 0) break
                            if (done + n > cap) {
                                out.close()
                                part.delete()
                                throw IOException("${m.name}: more than ${m.size} bytes")
                            }
                            out.write(buf, 0, n)
                            done += n
                            if (done - shown >= 256 * 1024) {
                                shown = done
                                onBytes(done)
                            }
                        }
                    }
                }
                return total
            } finally {
                conn.disconnect()
            }
        }
    }
}
