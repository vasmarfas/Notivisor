package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class WebRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val length: Long,
) {
    fun text(): String {
        if (length !in 0..MAX_TEXT_BYTES) throw IOException("request body of $length bytes")
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val count = body.read(bytes, read, bytes.size - read)
            if (count < 0) throw IOException("request body ended early")
            read += count
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 256 * 1024L
    }
}

class WebResponse(
    val status: String = "200 OK",
    val type: String = JSON,
    val length: Long = -1,
    val headers: String = "",
    val resource: AutoCloseable? = null,
    val write: (OutputStream) -> Unit = {},
) {
    companion object {
        const val JSON = "application/json; charset=utf-8"

        val EMPTY = WebResponse(status = "204 No Content", length = 0)

        fun bytes(body: ByteArray, type: String = JSON, status: String = "200 OK", headers: String = "") =
            WebResponse(status, type, body.size.toLong(), headers) { it.write(body) }

        fun json(text: String, status: String = "200 OK") = bytes(text.toByteArray(Charsets.UTF_8), status = status)

        fun error(status: String, reason: String): WebResponse {
            val escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
            return json("{\"error\":\"$escaped\"}", status)
        }
    }
}

fun interface WebApi {
    fun handle(request: WebRequest): WebResponse?
}

class WebCastServer(
    private val port: Int,
    private val password: String?,
    private val page: ByteArray,
    private val hub: CastHub,
    private val api: WebApi = WebApi { null },
) {
    private var server: ServerSocket? = null
    private val handling = AtomicInteger()
    private val failures = ArrayDeque<Long>()

    val localPort: Int get() = server?.localPort ?: port

    fun start() {
        val listener = ServerSocket(port, BACKLOG)
        server = listener
        Thread({ acceptLoop(listener) }, "cast-web").apply { isDaemon = true; start() }
        BridgeLog.i(SCOPE, "browser view on port ${listener.localPort}, ${if (password == null) "open" else "password"}")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!listener.isClosed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: break
            if (handling.incrementAndGet() > MAX_HANDLERS) {
                handling.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            Thread({ serve(socket) }, "cast-web-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: Socket) {
        var streaming = false
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            socket.tcpNoDelay = true
            val request = readRequest(BufferedInputStream(socket.getInputStream(), INPUT_BUFFER_BYTES)) ?: return
            val out = socket.getOutputStream()
            val reading = request.method == "GET"
            when {
                !addressedByIp(request.headers["host"]) -> send(out, WebResponse.error("421 Misdirected Request", "host"))
                !authorized(request.headers["authorization"]) -> send(
                    out,
                    WebResponse.bytes(
                        "401 Unauthorized".toByteArray(),
                        TEXT,
                        "401 Unauthorized",
                        "WWW-Authenticate: Basic realm=\"Notivisor\", charset=\"UTF-8\"\r\n",
                    ),
                )

                request.path.startsWith(API) -> {
                    val response = when {
                        !reading && request.headers[GUARD_HEADER] == null -> WebResponse.error("403 Forbidden", "forbidden")
                        else -> runCatching { api.handle(request) }.getOrElse {
                            BridgeLog.w(SCOPE, "${request.path} failed: ${it.message}")
                            WebResponse.error("500 Internal Server Error", it.message ?: it.javaClass.simpleName)
                        } ?: WebResponse.error("404 Not Found", "not_found")
                    }
                    send(out, response)
                }

                !reading -> send(out, WebResponse.error("405 Method Not Allowed", "method"))
                request.path == "/" -> send(out, WebResponse.bytes(page, "text/html; charset=utf-8"))
                request.path != "/stream" -> send(out, WebResponse.error("404 Not Found", "not_found"))
                hub.browsers >= MAX_VIEWERS -> send(out, WebResponse.error("503 Service Unavailable", "busy"))
                else -> {
                    out.write(STREAM_HEAD)
                    out.flush()
                    socket.soTimeout = 0
                    hub.add(WebViewer(socket, hub::remove))
                    streaming = true
                    BridgeLog.i(SCOPE, "browser ${socket.inetAddress.hostAddress} is watching")
                }
            }
        } catch (e: IOException) {
            BridgeLog.d(SCOPE, "request from ${socket.inetAddress?.hostAddress} failed: ${e.message}")
        } finally {
            handling.decrementAndGet()
            if (!streaming) runCatching { socket.close() }
        }
    }

    private fun authorized(header: String?): Boolean {
        val expected = password ?: return true
        synchronized(failures) {
            val now = System.nanoTime()
            while (failures.isNotEmpty() && now - failures.first() > LOCKOUT_NS) failures.removeFirst()
            if (failures.size >= MAX_FAILURES) return false
        }
        val given = header
            ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
            ?.let { runCatching { Base64.getDecoder().decode(it.substring(6).trim()) }.getOrNull() }
            ?.toString(Charsets.UTF_8)
            ?.substringAfter(':')
        if (given != null && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())) return true
        if (header != null) synchronized(failures) { failures.addLast(System.nanoTime()) }
        return false
    }

    private fun send(out: OutputStream, response: WebResponse) {
        val head = buildString {
            append("HTTP/1.1 ${response.status}\r\n")
            append("Content-Type: ${response.type}\r\n")
            if (response.length >= 0) append("Content-Length: ${response.length}\r\n")
            if ("Cache-Control" !in response.headers) append("Cache-Control: no-store\r\n")
            append(response.headers)
            append(COMMON_HEADERS)
            append("\r\n")
        }
        try {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            response.write(out)
            out.flush()
        } finally {
            runCatching { response.resource?.close() }
        }
    }

    private class Limited(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val next = source.read()
            if (next >= 0) left--
            return next
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (left <= 0) return -1
            val read = source.read(buffer, offset, minOf(count.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }
    }

    companion object {
        private const val SCOPE = "castweb"
        private const val BACKLOG = 16
        private const val MAX_HANDLERS = 24
        private const val MAX_VIEWERS = 4
        private const val MAX_REQUEST_BYTES = 8 * 1024
        private const val INPUT_BUFFER_BYTES = 64 * 1024
        private const val REQUEST_TIMEOUT_MS = 30_000
        private const val MAX_FAILURES = 10
        private const val LOCKOUT_NS = 60_000_000_000L
        private const val API = "/api/"
        private const val GUARD_HEADER = "x-notivisor"
        private const val TEXT = "text/plain; charset=utf-8"

        private const val COMMON_HEADERS = "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n"

        private val STREAM_HEAD = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Cache-Control: no-store\r\n" +
                COMMON_HEADERS +
                "\r\n").toByteArray(Charsets.ISO_8859_1)

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val random = SecureRandom()

        fun newPassword(): String = "%06d".format(random.nextInt(1_000_000))

        internal fun addressedByIp(host: String?): Boolean {
            host ?: return false
            if (host.startsWith("[")) return ':' in host.substringAfter('[').substringBefore(']')
            val name = host.substringBefore(':')
            return name == "localhost" || IPV4.matches(name)
        }

        private fun readRequest(input: InputStream): WebRequest? {
            val lines = mutableListOf<String>()
            val line = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val next = input.read()
                if (next < 0 || ++total > MAX_REQUEST_BYTES) return null
                if (next != '\n'.code) {
                    line.write(next)
                    continue
                }
                val text = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) break
                lines += text
            }
            val start = lines.firstOrNull()?.split(' ') ?: return null
            if (start.size < 3) return null
            val headers = lines.drop(1).mapNotNull { header ->
                val colon = header.indexOf(':')
                if (colon <= 0) null else header.substring(0, colon).trim().lowercase() to header.substring(colon + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            val query = start[1].substringAfter('?', "").split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name.isEmpty()) null else decode(name) to decode(pair.substringAfter('=', ""))
            }.toMap()
            return WebRequest(start[0], start[1].substringBefore('?'), query, headers, Limited(input, length), length)
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }
}
