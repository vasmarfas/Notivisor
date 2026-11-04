package com.vasmarfas.notivisor.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class FileStreamTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val stream = FileStream(key, "transfer")

    private fun sealed(content: ByteArray): ByteArray =
        ByteArrayOutputStream().also { stream.send(ByteArrayInputStream(content), it) }.toByteArray()

    private fun received(wire: ByteArray, from: FileStream = stream): ByteArray =
        ByteArrayOutputStream().also { from.receive(ByteArrayInputStream(wire), it) }.toByteArray()

    @Test
    fun `content over several chunks arrives intact`() {
        val content = Random(1).nextBytes(200_000)
        assertArrayEquals(content, received(sealed(content)))
    }

    @Test
    fun `empty file arrives empty`() {
        assertEquals(0, received(sealed(ByteArray(0))).size)
    }

    @Test
    fun `changed byte is rejected`() {
        val wire = sealed(Random(2).nextBytes(1_000))
        wire[100] = (wire[100] + 1).toByte()
        assertThrows(Exception::class.java) { received(wire) }
    }

    @Test
    fun `stream without the last frame is rejected`() {
        val content = Random(3).nextBytes(200_000)
        val wire = sealed(content)
        assertThrows(IOException::class.java) { received(wire.copyOf(wire.size - 40)) }
    }

    @Test
    fun `stream of another transfer is rejected`() {
        val wire = sealed(Random(4).nextBytes(1_000))
        assertThrows(Exception::class.java) { received(wire, FileStream(key, "other")) }
    }

    @Test
    fun `frame length outside the limits is rejected before reading it`() {
        val wire = ByteArrayOutputStream().also {
            DataOutputStream(it).run {
                writeBoolean(false)
                writeInt(Int.MAX_VALUE)
            }
        }.toByteArray()
        assertThrows(IOException::class.java) { received(wire) }
    }

    @Test
    fun `names lose paths, control characters and leading dots`() {
        assertEquals("passwd", FileStream.safeName("../../etc/passwd"))
        assertEquals("evil.exe", FileStream.safeName("C:\\temp\\evil.exe"))
        assertEquals("hidden", FileStream.safeName(".hidden"))
        assertEquals("ab.txt", FileStream.safeName("a\nb.txt"))
        assertEquals("file", FileStream.safeName(" / "))
        assertEquals("file", FileStream.safeName(null))
    }

    @Test
    fun `long name keeps its extension`() {
        val name = FileStream.safeName("x".repeat(300) + ".jpg")
        assertEquals(120, name.length)
        assertEquals("jpg", name.substringAfterLast('.'))
    }

    @Test
    fun `malformed mime falls back to binary`() {
        assertEquals("image/jpeg", FileStream.safeMime("image/jpeg"))
        assertEquals("application/octet-stream", FileStream.safeMime("not a type"))
        assertEquals("application/octet-stream", FileStream.safeMime(null))
    }
}
