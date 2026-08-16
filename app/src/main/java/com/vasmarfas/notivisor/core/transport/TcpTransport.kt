package com.vasmarfas.notivisor.core.transport

import android.content.Context
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.WireCodec
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds

class TcpTransport(
    context: Context,
    private val config: TransportConfig,
    private val codecProvider: () -> WireCodec,
) : NotificationTransport {

    override val kind = TransportKind.TCP

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<LinkState>(LinkState.Stopped)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val nsd = NsdHelper(appContext)

    override val maxFrameBytes = 1 shl 20

    private var job: Job? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var link: Link? = null

    override fun start() {
        if (job?.isActive == true) return
        _state.value = LinkState.Starting
        job = scope.launch {
            if (config.role == LinkRole.SOURCE) runServer() else runClient()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        nsd.stop()
        runCatching { serverSocket?.close() }
        serverSocket = null
        link?.close()
        link = null
        _state.value = LinkState.Stopped
    }

    override suspend fun send(envelope: Envelope): Boolean {
        val current = link ?: return false
        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching { current.write(codecProvider().encode(envelope)) }
                    .onFailure {
                        BridgeLog.w(SCOPE, "write failed, dropping link: ${it.message}")
                        current.close()
                    }
                    .isSuccess
            }
        }
    }

    private suspend fun runServer() {
        while (scope.isActive) {
            try {
                val server = withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(config.tcpPort))
                    }
                }
                serverSocket = server
                nsd.register(config.tcpPort)
                _state.value = LinkState.Waiting("listening on :${config.tcpPort}")
                BridgeLog.i(SCOPE, "listening on 0.0.0.0:${config.tcpPort}")

                while (scope.isActive) {
                    val socket = withContext(Dispatchers.IO) { server.accept() }
                    link?.close()

                    BridgeLog.i(SCOPE, "accepted ${socket.inetAddress.hostAddress}")
                    serve(socket)
                    if (scope.isActive) _state.value =
                        LinkState.Waiting("listening on :${config.tcpPort}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scope.isActive) return
                BridgeLog.e(SCOPE, "server loop failed", e)
                _state.value = LinkState.Failed(e.message ?: e.javaClass.simpleName)
                runCatching { serverSocket?.close() }
                delay(3_000.milliseconds)
            }
        }
    }

    private suspend fun runClient() {
        var backoff = 1_000L
        while (scope.isActive) {
            val host = config.tcpHost ?: nsd.discoverHost()
            if (host == null) {
                _state.value = LinkState.Waiting("no host configured, discovering")
                delay(5_000.milliseconds)
                continue
            }
            try {
                _state.value = LinkState.Connecting("$host:${config.tcpPort}")
                val socket = Socket()
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(host, config.tcpPort), CONNECT_TIMEOUT_MS)
                }
                BridgeLog.i(SCOPE, "connected to $host:${config.tcpPort}")
                backoff = 1_000L
                serve(socket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scope.isActive) return
                BridgeLog.w(SCOPE, "connect to $host failed: ${e.message}")
                _state.value = LinkState.Failed(e.message ?: "connect failed")
            }
            delay(backoff.milliseconds)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun serve(socket: Socket) {
        val current = Link(socket)
        link = current
        _state.value = LinkState.Connected(
            peer = socket.inetAddress?.hostAddress ?: "peer",
            since = System.currentTimeMillis(),
        )
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val codec = codecProvider()
            while (scope.isActive) {
                val line = withContext(Dispatchers.IO) { current.reader.readLine() } ?: break
                if (line.isBlank()) continue
                try {
                    _incoming.emit(codec.decode(line))
                } catch (e: WireCodec.ProtocolMismatch) {
                    BridgeLog.w(SCOPE, "rejected frame: ${e.message}")
                } catch (e: Exception) {
                    BridgeLog.w(SCOPE, "undecodable frame (${line.length} B): ${e.message}")
                }
            }
        } catch (e: SocketTimeoutException) {
            BridgeLog.w(SCOPE, "read timeout, peer went quiet for ${READ_TIMEOUT_MS / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (scope.isActive) BridgeLog.w(SCOPE, "read loop ended: ${e.message}")
        } finally {
            current.close()
            if (link === current) link = null
        }
    }

    private class Link(private val socket: Socket) {
        val reader: BufferedReader =
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer: BufferedWriter =
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        fun write(line: String) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val SCOPE = "tcp"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 45_000
        const val MAX_BACKOFF_MS = 30_000L
    }
}
