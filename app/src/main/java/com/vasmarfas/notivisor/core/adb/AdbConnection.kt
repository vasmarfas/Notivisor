package com.vasmarfas.notivisor.core.adb

import android.content.Context
import android.os.Build
import com.vasmarfas.notivisor.core.util.BridgeLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.Certificate

class AdbConnection private constructor(context: Context) : AbsAdbConnectionManager() {

    private val material = AdbIdentity.tlsMaterial(context)

    init {
        api = Build.VERSION.SDK_INT
        hostAddress = LOOPBACK
    }

    override fun getPrivateKey(): PrivateKey = material.privateKey

    override fun getCertificate(): Certificate = material.certificate

    override fun getDeviceName(): String = "Notivisor"

    companion object {
        private const val SCOPE = "adb"
        private const val LOOPBACK = "127.0.0.1"
        private const val TCPIP_PORT = 5555
        private const val PROBE_TIMEOUT_MS = 600
        private const val WAIT_TRIES = 20
        private const val WAIT_STEP_MS = 250L

        @Volatile
        private var instance: AdbConnection? = null

        @Synchronized
        fun getInstance(context: Context): AdbConnection =
            instance ?: AdbConnection(context.applicationContext).also { instance = it }

        fun pair(context: Context, code: String): Boolean {
            val service = AdbMdns.discover(context, AdbMdns.TLS_PAIRING, PAIRING_DISCOVERY_MS)
            if (service == null) {
                BridgeLog.w(SCOPE, "no pairing service on the network; is that screen still open?")
                return false
            }
            val host = service.host.hostAddress ?: return false
            return runCatching {
                getInstance(context).pair(host, service.port, code)
            }.onFailure {
                BridgeLog.w(SCOPE, "pairing rejected: ${it.message}")
            }.getOrDefault(false).also {
                BridgeLog.i(SCOPE, "pairing ${if (it) "accepted" else "refused"}")
            }
        }

        fun resolvePort(context: Context): Int? {

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return TCPIP_PORT.takeIf { isAlive(it) }
            }
            if (isAlive(TCPIP_PORT)) return TCPIP_PORT
            if (!switchToTcpip(context)) return null
            repeat(WAIT_TRIES) {
                if (isAlive(TCPIP_PORT)) {
                    BridgeLog.i(SCOPE, "adbd now listening on $TCPIP_PORT")
                    return TCPIP_PORT
                }
                runCatching { Thread.sleep(WAIT_STEP_MS) }.onFailure { return null }
            }
            BridgeLog.w(SCOPE, "asked for tcpip:$TCPIP_PORT but the port never came up")
            return null
        }

        private fun switchToTcpip(context: Context): Boolean = runCatching {
            val manager = getInstance(context)
            if (!manager.isConnected) {
                val connected = runCatching { manager.autoConnect(context, AUTO_CONNECT_MS) }
                    .getOrElse {
                        BridgeLog.w(SCOPE, "tls connect failed, is it paired? ${it.message}")
                        false
                    }
                if (!connected) return false
            }
            BridgeLog.i(SCOPE, "asking adbd for tcpip:$TCPIP_PORT over tls")
            val stream = manager.openStream("tcpip:$TCPIP_PORT")
            runCatching { stream.openInputStream().readBytes() }
            runCatching { stream.close() }
            runCatching { manager.disconnect() }
            true
        }.getOrElse {
            BridgeLog.w(SCOPE, "tcpip switch failed: ${it.message}")
            false
        }

        private fun isAlive(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)

        private const val AUTO_CONNECT_MS = 8_000L
        private const val PAIRING_DISCOVERY_MS = 8_000L
    }
}
