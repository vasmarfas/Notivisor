package com.vasmarfas.notivisor.core.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class NsdHelper(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun register(port: Int) {
        val manager = nsd ?: return
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                BridgeLog.i(SCOPE, "registered ${info.serviceName} on :$port")

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                BridgeLog.w(SCOPE, "registration failed, error $errorCode")

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                registration = null
                BridgeLog.w(SCOPE, "registerService threw: ${it.message}")
            }
    }

    suspend fun discoverHost(): String? {
        val manager = nsd ?: return null
        val result = CompletableDeferred<String?>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = BridgeLog.d(SCOPE, "discovery started")

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.startsWith(SERVICE_TYPE.take(14)) != true) return
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) =
                        BridgeLog.w(SCOPE, "resolve failed, error $errorCode")

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress
                        BridgeLog.i(SCOPE, "resolved ${info.serviceName} -> $host")
                        if (host != null) result.complete(host)
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                BridgeLog.w(SCOPE, "discovery unavailable, error $errorCode")
                result.complete(null)
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
        }

        discovery = listener
        val started = runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess
        if (!started) {
            discovery = null
            return null
        }

        return try {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { result.await() }
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            discovery = null
        }
    }

    fun stop() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
    }

    private companion object {
        const val SCOPE = "nsd"
        const val SERVICE_TYPE = "_Notivisor._tcp."
        const val SERVICE_NAME = "Notivisor"
        const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
