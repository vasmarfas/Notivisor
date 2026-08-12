package com.vasmarfas.notivisor.core.link

import android.content.Context
import android.os.Build
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.WireCodec
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.LinkRole
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.NotificationTransport
import com.vasmarfas.notivisor.core.transport.TransportFactory
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

data class LinkStats(
    val sent: Long = 0,
    val queued: Int = 0,
    val dropped: Long = 0,
    val received: Long = 0,
    val acked: Long = 0,
    val reconnects: Int = 0,
    val lastRttMs: Long? = null,
    val connectedSince: Long? = null,
)

class LinkManager(
    context: Context,
    private val settings: BridgeSettings,
    private val role: LinkRole,
) {

    private val appContext = context.applicationContext
    private val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".take(20)

    private val _state = MutableStateFlow<LinkState>(LinkState.Stopped)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)

    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val _stats = MutableStateFlow(LinkStats())
    val stats: StateFlow<LinkStats> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queueMutex = Mutex()
    private val queue = ArrayDeque<Pending>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    private var transport: NotificationTransport? = null
    private var jobs = mutableListOf<Job>()
    private var seq = 0L

    @Volatile
    private var lastTrafficAt = 0L

    @Volatile
    private var pendingPingAt = 0L

    @Volatile
    private var lastPeerSeq = 0L

    @Volatile
    private var peerVerified = false

    @Volatile
    private var transportConnectedAt = 0L

    private var transportState: LinkState = LinkState.Stopped

    private var codec: WireCodec = WireCodec(null)

    val transportKind get() = transport?.kind

    fun start() {
        stopInternal()
        val config = settings.transportConfig(role)
        codec = WireCodec(settings.sessionKey())
        BridgeLog.i(
            SCOPE,
            "starting ${config.kind} as $role" +
                    (if (config.kind.name == "BLE") ", gatt server = ${if (config.isBleServer) "us" else "peer"}" else "") +
                    ", encryption ${if (codec.encrypted) "on" else "OFF"}"
        )

        val created = TransportFactory.create(appContext, config, { codec }, deviceLabel)
        transport = created

        jobs += scope.launch {
            created.state.collect { state ->
                val previous = transportState
                transportState = state
                if (state is LinkState.Connected && previous !is LinkState.Connected) onConnected(
                    state
                )
                if (state !is LinkState.Connected && previous is LinkState.Connected) onDisconnected()
                publishState()
            }
        }
        jobs += scope.launch { created.incoming.collect(::onEnvelope) }
        jobs += scope.launch { senderLoop() }
        jobs += scope.launch { keepaliveLoop() }

        created.start()
    }

    fun stop() {
        stopInternal()
        _state.value = LinkState.Stopped
    }

    fun restart(reason: String) {
        BridgeLog.i(SCOPE, "restarting link: $reason")
        _stats.value = _stats.value.copy(reconnects = _stats.value.reconnects + 1)
        start()
    }

    private fun stopInternal() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        transport?.stop()
        transport = null
    }

    fun enqueue(envelope: Envelope) {
        scope.launch {
            queueMutex.withLock {
                purgeLocked()
                if (queue.size >= MAX_QUEUE) {
                    queue.removeFirst()
                    _stats.value = _stats.value.copy(dropped = _stats.value.dropped + 1)
                    BridgeLog.w(SCOPE, "queue full, dropped the oldest message")
                }
                queue.addLast(Pending(envelope.copy(seq = ++seq), System.currentTimeMillis()))
                _stats.value = _stats.value.copy(queued = queue.size)
            }
            wakeup.trySend(Unit)
        }
    }

    fun sendDirect(envelope: Envelope) {
        scope.launch { transport?.send(envelope) }
    }

    private fun purgeLocked() {
        val deadline = System.currentTimeMillis() - TTL_MS
        var removed = 0
        while (queue.isNotEmpty() && queue.first().createdAt < deadline) {
            queue.removeFirst()
            removed++
        }
        if (removed > 0) {
            _stats.value =
                _stats.value.copy(dropped = _stats.value.dropped + removed, queued = queue.size)
            BridgeLog.w(SCOPE, "dropped $removed message(s) older than ${TTL_MS / 1000} s")
        }
    }

    private suspend fun senderLoop() {
        while (scope.isActive) {
            wakeup.receive()
            while (scope.isActive) {
                val head = queueMutex.withLock {
                    purgeLocked()
                    queue.firstOrNull()
                } ?: break

                if (!_state.value.isConnected) break

                val ok = transport?.send(head.envelope) ?: false
                if (!ok) {
                    delay(500)
                    break
                }
                queueMutex.withLock {
                    if (queue.firstOrNull() === head) queue.removeFirst()
                    _stats.value =
                        _stats.value.copy(sent = _stats.value.sent + 1, queued = queue.size)
                }
            }
        }
    }

    private suspend fun keepaliveLoop() {
        while (scope.isActive) {
            delay(PING_INTERVAL_MS)

            if (transportState is LinkState.Connected && !peerVerified) {
                if (handshakeExpired()) {
                    publishState()
                    BridgeLog.w(
                        SCOPE,
                        "peer connected but nothing decodes — check the pairing code"
                    )
                    delay(PAIRING_RETRY_MS)
                    restart("retrying after a pairing mismatch")
                }
                continue
            }

            if (!_state.value.isConnected) continue

            if (role == LinkRole.SOURCE) {
                pendingPingAt = System.currentTimeMillis()
                val ok = transport?.send(Envelope.ping(++seq)) ?: false
                if (!ok) {
                    restart("keepalive could not be written")
                    continue
                }
            }

            val silence = System.currentTimeMillis() - lastTrafficAt
            if (lastTrafficAt == 0L || silence <= SILENCE_LIMIT_MS) continue

            val probeSent = transport?.send(Envelope.ping(++seq)) ?: false
            if (!probeSent) {
                restart("silent for ${silence / 1000} s and the probe could not be written")
                continue
            }
            delay(PROBE_GRACE_MS)
            if (System.currentTimeMillis() - lastTrafficAt > SILENCE_LIMIT_MS) {
                restart("silent for ${silence / 1000} s, no answer to the probe")
            } else {
                BridgeLog.d(SCOPE, "peer was quiet for ${silence / 1000} s but answered the probe")
            }
        }
    }

    private suspend fun onEnvelope(envelope: Envelope) {
        lastTrafficAt = System.currentTimeMillis()
        _stats.value = _stats.value.copy(received = _stats.value.received + 1)

        if (!peerVerified) {
            peerVerified = true
            BridgeLog.i(SCOPE, "peer verified, link is usable")
            publishState()
            wakeup.trySend(Unit)
        }

        if (envelope.action == Action.POST || envelope.action == Action.REMOVE) {
            if (envelope.seq != 0L && envelope.seq <= lastPeerSeq) {
                BridgeLog.w(SCOPE, "dropping replayed seq=${envelope.seq} (last was $lastPeerSeq)")
                return
            }
            lastPeerSeq = envelope.seq
        }

        when (envelope.action) {
            Action.PING -> transport?.send(Envelope.pong(envelope.seq))

            Action.PONG -> {
                if (pendingPingAt != 0L) {
                    val rtt = System.currentTimeMillis() - pendingPingAt
                    _stats.value = _stats.value.copy(lastRttMs = rtt)
                    pendingPingAt = 0L
                }
            }

            Action.ACK -> _stats.value = _stats.value.copy(acked = _stats.value.acked + 1)

            Action.HELLO -> {
                lastPeerSeq = 0L
                BridgeLog.i(SCOPE, "peer says hello: ${envelope.app} (${envelope.pkg})")
            }

            else -> _incoming.emit(envelope)
        }
    }

    private fun publishState() {
        val transport = transportState
        _state.value = when {
            transport !is LinkState.Connected -> transport
            peerVerified -> transport
            handshakeExpired() -> LinkState.Failed("pairing code mismatch", pairing = true)
            else -> LinkState.Connecting(transport.peer)
        }
    }

    private fun handshakeExpired(): Boolean =
        transportConnectedAt != 0L &&
                System.currentTimeMillis() - transportConnectedAt > HANDSHAKE_TIMEOUT_MS

    private fun onConnected(state: LinkState.Connected) {
        transportConnectedAt = System.currentTimeMillis()
        lastTrafficAt = System.currentTimeMillis()
        _stats.value = _stats.value.copy(connectedSince = state.since)
        BridgeLog.i(SCOPE, "link up with ${state.peer}")
        scope.launch {
            transport?.send(Envelope.hello(role.name.lowercase(), deviceLabel))
            wakeup.trySend(Unit)
        }
    }

    private fun onDisconnected() {
        _stats.value = _stats.value.copy(connectedSince = null)
        pendingPingAt = 0L
        peerVerified = false
        transportConnectedAt = 0L
        BridgeLog.w(SCOPE, "link down")
    }

    private data class Pending(val envelope: Envelope, val createdAt: Long)

    private companion object {
        const val SCOPE = "link"
        const val PING_INTERVAL_MS = 15_000L

        const val SILENCE_LIMIT_MS = 50_000L
        const val PROBE_GRACE_MS = 5_000L

        const val HANDSHAKE_TIMEOUT_MS = 12_000L
        const val PAIRING_RETRY_MS = 60_000L
        const val TTL_MS = 5 * 60_000L
        const val MAX_QUEUE = 50
    }
}
