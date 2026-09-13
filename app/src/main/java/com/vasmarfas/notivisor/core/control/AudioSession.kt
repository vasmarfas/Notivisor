package com.vasmarfas.notivisor.core.control

import android.content.Context
import com.vasmarfas.notivisor.core.adb.AdbConnection
import com.vasmarfas.notivisor.core.adb.AdbIdentity
import com.vasmarfas.notivisor.core.util.BridgeLog
import dadb.AdbStream
import dadb.Dadb
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioSession {

    private var dadb: Dadb? = null
    private var shell: AdbStream? = null
    private var stream: AdbStream? = null

    @Volatile
    private var input: InputStream? = null

    @Synchronized
    fun start(context: Context): Boolean = runCatching {
        close()
        val port = AdbConnection.resolvePort(context) ?: run {
            BridgeLog.w(SCOPE, "no reachable adb port; wireless debugging off or unpaired")
            return false
        }
        val version = ScrcpyServer.version(context) ?: run {
            BridgeLog.w(SCOPE, "could not read the bundled server's version")
            return false
        }
        val connection = Dadb.create("127.0.0.1", port, AdbIdentity.keyPair(context))
        dadb = connection

        val scid = ScrcpyServer.scid()
        shell = ScrcpyServer.launch(context, connection, version, scid, OPTIONS)
        val socket = ScrcpyServer.open(connection, scid) ?: return false
        stream = socket

        val source = socket.source.inputStream()
        readFully(source, ByteArray(DEVICE_META))
        val header = ByteArray(4).also { readFully(source, it) }
        val codec = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (codec != CODEC_RAW) {
            BridgeLog.w(SCOPE, "the device refused to capture audio, codec id 0x${codec.toString(16)}")
            close()
            return false
        }
        input = source
        BridgeLog.i(SCOPE, "capturing $SAMPLE_RATE Hz stereo")
        true
    }.getOrElse {
        BridgeLog.w(SCOPE, "audio session failed: ${it.message}")
        close()
        false
    }

    fun read(): ByteArray? {
        val source = input ?: return null
        return runCatching {
            val header = ByteArray(PACKET_HEADER).also { readFully(source, it) }
            val length = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length <= 0 || length > MAX_CHUNK_BYTES) {
                BridgeLog.w(SCOPE, "bogus audio chunk $length, stream is out of sync")
                return null
            }
            ByteArray(length).also { readFully(source, it) }
        }.getOrNull()
    }

    @Synchronized
    fun close() {
        input = null
        runCatching { stream?.close() }
        runCatching { shell?.close() }
        runCatching { dadb?.close() }
        stream = null
        shell = null
        dadb = null
    }

    private fun readFully(source: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val count = source.read(buffer, read, buffer.size - read)
            if (count < 0) throw EOFException("stream ended after $read of ${buffer.size} B")
            read += count
        }
    }

    const val SAMPLE_RATE = 48_000

    private const val SCOPE = "castaudio"
    private const val OPTIONS = "video=false audio=true audio_codec=raw control=false"
    private const val DEVICE_META = 64
    private const val PACKET_HEADER = 12
    private const val MAX_CHUNK_BYTES = 1 shl 16
    private const val CODEC_RAW = 0x00726177
}
