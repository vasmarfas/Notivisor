package com.vasmarfas.notivisor.headset.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.headset.ui.HeadsetCamera

class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        HeadsetBridge.init(context)
        val settings = HeadsetBridge.settings

        when (val cmd = intent.getStringExtra("cmd")) {
            "status" -> {
                val stats = HeadsetBridge.link.stats.value
                val counters = HeadsetBridge.publisher.counters.value
                BridgeLog.i(
                    SCOPE,
                    "STATUS state=${HeadsetBridge.link.state.value.describe()} " +
                            "transport=${settings.transportKind} bleServerIsSource=${settings.bleServerIsSource} " +
                            "host=${settings.tcpHost} port=${settings.tcpPort} " +
                            "encrypted=${settings.sessionKey() != null} " +
                            "received=${counters.received} published=${counters.published} " +
                            "removed=${counters.removed} throttled=${counters.throttled} " +
                            "linkReceived=${stats.received} reconnects=${stats.reconnects} rtt=${stats.lastRttMs}"
                )
            }

            "pair" -> {
                val code = Pairing.normalise(intent.getStringExtra("code").orEmpty())
                if (!Pairing.isValid(code)) {
                    BridgeLog.w(SCOPE, "pair: expected ${Pairing.CODE_LENGTH} digits, got '$code'")
                    return
                }
                settings.pairingCode = code
                HeadsetBridge.restartLink("pairing code set over adb")
                BridgeLog.i(SCOPE, "pair: code set")
            }

            "encryption" -> {
                settings.encryptionEnabled = intent.getBooleanExtra("value", true)
                HeadsetBridge.restartLink("encryption toggled over adb")
            }

            "transport" -> {
                val kind = runCatching {
                    TransportKind.valueOf(intent.getStringExtra("kind").orEmpty().uppercase())
                }.getOrNull()
                if (kind == null) {
                    BridgeLog.w(SCOPE, "transport: expected BLE or TCP")
                    return
                }
                settings.transportKind = kind
                HeadsetBridge.restartLink("transport switched to $kind over adb")
            }

            "role" -> {
                settings.bleServerIsSource = intent.getBooleanExtra("server_is_source", true)
                HeadsetBridge.restartLink("ble role swapped over adb")
            }

            "host" -> {
                settings.tcpHost = intent.getStringExtra("value")
                HeadsetBridge.restartLink("tcp host set over adb")
                BridgeLog.i(SCOPE, "tcp host = ${settings.tcpHost}")
            }

            "port" -> {
                settings.tcpPort = intent.getIntExtra("value", 47820)
                HeadsetBridge.restartLink("tcp port set over adb")
            }

            "interval" -> {
                settings.headsUpIntervalMs = intent.getLongExtra("value", 2_500L)
                BridgeLog.i(SCOPE, "heads-up interval = ${settings.headsUpIntervalMs} ms")
            }

            "cameras" -> BridgeLog.i(SCOPE, "CAMERAS ${HeadsetCamera.describe(context)}")

            "selftest" -> HeadsetBridge.selfTest()

            "restart" -> HeadsetBridge.restartLink("adb")

            else -> BridgeLog.w(SCOPE, "unknown cmd '$cmd'")
        }
    }

    private companion object {
        const val SCOPE = "debug"
    }
}
