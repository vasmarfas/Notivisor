package com.vasmarfas.notivisor.core.control

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

sealed interface MirrorState {
    data object Idle : MirrorState
    data object Connecting : MirrorState
    data class Connected(val width: Int, val height: Int, val frames: Long = 0) : MirrorState
    data class Failed(val reason: String) : MirrorState
}

class ScreenReceiver {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var server: ServerSocket? = null
    private var decoder: MediaCodec? = null
    private var thread: Thread? = null
    private val sender = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mirror-input").apply { isDaemon = true }
    }

    @Volatile
    private var frames = 0L

    @Volatile
    private var listening = false

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    fun start(host: String, port: Int, surface: Surface) {
        stop()
        _state.value = MirrorState.Connecting
        thread = Thread { runLoop(surface) { connect(host, port) } }
            .apply { isDaemon = true; start() }
    }

    fun listen(port: Int, surface: Surface) {
        stop()
        listening = true
        _state.value = MirrorState.Connecting
        thread = Thread { listenLoop(port, surface) }.apply { isDaemon = true; start() }
    }

    private fun runLoop(surface: Surface, open: () -> Socket) {
        runCatching { decode(open(), surface) }.onFailure {
            BridgeLog.w(SCOPE, "mirror stopped: ${it.message}")
            _state.value = MirrorState.Failed(it.message ?: "connection lost")
        }
    }

    private fun listenLoop(port: Int, surface: Surface) {
        val listener = runCatching { ServerSocket(port) }.getOrElse {
            BridgeLog.w(SCOPE, "could not open the streaming port: ${it.message}")
            _state.value = MirrorState.Failed(it.message ?: "port busy")
            return
        }
        server = listener
        BridgeLog.i(SCOPE, "waiting for a stream on $port")
        while (listening) {
            val client = runCatching { listener.accept() }.getOrNull() ?: break
            runCatching { decode(client, surface) }
                .onFailure { BridgeLog.i(SCOPE, "stream ended: ${it.message}") }
            releaseDecoder()
            runCatching { client.close() }
            socket = null
            if (listening) _state.value = MirrorState.Connecting
        }
        runCatching { listener.close() }
        server = null
    }

    private fun connect(host: String, port: Int): Socket {
        var lastError: Exception? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (_state.value !is MirrorState.Connecting) throw InterruptedException("cancelled")
            try {
                return Socket(host, port)
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < CONNECT_ATTEMPTS - 1) Thread.sleep(CONNECT_RETRY_MS)
        }
        throw lastError ?: IOException("could not connect")
    }

    private fun decode(sock: Socket, surface: Surface) {
        frames = 0
        socket = sock
        val input = DataInputStream(sock.getInputStream())
        val width = input.readInt()
        val height = input.readInt()
        BridgeLog.i(SCOPE, "mirror connected, ${width}x$height")

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createDecoderByType(MIME)
        codec.configure(format, surface, null, 0)
        codec.start()
        decoder = codec
        _state.value = MirrorState.Connected(width, height)

        val info = MediaCodec.BufferInfo()
        while (socket != null) {
            val length = input.readInt()
            val bytes = ByteArray(length)
            input.readFully(bytes)

            var inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            var waited = 0L
            while (inputIndex < 0 && waited < INPUT_WAIT_LIMIT_US) {
                inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                waited += DEQUEUE_TIMEOUT_US
            }
            if (inputIndex >= 0) {
                val buffer = codec.getInputBuffer(inputIndex)
                buffer?.put(bytes)

                val flags = if (isCodecConfig(bytes)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                codec.queueInputBuffer(inputIndex, 0, bytes.size, System.nanoTime() / 1000, flags)
            } else {
                BridgeLog.w(SCOPE, "decoder stalled, dropped a frame")
            }
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                frames++
                _state.value = MirrorState.Connected(width, height, frames)
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    fun send(bytes: ByteArray) {
        val sock = socket ?: return
        runCatching {
            sender.execute {
                runCatching {
                    synchronized(sock) {
                        sock.getOutputStream().apply {
                            write(bytes)
                            flush()
                        }
                    }
                }.onFailure { BridgeLog.w(SCOPE, "could not send input: $it") }
            }
        }
    }

    fun stop() {
        listening = false
        thread?.interrupt()
        thread = null
        releaseDecoder()
        runCatching { server?.close() }
        server = null
        runCatching { socket?.close() }
        socket = null
        _state.value = MirrorState.Idle
    }

    private fun releaseDecoder() {
        val current = decoder
        decoder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    private fun isCodecConfig(bytes: ByteArray): Boolean {
        val start = when {
            bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                    bytes[2] == 0.toByte() && bytes[3] == 1.toByte() -> 4

            bytes.size > 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                    bytes[2] == 1.toByte() -> 3

            else -> return false
        }
        return (bytes[start].toInt() and 0x1F) == H264.NAL_SPS
    }

    private companion object {
        const val SCOPE = "mirror"
        const val KEY_LOW_LATENCY = "low-latency"
        const val MIME = "video/avc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val INPUT_WAIT_LIMIT_US = 200_000L
        const val CONNECT_ATTEMPTS = 16
        const val CONNECT_RETRY_MS = 500L
    }
}
