package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

abstract class CastViewer(private val socket: Socket, private val onClosed: (CastViewer) -> Unit) {

    var outgrown = false
        internal set

    open val survivesResize = true
    open val inBrowser = false

    internal var cursor = 0L
    internal var synced = false
    internal var config: Config? = null
    internal var pending: Piece? = null

    private val closed = AtomicBoolean()

    @Volatile
    private var sender: Thread? = null

    internal fun start(hub: CastHub) {
        val thread = Thread({ serve(hub) }, "cast-viewer").apply { isDaemon = true }
        sender = thread
        thread.start()
    }

    internal fun wake() {
        sender?.let(LockSupport::unpark)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        wake()
        onClosed(this)
    }

    internal abstract fun write(out: DataOutputStream, piece: Piece)

    private fun serve(hub: CastHub) {
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
            while (!closed.get()) {
                val piece = hub.next(this)
                if (piece != null) {
                    write(out, piece)
                } else {
                    out.flush()
                    LockSupport.park(this)
                }
            }
        } catch (broken: IOException) {
            if (!closed.get()) BridgeLog.d(SCOPE, "${socket.inetAddress?.hostAddress} stopped taking the stream: ${broken.message}")
        } finally {
            close()
        }
    }

    private companion object {
        const val SCOPE = "castview"
        const val BUFFER_BYTES = 128 shl 10
    }
}

class PhoneViewer(socket: Socket, onClosed: (CastViewer) -> Unit) : CastViewer(socket, onClosed) {

    override val survivesResize = false

    private var headed = false

    override fun write(out: DataOutputStream, piece: Piece) {
        if (piece is Config && !headed) {
            out.writeInt(piece.size.width)
            out.writeInt(piece.size.height)
            headed = true
        }
        out.writeInt(if (piece.type == WebViewer.TAG_SOUND) piece.bytes.size or Int.MIN_VALUE else piece.bytes.size)
        out.write(piece.bytes)
    }
}

class WebViewer(socket: Socket, onClosed: (CastViewer) -> Unit) : CastViewer(socket, onClosed) {

    override val inBrowser = true

    override fun write(out: DataOutputStream, piece: Piece) {
        when {
            piece is Config -> {
                out.writeInt(1 + 2 * Int.SIZE_BYTES + piece.bytes.size)
                out.writeByte(piece.type.toInt())
                out.writeInt(piece.size.width)
                out.writeInt(piece.size.height)
            }

            piece.type == TAG_NOTICE -> {
                out.writeInt(1 + piece.bytes.size)
                out.writeByte(piece.type.toInt())
            }

            else -> {
                out.writeInt(1 + Long.SIZE_BYTES + piece.bytes.size)
                out.writeByte(piece.type.toInt())
                out.writeLong(piece.stamp)
            }
        }
        out.write(piece.bytes)
    }

    companion object {
        const val TAG_CONFIG = 'C'.code.toByte()
        const val TAG_VIDEO = 'V'.code.toByte()
        const val TAG_SOUND = 'S'.code.toByte()
        const val TAG_NOTICE = 'N'.code.toByte()

        const val NOTICE_LIVE: Byte = 0
        const val NOTICE_STARTING: Byte = 1
        const val NOTICE_TROUBLE: Byte = 2
    }
}
