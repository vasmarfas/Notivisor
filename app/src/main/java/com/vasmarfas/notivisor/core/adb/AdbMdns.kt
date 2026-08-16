package com.vasmarfas.notivisor.core.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object AdbMdns {

    private const val SCOPE = "adb"

    const val TLS_CONNECT = "_adb-tls-connect._tcp"

    const val TLS_PAIRING = "_adb-tls-pairing._tcp"

    data class Service(val host: InetAddress, val port: Int)

    fun discover(context: Context, serviceType: String, timeoutMs: Long = 5_000): Service? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val result = AtomicReference<Service?>(null)
        val latch = CountDownLatch(1)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = serviceInfo.host ?: return
                val port = serviceInfo.port
                if (port > 0 && result.compareAndSet(null, Service(host, port))) latch.countDown()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                BridgeLog.w(SCOPE, "mdns discovery failed: $errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching { nsd.resolveService(serviceInfo, resolveListener) }
            }
        }

        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: Exception) {
            BridgeLog.w(SCOPE, "mdns error: ${e.message}")
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }
}
