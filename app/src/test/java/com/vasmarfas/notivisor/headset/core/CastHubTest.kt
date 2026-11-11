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

    private fun connect(window: Int = 0): Pair<Socket, DataInputStream> {
        val client = Socket().apply {
            soTimeout = 2_000
            if (window > 0) receiveBufferSize = window
            connect(listener.localSocketAddress)
        }
        clients += client
        val socket = listener.accept().apply { if (window > 0) sendBufferSize = window }
        return socket to DataInputStream(client.getInputStream())
    }

    private fun DataInputStream.record(): Pair<Int, ByteArray> {
        val size = readInt()
        val tag = readUnsignedByte()
        return tag to ByteArray(size - 1).also { readFully(it) }
    }

    private fun DataInputStream.video(): ByteArray {
        val (type, payload) = record()
        assertEquals(WebViewer.TAG_VIDEO.toInt(), type)
        return payload.copyOfRange(8, payload.size)
    }

    @Test
    fun `a late viewer starts at the keyframe already in the backlog`() {
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
        assertEquals(WebViewer.TAG_CONFIG.toInt(), configType)
        assertEquals(1344, config.int(0))
        assertEquals(1440, config.int(4))
        assertArrayEquals(CONFIG, config.copyOfRange(8, config.size))

        assertArrayEquals(KEY, input.video())
        assertArrayEquals(DELTA, input.video())
        val (soundType, sound) = input.record()
        assertEquals(WebViewer.TAG_SOUND.toInt(), soundType)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sound.copyOfRange(8, sound.size))
        assertArrayEquals(KEY, input.video())
        assertArrayEquals(DELTA, input.video())
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
        assertEquals(WebViewer.TAG_NOTICE.toInt(), type)
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
        assertEquals(WebViewer.TAG_CONFIG.toInt(), fresh.record().first)
    }

    @Test
    fun `a browser record starts with a length that counts its letter tag`() {
        val (socket, input) = connect()
        hub.add(WebViewer(socket, hub::remove))
        hub.notice(WebViewer.NOTICE_TROUBLE, "x")
        val notice = ByteArray(7).also { input.readFully(it) }
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 'N'.code.toByte(), 2, 'x'.code.toByte()), notice)

        hub.resize(CapturePacket.Size(1344, 1440))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.sound(byteArrayOf(5))
        assertEquals('C'.code, input.record().first)
        assertEquals('V'.code, input.record().first)
        val (tag, sound) = input.record()
        assertEquals('S'.code, tag)
        assertEquals(Long.SIZE_BYTES + 1, sound.size)
    }

    @Test
    fun `a restarted capture makes every viewer wait for a keyframe again`() {
        val (socket, input) = connect()
        hub.add(WebViewer(socket, hub::remove))
        hub.resize(CapturePacket.Size(1344, 1440))
        hub.config(CONFIG)
        hub.frame(KEY)
        assertEquals(WebViewer.TAG_CONFIG.toInt(), input.record().first)
        assertArrayEquals(KEY, input.video())

        hub.reset()
        hub.resize(CapturePacket.Size(1344, 1440))
        hub.frame(DELTA)
        hub.config(CONFIG)
        hub.frame(KEY)

        assertEquals(WebViewer.TAG_CONFIG.toInt(), input.record().first)
        assertArrayEquals(KEY, input.video())
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
    fun `a resize drops the phone and lets browsers continue from new parameters`() {
        val (phoneSocket, phoneInput) = connect()
        val (webSocket, webInput) = connect()
        val phone = PhoneViewer(phoneSocket, hub::remove)
        hub.add(phone)
        hub.add(WebViewer(webSocket, hub::remove))
        hub.resize(CapturePacket.Size(1280, 720))
        hub.config(CONFIG)
        hub.frame(KEY)
        phoneInput.readFully(ByteArray(8 + 4 + CONFIG.size + 4 + KEY.size))
        assertEquals(WebViewer.TAG_CONFIG.toInt(), webInput.record().first)
        assertArrayEquals(KEY, webInput.video())
        assertEquals(2, hub.audience)

        hub.resize(CapturePacket.Size(1920, 1080))
        assertTrue(phone.outgrown)
        assertEquals(1, hub.audience)
        assertEquals(1, hub.browsers)

        hub.frame(KEY)
        hub.config(CONFIG)
        hub.frame(KEY)

        val (type, config) = webInput.record()
        assertEquals(WebViewer.TAG_CONFIG.toInt(), type)
        assertEquals(1920, config.int(0))
        assertArrayEquals(KEY, webInput.video())

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

    @Test
    fun `keyframe requests are spaced out except for a new viewer`() {
        hub.refresh()
        hub.refresh()
        assertEquals(1, keyframeRequests)

        val (socket, _) = connect()
        hub.add(WebViewer(socket, hub::remove))
        assertEquals(2, keyframeRequests)
        hub.refresh()
        assertEquals(2, keyframeRequests)

        Thread.sleep(600)
        hub.refresh()
        assertEquals(3, keyframeRequests)
    }

    @Test
    fun `a stalled reader jumps to the newest keyframe while a quick one receives every frame`() {
        val (slowSocket, slowInput) = connect(window = 4096)
        val (fastSocket, fastInput) = connect()
        hub.resize(CapturePacket.Size(1280, 720))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.add(WebViewer(slowSocket, hub::remove))
        hub.add(WebViewer(fastSocket, hub::remove))
        assertEquals(WebViewer.TAG_CONFIG.toInt(), fastInput.record().first)
        assertArrayEquals(KEY, fastInput.video())

        val heavy = ByteArray(128 * 1024).also { DELTA.copyInto(it) }
        val newest = KEY + 7.toByte()
        repeat(HEAVY_FRAMES) {
            hub.frame(heavy)
            assertEquals(heavy.size, fastInput.video().size)
        }
        hub.frame(newest)
        assertArrayEquals(newest, fastInput.video())
        hub.frame(DELTA)
        assertArrayEquals(DELTA, fastInput.video())

        assertEquals(WebViewer.TAG_CONFIG.toInt(), slowInput.record().first)
        var before = 0
        while (!slowInput.video().contentEquals(newest)) before++
        assertTrue("the slow viewer got all $before frames instead of skipping", before < 1 + HEAVY_FRAMES)
        assertArrayEquals(DELTA, slowInput.video())
    }

    @Test
    fun `a stalled reader does not keep asking for keyframes while one is in the backlog`() {
        val (slowSocket, _) = connect(window = 4096)
        hub.resize(CapturePacket.Size(1280, 720))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.add(WebViewer(slowSocket, hub::remove))
        val heavy = ByteArray(128 * 1024).also { DELTA.copyInto(it) }
        repeat(HEAVY_FRAMES) { hub.frame(heavy) }
        hub.frame(KEY)
        val asked = keyframeRequests
        repeat(6) {
            Thread.sleep(100)
            hub.frame(DELTA)
        }
        assertEquals(asked, keyframeRequests)
    }

    @Test
    fun `a notice the browser has not read yet survives a resize`() {
        val (socket, input) = connect(window = 4096)
        hub.resize(CapturePacket.Size(1280, 720))
        hub.config(CONFIG)
        hub.frame(KEY)
        hub.add(WebViewer(socket, hub::remove))
        assertEquals(WebViewer.TAG_CONFIG.toInt(), input.record().first)
        assertArrayEquals(KEY, input.video())

        hub.frame(ByteArray(256 * 1024).also { DELTA.copyInto(it) })
        val length = input.readInt()
        assertEquals(WebViewer.TAG_VIDEO.toInt(), input.readUnsignedByte())
        hub.notice(WebViewer.NOTICE_LIVE)
        hub.resize(CapturePacket.Size(1920, 1080))
        hub.config(CONFIG)
        hub.frame(KEY)
        input.readFully(ByteArray(length - 1))

        val (type, live) = input.record()
        assertEquals(WebViewer.TAG_NOTICE.toInt(), type)
        assertArrayEquals(byteArrayOf(WebViewer.NOTICE_LIVE), live)
        val (configType, config) = input.record()
        assertEquals(WebViewer.TAG_CONFIG.toInt(), configType)
        assertEquals(1920, config.int(0))
    }

    private fun ByteArray.int(at: Int): Int = ByteBuffer.wrap(this, at, 4).int

    private companion object {
        const val HEAVY_FRAMES = 96
        val CONFIG = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1F, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val KEY = byteArrayOf(0, 0, 0, 1, 0x09, 0x10, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        val DELTA = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x02)
    }
}
