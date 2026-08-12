package com.vasmarfas.notivisor.core.transport

import com.vasmarfas.notivisor.core.protocol.Envelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TransportKind { BLE, TCP }

enum class LinkRole { SOURCE, SINK }

sealed interface LinkState {
    data object Stopped : LinkState
    data object Starting : LinkState

    data class Waiting(val detail: String) : LinkState
    data class Connecting(val detail: String) : LinkState
    data class Connected(val peer: String, val since: Long) : LinkState
    data class Failed(val reason: String, val pairing: Boolean = false) : LinkState

    val isConnected: Boolean get() = this is Connected

    fun describe(): String = when (this) {
        is Stopped -> "stopped"
        is Starting -> "starting"
        is Waiting -> "waiting: $detail"
        is Connecting -> "connecting: $detail"
        is Connected -> "connected to $peer"
        is Failed -> "failed: $reason"
    }
}

data class TransportConfig(
    val kind: TransportKind,
    val role: LinkRole,
    val bleServerIsSource: Boolean = true,
    val tcpPort: Int = DEFAULT_TCP_PORT,
    val tcpHost: String? = null,
) {
    val isBleServer: Boolean
        get() = kind == TransportKind.BLE && (role == LinkRole.SOURCE) == bleServerIsSource

    companion object {
        const val DEFAULT_TCP_PORT = 47820
    }
}

interface NotificationTransport {
    val kind: TransportKind
    val state: StateFlow<LinkState>
    val incoming: Flow<Envelope>

    fun start()
    fun stop()

    suspend fun send(envelope: Envelope): Boolean
}
