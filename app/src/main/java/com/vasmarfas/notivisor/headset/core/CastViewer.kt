package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.control.CapturePacket
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

abstract class CastViewer(
    private val socket: Socket,
    private val onClosed: (CastViewer) -> Unit,
) {
    @Volatile
    var awaitingKeyframe = true

    @Volatile
    var outgrown = false

    open val survivesResize = true
    open val inBrowser = false

    private val queue = LinkedBlockingQueue<Chunk>()
    private val queued = AtomicInteger()
    private val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES)
    private val writer = Thread(::drain, "cast-viewer").apply { isDaemon = true }

    @Volatile
    private var closed = false

    abstract fun begin(size: CapturePacket.Size)
    abstract fun config(bytes: ByteArray, at: Long)
    abstract fun video(bytes: ByteArray, at: Long): Boolean
    abstract fun sound(bytes: ByteArray, at: Long)

    open fun notice(code: Byte, text: String) = Unit

    fun start() = writer.start()

    fun close() {
        if (closed) return
        closed = true
        writer.interrupt()
        runCatching { socket.close() }
    }

    protected fun offer(head: ByteArray, body: ByteArray, essential: Boolean): Boolean {
        if (closed) return true
        val size = head.size + body.size
        if (queued.get() + size > QUEUE_LIMIT_BYTES) {
            if (!essential) return true
            queue.clear()
            queued.set(0)
            awaitingKeyframe = true
            return false
        }
        queued.addAndGet(size)
        queue.put(Chunk(head, body))
        return true
    }

    private fun drain() {
        try {
            while (!closed) {
                val chunk = queue.take()
                output.write(chunk.head)
                output.write(chunk.body)
                queued.addAndGet(-(chunk.head.size + chunk.body.size))
                if (queue.isEmpty()) output.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            BridgeLog.i(SCOPE, "viewer ${socket.inetAddress?.hostAddress} left: ${e.message}")
        }
        close()
        onClosed(this)
    }

    private class Chunk(val head: ByteArray, val body: ByteArray)

    companion object {
        private const val SCOPE = "castviewer"
        private const val BUFFER_BYTES = 64 * 1024
        const val QUEUE_LIMIT_BYTES = 2 * 1024 * 1024

        val EMPTY = ByteArray(0)
    }
}

class PhoneViewer(socket: Socket, onClosed: (CastViewer) -> Unit) : CastViewer(socket, onClosed) {

    override val survivesResize = false

    @Volatile
    private var begun = false

    override fun begin(size: CapturePacket.Size) {
        if (begun) return
        begun = true
        offer(ByteBuffer.allocate(8).putInt(size.width).putInt(size.height).array(), EMPTY, essential = true)
    }

    override fun config(bytes: ByteArray, at: Long) {
        offer(length(bytes.size), bytes, essential = true)
    }

    override fun video(bytes: ByteArray, at: Long): Boolean = offer(length(bytes.size), bytes, essential = true)

    override fun sound(bytes: ByteArray, at: Long) {
        offer(length(bytes.size or SOUND_MARK), bytes, essential = false)
    }

    private fun length(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private companion object {
        const val SOUND_MARK = Int.MIN_VALUE
    }
}

class WebViewer(socket: Socket, onClosed: (CastViewer) -> Unit) : CastViewer(socket, onClosed) {

    override val inBrowser = true

    @Volatile
    private var size = CapturePacket.Size(0, 0)

    override fun begin(size: CapturePacket.Size) {
        this.size = size
    }

    override fun config(bytes: ByteArray, at: Long) {
        offer(head(TYPE_CONFIG, bytes.size).putInt(size.width).putInt(size.height).array(), bytes, essential = true)
    }

    override fun video(bytes: ByteArray, at: Long): Boolean =
        offer(head(TYPE_VIDEO, bytes.size).putLong(at).array(), bytes, essential = true)

    override fun sound(bytes: ByteArray, at: Long) {
        offer(head(TYPE_SOUND, bytes.size).putLong(at).array(), bytes, essential = false)
    }

    override fun notice(code: Byte, text: String) {
        val reason = text.toByteArray(Charsets.UTF_8)
        val record = ByteBuffer.allocate(6 + reason.size)
            .put(TYPE_NOTICE)
            .putInt(1 + reason.size)
            .put(code)
            .put(reason)
            .array()
        offer(record, EMPTY, essential = false)
    }

    private fun head(type: Byte, body: Int): ByteBuffer =
        ByteBuffer.allocate(HEAD_BYTES).put(type).putInt(body + 8)

    companion object {
        const val TYPE_CONFIG: Byte = 1
        const val TYPE_VIDEO: Byte = 2
        const val TYPE_SOUND: Byte = 3
        const val TYPE_NOTICE: Byte = 4
        const val HEAD_BYTES = 13

        const val NOTICE_LIVE: Byte = 0
        const val NOTICE_STARTING: Byte = 1
        const val NOTICE_TROUBLE: Byte = 2
    }
}
