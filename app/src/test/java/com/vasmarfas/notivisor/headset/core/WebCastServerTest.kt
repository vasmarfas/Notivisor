package com.vasmarfas.notivisor.headset.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

class WebCastServerTest {

    private val hub = CastHub(wantKeyframe = {}, onAudience = {})
    private val servers = mutableListOf<WebCastServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        hub.closeAll()
    }

    private fun serve(password: String?, api: WebApi = WebApi { null }): WebCastServer =
        WebCastServer(0, password, PAGE.toByteArray(), hub, api).also {
            it.start()
            servers += it
        }

    private fun WebCastServer.get(path: String, host: String = "192.168.1.5:8080", auth: String? = null): String =
        send("GET", path, host, auth)

    private fun WebCastServer.send(
        method: String,
        path: String,
        host: String = "192.168.1.5:8080",
        auth: String? = null,
        body: String? = null,
        marked: Boolean = false,
    ): String {
        Socket(InetAddress.getLoopbackAddress(), localPort).use { socket ->
            socket.soTimeout = 2_000
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\n")
                if (auth != null) append("Authorization: Basic ${Base64.getEncoder().encodeToString(auth.toByteArray())}\r\n")
                if (marked) append("X-Notivisor: 1\r\n")
                if (body != null) append("Content-Length: ${body.length}\r\n")
                append("\r\n")
                if (body != null) append(body)
            }
            socket.getOutputStream().write(request.toByteArray())
            return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun `an open server shows the page to anyone`() {
        val response = serve(null).get("/")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.endsWith(PAGE))
    }

    @Test
    fun `a password is asked for and checked`() {
        val server = serve("123456")
        val challenge = server.get("/")
        assertTrue(challenge.startsWith("HTTP/1.1 401"))
        assertTrue(challenge.contains("WWW-Authenticate: Basic"))
        assertTrue(server.get("/", auth = "viewer:654321").startsWith("HTTP/1.1 401"))
        assertTrue(server.get("/", auth = "anyone:123456").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `guessing locks the door for a while`() {
        val server = serve("123456")
        repeat(10) { server.get("/", auth = "x:${100_000 + it}") }
        assertTrue(server.get("/", auth = "x:123456").startsWith("HTTP/1.1 401"))
    }

    @Test
    fun `names other than an address are refused`() {
        val server = serve(null)
        assertTrue(server.get("/", host = "evil.example:8080").startsWith("HTTP/1.1 421"))
        assertTrue(server.get("/", host = "[fe80::1]:8080").startsWith("HTTP/1.1 200"))
        assertTrue(server.get("/missing").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun `the stream registers a browser viewer`() {
        val server = serve(null)
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: 10.0.0.2:8080\r\n\r\n".toByteArray())
            val input = DataInputStream(socket.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
            assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(head.contains("Content-Type: application/octet-stream\r\n"))
            assertTrue(head.contains("Cache-Control: no-store\r\n"))
            assertFalse(head.contains("Content-Length"))
            val deadline = System.currentTimeMillis() + 2_000
            while (hub.browsers == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, hub.browsers)

            hub.notice(WebViewer.NOTICE_STARTING)
            assertEquals(2, input.readInt())
            assertEquals(WebViewer.TAG_NOTICE, input.readByte())
            assertEquals(WebViewer.NOTICE_STARTING, input.readByte())
        }
    }

    @Test
    fun `the api reads freely and writes only with the marker header`() {
        val api = WebApi { request ->
            val body = if (request.length > 0) request.text() else ""
            WebResponse.json("${request.method} ${request.path} ${request.query["name"]} $body")
        }
        val server = serve(null, api)
        assertTrue(server.get("/api/files?name=a%20b").endsWith("GET /api/files a b "))
        assertTrue(server.send("POST", "/api/shell", body = "ls").startsWith("HTTP/1.1 403"))
        assertTrue(server.send("POST", "/api/shell", body = "ls", marked = true).endsWith("POST /api/shell null ls"))
        assertTrue(server.send("POST", "/", marked = true).startsWith("HTTP/1.1 405"))
        assertTrue(serve("123456", api).get("/api/files").startsWith("HTTP/1.1 401"))
    }

    @Test
    fun `an api that does not know the path or throws still answers`() {
        val server = serve(null, WebApi { request -> if (request.path == "/api/boom") error("boom") else null })
        assertTrue(server.get("/api/nothing").startsWith("HTTP/1.1 404"))
        val failure = server.get("/api/boom")
        assertTrue(failure.startsWith("HTTP/1.1 500"))
        assertTrue(failure.endsWith("{\"error\":\"boom\"}"))
    }

    @Test
    fun `host check accepts addresses only`() {
        assertTrue(WebCastServer.addressedByIp("192.168.0.10"))
        assertTrue(WebCastServer.addressedByIp("localhost:8080"))
        assertTrue(WebCastServer.addressedByIp("[::1]:8080"))
        assertFalse(WebCastServer.addressedByIp("192.168.0.10.nip.io:8080"))
        assertFalse(WebCastServer.addressedByIp("300.1.1.1"))
        assertFalse(WebCastServer.addressedByIp("localhost:port"))
        assertFalse(WebCastServer.addressedByIp(null))
        assertFalse(WebCastServer.addressedByIp("[evil.example]"))
    }

    @Test
    fun `broken and oversized requests are answered or dropped and the server goes on`() {
        val server = serve(null, WebApi { request -> WebResponse.json(request.text().length.toString()) })
        assertTrue(server.raw("NONSENSE\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(server.raw("GET /?q=%zz HTTP/1.1\r\nHost: 10.0.0.2\r\n\r\n").startsWith("HTTP/1.1 400"))
        val huge = server.raw("GET / HTTP/1.1\r\nHost: 10.0.0.2\r\nX-Pad: ${"a".repeat(20_000)}\r\n\r\n")
        assertTrue(huge, huge.isEmpty() || huge.startsWith("HTTP/1.1 431"))
        val long = "b".repeat(20_000)
        server.raw("POST / HTTP/1.1\r\nHost: 10.0.0.2\r\nX-Notivisor: 1\r\nContent-Length: ${long.length}\r\n\r\n$long")
        assertTrue(server.send("POST", "/api/echo", body = long, marked = true).endsWith("\r\n\r\n${long.length}"))
        assertTrue(server.get("/").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `extra connections are dropped while every worker is busy`() {
        val server = serve(null)
        val idle = List(24) { Socket(InetAddress.getLoopbackAddress(), server.localPort) }
        try {
            repeat(2) {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { extra ->
                    extra.soTimeout = 2_000
                    val ended = try {
                        extra.getInputStream().read() < 0
                    } catch (_: SocketTimeoutException) {
                        false
                    } catch (_: IOException) {
                        true
                    }
                    assertTrue("an extra connection was kept waiting", ended)
                }
            }
        } finally {
            idle.forEach { it.close() }
        }
        val deadline = System.currentTimeMillis() + 2_000
        var answer = ""
        while (!answer.startsWith("HTTP/1.1 200") && System.currentTimeMillis() < deadline) {
            answer = runCatching { server.get("/") }.getOrDefault("")
        }
        assertTrue(answer.startsWith("HTTP/1.1 200"))
    }

    private fun WebCastServer.raw(request: String): String =
        Socket(InetAddress.getLoopbackAddress(), localPort).use { socket ->
            socket.soTimeout = 2_000
            runCatching {
                socket.getOutputStream().write(request.toByteArray())
                socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            }.getOrDefault("")
        }

    private companion object {
        const val PAGE = "<!doctype html><title>cast</title>"
    }
}
