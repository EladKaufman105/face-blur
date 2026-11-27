package com.elad.faceblur.models

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 server for the downloader's tests, one request per connection. The JDK's
 * com.sun.net.httpserver isn't on the Android unit tests' classpath (they compile against android.jar).
 */
class TestHttpServer private constructor(private val socket: ServerSocket) {
    private var handler: (TestExchange) -> Unit = {}
    @Volatile private var running = true

    val address: InetSocketAddress get() = InetSocketAddress(socket.inetAddress, socket.localPort)

    fun createContext(@Suppress("UNUSED_PARAMETER") path: String, h: (TestExchange) -> Unit) { handler = h }

    fun start() {
        thread(isDaemon = true, name = "test-http") {
            while (running) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { s.use { serve(it) } }
            }
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) {
        running = false
        socket.close()
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream()
        fun line(): String? {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (b.isEmpty()) null else b.toString()
                if (c == '\n'.code) return b.toString().trimEnd('\r')
                b.append(c.toChar())
            }
        }
        val request = line() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val ex = TestExchange(URI(request.split(' ').getOrElse(1) { "/" }), headers, BufferedOutputStream(s.getOutputStream()))
        try {
            handler(ex)
        } finally {
            ex.close()
        }
    }

    companion object {
        fun create(addr: InetSocketAddress, backlog: Int): TestHttpServer = TestHttpServer(ServerSocket(addr.port, backlog.coerceAtLeast(50), addr.address))
    }
}

/** One request: its path and headers, and the response to write. */
class TestExchange(val requestURI: URI, headers: Map<String, String>, private val out: OutputStream) {
    val requestHeaders = Headers(headers)
    val responseHeaders = MutableHeaders()
    val responseBody: OutputStream get() = out

    /** Writes the status line and headers; [length] -1 is no body. */
    fun sendResponseHeaders(code: Int, length: Long) {
        val sb = StringBuilder("HTTP/1.1 $code X\r\nConnection: close\r\n")
        sb.append("Content-Length: ${if (length < 0) 0 else length}\r\n")
        for ((k, v) in responseHeaders.all) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    fun close() {
        try { out.flush(); out.close() } catch (_: Exception) {}
    }

    class Headers(private val map: Map<String, String>) {
        fun getFirst(name: String): String? = map[name.lowercase()]
    }

    class MutableHeaders {
        val all = mutableListOf<Pair<String, String>>()
        fun add(name: String, value: String) { all += name to value }
    }
}
