package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class WebRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val length: Long,
) {
    fun text(): String {
        if (length > MAX_TEXT_BYTES) throw IOException("a text body of $length bytes is too long")
        return ByteArray(length.toInt()).also { DataInputStream(body).readFully(it) }.toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
    }
}

class WebResponse(
    val status: Int = 200,
    val type: String = JSON,
    val length: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val resource: AutoCloseable? = null,
    val write: (OutputStream) -> Unit = {},
) {
    companion object {
        const val JSON = "application/json; charset=utf-8"

        val EMPTY = WebResponse(204, length = 0)

        fun bytes(body: ByteArray, type: String = JSON, status: Int = 200, headers: Map<String, String> = emptyMap()) =
            WebResponse(status, type, body.size.toLong(), headers) { it.write(body) }

        fun json(text: String, status: Int = 200) = bytes(text.toByteArray(Charsets.UTF_8), status = status)

        fun error(status: Int, reason: String): WebResponse {
            val quoted = buildString {
                for (char in reason) when {
                    char == '"' || char == '\\' -> append('\\').append(char)
                    char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else -> append(char)
                }
            }
            return json("{\"error\":\"$quoted\"}", status)
        }
    }
}

fun interface WebApi {
    fun handle(request: WebRequest): WebResponse?
}

class WebCastServer(
    private val port: Int,
    password: String?,
    private val page: ByteArray,
    private val hub: CastHub,
    private val api: WebApi = WebApi { null },
) {
    private val secret = password?.let(::sha256)
    private val failures = ArrayDeque<Long>()
    private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null

    val localPort: Int get() = listener?.localPort ?: port

    fun start() {
        val socket = ServerSocket(port)
        val pool = ThreadPoolExecutor(0, MAX_REQUESTS, IDLE_WORKER_S, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "cast-web-worker").apply { isDaemon = true }
        }
        listener = socket
        workers = pool
        Thread({ accept(socket, pool) }, "cast-web").apply { isDaemon = true; start() }
        BridgeLog.i(SCOPE, "browser view on port ${socket.localPort}, ${if (secret == null) "open" else "password"}")
    }

    fun stop() {
        runCatching { listener?.close() }
        workers?.shutdown()
        listener = null
        workers = null
    }

    private fun accept(door: ServerSocket, pool: ThreadPoolExecutor) {
        do {
            val caller = runCatching { door.accept() }.getOrElse { failure ->
                if (!door.isClosed) {
                    BridgeLog.w(SCOPE, "accept failed: ${failure.message}")
                    Thread.sleep(ACCEPT_PAUSE_MS)
                }
                null
            } ?: continue
            runCatching { pool.execute { serve(caller) } }.onFailure {
                BridgeLog.d(SCOPE, "turned ${caller.inetAddress.hostAddress} away, $MAX_REQUESTS requests in flight")
                runCatching { caller.close() }
            }
        } while (!door.isClosed)
    }

    private fun serve(socket: Socket) {
        var watching = false
        try {
            socket.tcpNoDelay = true
            val out = socket.getOutputStream().buffered(OUTPUT_BUFFER_BYTES)
            val request = try {
                read(socket)
            } catch (e: Refused) {
                send(out, WebResponse.error(e.status, e.reason))
                null
            } ?: return
            when {
                !addressedByIp(request.headers["host"]) -> send(out, WebResponse.error(421, "host"))
                !admitted(request.headers["authorization"]) -> send(out, CHALLENGE)
                request.path.startsWith(API) -> send(out, answer(request))
                request.method != "GET" -> send(out, WebResponse.error(405, "method"))
                request.path == "/" -> send(out, WebResponse.bytes(page, HTML))
                request.path == "/stream" -> watching = watch(socket, out)
                else -> send(out, WebResponse.error(404, "not_found"))
            }
        } catch (e: IOException) {
            BridgeLog.d(SCOPE, "request from ${socket.inetAddress.hostAddress} broke off: ${e.message}")
        } catch (e: Exception) {
            BridgeLog.w(SCOPE, "request from ${socket.inetAddress.hostAddress} failed: $e")
        } finally {
            if (!watching) runCatching { socket.close() }
        }
    }

    private fun answer(request: WebRequest): WebResponse {
        if (request.method != "GET" && GUARD !in request.headers) return WebResponse.error(403, "forbidden")
        return try {
            api.handle(request) ?: WebResponse.error(404, "not_found")
        } catch (e: Exception) {
            BridgeLog.w(SCOPE, "${request.method} ${request.path} failed: ${e.message}")
            WebResponse.error(500, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun watch(socket: Socket, out: OutputStream): Boolean = synchronized(this) {
        if (hub.browsers >= MAX_BROWSERS) {
            send(out, WebResponse.error(503, "busy"))
            return false
        }
        send(out, STREAM)
        hub.add(WebViewer(socket, hub::remove))
        BridgeLog.i(SCOPE, "browser ${socket.inetAddress.hostAddress} is watching")
        true
    }

    private fun admitted(authorization: String?): Boolean {
        val expected = secret ?: return true
        val now = System.nanoTime()
        synchronized(failures) {
            while (failures.isNotEmpty() && now - failures.first() >= LOCKOUT_NS) failures.removeFirst()
            if (failures.size >= MAX_FAILURES) return false
        }
        authorization ?: return false
        val given = authorization.takeIf { it.startsWith("Basic ", ignoreCase = true) }
            ?.let { runCatching { Base64.getDecoder().decode(it.substring(6).trim()) }.getOrNull() }
            ?.toString(Charsets.UTF_8)
            ?.split(':', limit = 2)
            ?.getOrNull(1)
        if (given != null && MessageDigest.isEqual(sha256(given), expected)) return true
        synchronized(failures) {
            failures.addLast(now)
            if (failures.size == MAX_FAILURES) BridgeLog.w(SCOPE, "$MAX_FAILURES wrong passwords within a minute, refusing everyone for now")
        }
        return false
    }

    private class Refused(val status: Int, val reason: String) : Exception(reason)

    private class Body(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            return source.read().also { if (it >= 0) left-- }
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (left <= 0) return -1
            return source.read(buffer, offset, minOf(count.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }
    }

    companion object {
        private const val SCOPE = "castweb"
        private const val MAX_REQUESTS = 24
        private const val MAX_BROWSERS = 4
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val OUTPUT_BUFFER_BYTES = 16 * 1024
        private const val TIMEOUT_MS = 30_000
        private const val TIMEOUT_NS = TIMEOUT_MS * 1_000_000L
        private const val IDLE_WORKER_S = 30L
        private const val ACCEPT_PAUSE_MS = 500L
        private const val MAX_FAILURES = 10
        private const val LOCKOUT_NS = 60_000_000_000L
        private const val API = "/api/"
        private const val GUARD = "x-notivisor"
        private const val HTML = "text/html; charset=utf-8"
        private const val OCTET = """(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"""
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        private val REASONS = mapOf(
            200 to "OK",
            204 to "No Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            409 to "Conflict",
            421 to "Misdirected Request",
            422 to "Unprocessable Content",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            503 to "Service Unavailable",
        )

        private val CHALLENGE = WebResponse.bytes(
            "Unauthorized".toByteArray(),
            "text/plain; charset=utf-8",
            401,
            mapOf("WWW-Authenticate" to "Basic realm=\"Notivisor\", charset=\"UTF-8\""),
        )
        private val STREAM = WebResponse(type = "application/octet-stream")
        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val HOST = Regex(
            """(localhost|$OCTET(\.$OCTET){3}|\[[\dA-Fa-f.]*:[\dA-Fa-f.:]*\])(:\d{1,5})?""",
            RegexOption.IGNORE_CASE,
        )
        private val random = SecureRandom()

        fun newPassword(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

        internal fun addressedByIp(host: String?): Boolean = host != null && HOST.matches(host)

        private fun sha256(text: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

        private fun send(out: OutputStream, response: WebResponse) {
            try {
                val head = buildString {
                    append("HTTP/1.1 ").append(response.status).append(' ').append(REASONS[response.status].orEmpty()).append("\r\n")
                    append("Content-Type: ").append(response.type).append("\r\n")
                    if (response.length >= 0) append("Content-Length: ").append(response.length).append("\r\n")
                    if (response.headers.keys.none { it.equals("Cache-Control", ignoreCase = true) }) append("Cache-Control: no-store\r\n")
                    for ((name, value) in response.headers) append(name).append(": ").append(value).append("\r\n")
                    append("X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
                }
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                response.write(out)
                out.flush()
            } finally {
                runCatching { response.resource?.close() }
            }
        }

        private fun read(socket: Socket): WebRequest? {
            val input = socket.getInputStream()
            val head = ByteArray(MAX_HEAD_BYTES)
            val deadline = System.nanoTime() + TIMEOUT_NS
            var filled = 0
            var end = -1
            while (end < 0) {
                if (filled == head.size) throw Refused(431, "head_too_large")
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw SocketTimeoutException("no request head within $TIMEOUT_MS ms")
                socket.soTimeout = left.toInt()
                val count = input.read(head, filled, head.size - filled)
                if (count < 0) return null
                end = headEnd(head, maxOf(0, filled - 2), filled + count)
                filled += count
            }
            socket.soTimeout = TIMEOUT_MS

            val lines = String(head, 0, end, Charsets.ISO_8859_1).lines()
            val start = lines.first().split(' ')
            if (start.size != 3 || !TOKEN.matches(start[0]) || !start[1].startsWith('/') || !start[2].startsWith("HTTP/1.")) {
                throw Refused(400, "request_line")
            }
            val headers = HashMap<String, String>()
            lines.drop(1).takeWhile(String::isNotEmpty).forEach { field ->
                val name = field.substringBefore(':', "")
                if (!TOKEN.matches(name)) throw Refused(400, "header")
                val value = field.substringAfter(':').trim()
                headers.merge(name.lowercase(), value) { joined, more -> "$joined, $more" }
            }
            if ("transfer-encoding" in headers) throw Refused(501, "transfer_encoding")
            val length = headers["content-length"]?.let { it.toLongOrNull()?.takeIf { size -> size >= 0 } ?: throw Refused(400, "length") } ?: 0L

            val target = start[1]
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }
            val body = Body(SequenceInputStream(ByteArrayInputStream(head, end, filled - end), input), length)
            return WebRequest(start[0], target.substringBefore('?'), query, headers, body, length)
        }

        private fun headEnd(bytes: ByteArray, from: Int, until: Int): Int {
            for (at in from until until) {
                if (bytes[at] != LF) continue
                if (at + 1 < until && bytes[at + 1] == LF) return at + 2
                if (at + 2 < until && bytes[at + 1] == CR && bytes[at + 2] == LF) return at + 3
            }
            return -1
        }

        private fun decode(text: String): String = try {
            URLDecoder.decode(text, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            throw Refused(400, "query")
        }
    }
}
