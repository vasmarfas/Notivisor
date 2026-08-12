package com.vasmarfas.notivisor.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DebugReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        PhoneBridge.init(context)
        val settings = PhoneBridge.settings

        when (val cmd = intent.getStringExtra("cmd")) {
            "status" -> {
                val stats = PhoneBridge.link.stats.value
                val counters = PhoneBridge.counters.value
                BridgeLog.i(
                    SCOPE,
                    "STATUS state=${PhoneBridge.link.state.value.describe()} " +
                            "transport=${settings.transportKind} bleServerIsSource=${settings.bleServerIsSource} " +
                            "encrypted=${settings.sessionKey() != null} listener=${PhoneBridge.listenerConnected.value} " +
                            "sent=${stats.sent} queued=${stats.queued} dropped=${stats.dropped} " +
                            "received=${stats.received} acked=${stats.acked} reconnects=${stats.reconnects} " +
                            "rtt=${stats.lastRttMs} mirrored=${counters.mirrored} skipped=${counters.skipped}"
                )
            }

            "pair" -> {
                val code = Pairing.normalise(intent.getStringExtra("code").orEmpty())
                if (!Pairing.isValid(code)) {
                    BridgeLog.w(
                        SCOPE,
                        "pair: expected ${Pairing.CODE_LENGTH} digits, got '${code}'"
                    )
                    return
                }
                settings.pairingCode = code
                PhoneBridge.restartLink("pairing code set over adb")
                BridgeLog.i(SCOPE, "pair: code set to ${Pairing.format(code)}")
            }

            "encryption" -> {
                settings.encryptionEnabled = intent.getBooleanExtra("value", true)
                PhoneBridge.restartLink("encryption toggled over adb")
                BridgeLog.i(SCOPE, "encryption = ${settings.encryptionEnabled}")
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
                PhoneBridge.restartLink("transport switched to $kind over adb")
            }

            "role" -> {
                settings.bleServerIsSource = intent.getBooleanExtra("server_is_source", true)
                PhoneBridge.restartLink("ble role swapped over adb")
                BridgeLog.i(SCOPE, "bleServerIsSource = ${settings.bleServerIsSource}")
            }

            "port" -> {
                settings.tcpPort = intent.getIntExtra("value", 47820)
                PhoneBridge.restartLink("tcp port changed over adb")
            }

            "quit" -> PhoneBridge.shutdown(context)

            "test" -> PhoneBridge.sendTest()

            "notifs" -> {
                val active = NotifyListener.instance?.activeNotifications
                if (active == null) {
                    BridgeLog.w(SCOPE, "notifs: listener is not bound")
                } else {
                    active.forEach { BridgeLog.i(SCOPE, "ACTIVE ${it.key}") }
                    BridgeLog.i(SCOPE, "notifs: ${active.size} active")
                }
            }

            "cancel" -> {
                val key = intent.getStringExtra("key")
                val listener = NotifyListener.instance
                when {
                    listener == null -> BridgeLog.w(SCOPE, "cancel: listener is not bound")
                    key == null -> BridgeLog.w(
                        SCOPE,
                        "cancel: --es key <notification key> required"
                    )

                    else -> {
                        listener.cancelNotification(key)
                        BridgeLog.i(SCOPE, "cancel: requested $key")
                    }
                }
            }

            "burst" -> {
                val n = intent.getIntExtra("n", 10)
                val gap = intent.getLongExtra("gap", 3_000L)
                BridgeLog.i(SCOPE, "BURST start n=$n gap=$gap")
                scope.launch {
                    repeat(n) { index ->
                        val now = System.currentTimeMillis()
                        PhoneBridge.link.enqueue(
                            Envelope(
                                action = Action.POST,
                                key = "Notivisor|burst|$index",
                                pkg = "com.vasmarfas.notivisor.test",
                                app = "Notivisor",
                                title = "Проба ${index + 1} из $n",
                                text = "Отправлено в ${
                                    java.text.SimpleDateFormat(
                                        "HH:mm:ss",
                                        java.util.Locale.US
                                    ).format(now)
                                }",
                                ts = now,
                                whenMs = now,
                                prio = 1,
                            )
                        )
                        BridgeLog.i(SCOPE, "BURST sent ${index + 1}/$n")
                        delay(gap)
                    }
                    BridgeLog.i(SCOPE, "BURST done n=$n")
                }
            }

            "enable" -> {
                settings.enabled = intent.getBooleanExtra("value", true)
                if (settings.enabled) BridgeService.start(context) else PhoneBridge.stopLink()
            }

            "restart" -> PhoneBridge.restartLink("adb")

            else -> BridgeLog.w(SCOPE, "unknown cmd '$cmd'")
        }
    }

    private companion object {
        const val SCOPE = "debug"
    }
}
