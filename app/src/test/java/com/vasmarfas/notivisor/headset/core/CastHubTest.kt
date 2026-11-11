package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.control.CapturePacket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

class CastHubTest {

    private val listener = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val clients = mutableListOf<Socket>()
    private var keyframeRequests = 0
    private val hub = CastHub(wantKeyframe = { keyframeRequests++ }, onAudience = {})

    @After
    fun tearDown() {
        hub.closeAll()
        clients.forEach { it.close() }
        listener.close()
    }

    private fun connect(): Pair<Socket, DataInputStream> {
        val client = Socket(listener.inetAddress, listener.localPort).apply { soTimeout = 2_000 }
        clients += client
        return listener.accept() to DataInputStream(client.getInputStream())
    }

    private fun DataInputStream.record(): Pair<Int, ByteArray> {
        val type = readUnsignedByte()
        val payload = ByteArray(readInt()).also { readFully(it) }
        return type to payload
    }

    @Test
    fun `a late viewer starts from the parameter sets and the next keyframe`() {
        val (socket, input) = connect()
        hub.resize(CapturePacket.Size(1344, 1440))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.add(WebViewer(socket, hub::remove))
        assertEquals(1, keyframeRequests)

        hub.frame(DELTA)
        hub.sound(byteArrayOf(1, 2, 3, 4))
        hub.frame(KEY)
        hub.frame(DELTA)

        val (configType, config) = input.record()
        assertEquals(WebViewer.TYPE_CONFIG.toInt(), configType)
        assertEquals(1344, config.int(0))
        assertEquals(1440, config.int(4))
        assertArrayEquals(CONFIG, config.copyOfRange(8, config.size))

        val (keyType, key) = input.record()
        assertEquals(WebViewer.TYPE_VIDEO.toInt(), keyType)
        assertArrayEquals(KEY, key.copyOfRange(8, key.size))

        val (deltaType, delta) = input.record()
        assertEquals(WebViewer.TYPE_VIDEO.toInt(), deltaType)
        assertArrayEquals(DELTA, delta.copyOfRange(8, delta.size))
        assertEquals("a burst of frames asks for a keyframe once", 1, keyframeRequests)
    }

    @Test
    fun `a browser is told why there is no picture, also when it comes late`() {
        val (first, early) = connect()
        hub.add(WebViewer(first, hub::remove))
        hub.notice(WebViewer.NOTICE_STARTING)
        hub.notice(WebViewer.NOTICE_TROUBLE, "no adb")

        assertArrayEquals(byteArrayOf(WebViewer.NOTICE_STARTING), early.record().second)
        val (type, trouble) = early.record()
        assertEquals(WebViewer.TYPE_NOTICE.toInt(), type)
        assertEquals(WebViewer.NOTICE_TROUBLE, trouble[0])
        assertEquals("no adb", trouble.copyOfRange(1, trouble.size).toString(Charsets.UTF_8))

        val (second, late) = connect()
        hub.add(WebViewer(second, hub::remove))
        assertEquals("no adb", late.record().second.drop(1).toByteArray().toString(Charsets.UTF_8))

        hub.notice(WebViewer.NOTICE_LIVE)
        val (third, fresh) = connect()
        hub.add(WebViewer(third, hub::remove))
        hub.resize(CapturePacket.Size(1344, 1440))
        hub.config(CONFIG)
        hub.frame(KEY)
        assertEquals(WebViewer.TYPE_CONFIG.toInt(), fresh.record().first)
    }

    @Test
    fun `a restarted capture makes every viewer wait for a keyframe again`() {
        val (socket, input) = connect()
        hub.add(WebViewer(socket, hub::remove))
        hub.resize(CapturePacket.Size(1344, 1440))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.reset()
        hub.resize(CapturePacket.Size(1344, 1440))
        hub.frame(DELTA)
        hub.config(CONFIG)
        hub.frame(KEY)

        assertEquals(WebViewer.TYPE_CONFIG.toInt(), input.record().first)
        assertArrayEquals(KEY, input.record().second.drop(8).toByteArray())
        assertEquals(WebViewer.TYPE_CONFIG.toInt(), input.record().first)
        assertArrayEquals(KEY, input.record().second.drop(8).toByteArray())
    }

    @Test
    fun `the phone gets its size header first and sound marked by the top bit`() {
        val (socket, input) = connect()
        hub.add(PhoneViewer(socket, hub::remove))
        hub.resize(CapturePacket.Size(1600, 1032))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.sound(byteArrayOf(9, 8))

        assertEquals(1600, input.readInt())
        assertEquals(1032, input.readInt())
        assertEquals(CONFIG.size, input.readInt())
        input.readFully(ByteArray(CONFIG.size))
        assertEquals(KEY.size, input.readInt())
        input.readFully(ByteArray(KEY.size))
        assertEquals(2 or Int.MIN_VALUE, input.readInt())
        assertEquals(9, input.readByte().toInt())
    }

    @Test
    fun `a resize drops the phone and makes browsers wait for new parameters`() {
        val (phoneSocket, phoneInput) = connect()
        val (webSocket, webInput) = connect()
        hub.add(PhoneViewer(phoneSocket, hub::remove))
        hub.add(WebViewer(webSocket, hub::remove))
        hub.resize(CapturePacket.Size(1280, 720))
        hub.config(CONFIG)
        hub.frame(KEY)
        assertEquals(2, hub.audience)

        hub.resize(CapturePacket.Size(1920, 1080))
        assertEquals(1, hub.audience)
        assertEquals(1, hub.browsers)

        hub.frame(KEY)
        hub.config(CONFIG)
        hub.frame(KEY)

        assertEquals(WebViewer.TYPE_CONFIG.toInt(), webInput.record().first)
        assertEquals(WebViewer.TYPE_VIDEO.toInt(), webInput.record().first)
        val (type, config) = webInput.record()
        assertEquals(WebViewer.TYPE_CONFIG.toInt(), type)
        assertEquals(1920, config.int(0))

        val closed = try {
            while (phoneInput.read() >= 0) Unit
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
        assertTrue("the phone connection stayed open after the resize", closed)
    }

    private fun ByteArray.int(at: Int): Int = ByteBuffer.wrap(this, at, 4).int

    private companion object {
        val CONFIG = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1F, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val KEY = byteArrayOf(0, 0, 0, 1, 0x09, 0x10, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        val DELTA = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x02)
    }
}
