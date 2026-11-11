package com.vasmarfas.notivisor.core.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.vasmarfas.notivisor.core.util.BridgeLog
import dadb.Dadb
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.Certificate

enum class AdbAccess { READY, UNAUTHORIZED, OFFLINE }

class AdbConnection private constructor(private val material: AdbIdentity.Material) : AbsAdbConnectionManager() {

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
        private const val WIRELESS_SETTING = "adb_wifi_enabled"

        private var instances: List<AdbConnection>? = null

        @Volatile
        private var grantRefused = false

        @Synchronized
        private fun managers(context: Context): List<AdbConnection> =
            instances ?: AdbIdentity.identities(context.applicationContext)
                .map(::AdbConnection)
                .also { instances = it }

        fun pair(context: Context, code: String): Boolean {
            val service = AdbMdns.discover(context, AdbMdns.TLS_PAIRING, PAIRING_DISCOVERY_MS)
            if (service == null) {
                BridgeLog.w(SCOPE, "no pairing service on the network; is that screen still open?")
                return false
            }
            val host = service.host.hostAddress ?: return false
            return runCatching {
                managers(context).first().pair(host, service.port, code)
            }.onFailure {
                BridgeLog.w(SCOPE, "pairing rejected: ${it.message}")
            }.getOrDefault(false).also {
                BridgeLog.i(SCOPE, "pairing ${if (it) "accepted" else "refused"}")
            }
        }

        fun resolvePort(context: Context, wake: Boolean = true): Int? {

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !wake) {
                return TCPIP_PORT.takeIf { isAlive(it) }
            }
            if (isAlive(TCPIP_PORT)) return TCPIP_PORT
            enableWireless(context)
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

        fun access(context: Context, wake: Boolean = true): AdbAccess {
            val port = resolvePort(context, wake) ?: return AdbAccess.OFFLINE
            return runCatching {
                Dadb.create(LOOPBACK, port, AdbIdentity.keyPair(context), PROBE_TIMEOUT_MS, AUTH_TIMEOUT_MS).use {
                    it.shell("true")
                    keep(context, it)
                }
                AdbAccess.READY
            }.getOrElse {
                BridgeLog.w(SCOPE, "adbd did not accept our key: ${it.message}")
                AdbAccess.UNAUTHORIZED
            }
        }

        fun open(context: Context, timeoutMs: Int): Dadb =
            Dadb.create(LOOPBACK, TCPIP_PORT, AdbIdentity.keyPair(context), PROBE_TIMEOUT_MS, timeoutMs)

        fun keep(context: Context, shell: Dadb) {
            if (grantRefused || canEnableWireless(context)) return
            val result = runCatching {
                shell.shell("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
            }.getOrNull() ?: return
            if (result.exitCode == 0) {
                BridgeLog.i(SCOPE, "wireless debugging can now be switched on without a computer")
            } else {
                grantRefused = true
            }
        }

        fun canEnableWireless(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        private fun enableWireless(context: Context) {
            if (!canEnableWireless(context)) return
            val resolver = context.contentResolver
            if (Settings.Global.getInt(resolver, WIRELESS_SETTING, 0) == 1) return
            runCatching { Settings.Global.putInt(resolver, WIRELESS_SETTING, 1) }
                .onSuccess { BridgeLog.i(SCOPE, "switched wireless debugging on") }
                .onFailure { BridgeLog.w(SCOPE, "could not switch wireless debugging on: ${it.message}") }
        }

        private fun switchToTcpip(context: Context): Boolean {
            val service = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, AUTO_CONNECT_MS) ?: run {
                BridgeLog.w(SCOPE, "wireless debugging is not advertised on this device")
                return false
            }
            return managers(context).any { manager ->
                runCatching { manager.switchToTcpip(service.port) }
                    .onFailure { BridgeLog.w(SCOPE, "tls connect failed, is the key known? $it") }
                    .getOrDefault(false)
            }
        }

        private fun AdbConnection.switchToTcpip(port: Int): Boolean {
            if (!connect(LOOPBACK, port)) return false
            BridgeLog.i(SCOPE, "asking adbd for tcpip:$TCPIP_PORT over tls")
            val stream = openStream("tcpip:$TCPIP_PORT")
            runCatching { stream.openInputStream().readBytes() }
            runCatching { stream.close() }
            runCatching { disconnect() }
            return true
        }

        private fun isAlive(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)

        private const val AUTO_CONNECT_MS = 8_000L
        private const val PAIRING_DISCOVERY_MS = 8_000L
        private const val AUTH_TIMEOUT_MS = 20_000
    }
}
