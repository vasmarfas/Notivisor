package com.vasmarfas.notivisor.headset.service

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Bundle
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.protocol.ScreenControl
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.headset.core.HeadsetInput
import com.vasmarfas.notivisor.headset.core.ScreenReceiver
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

            "paste" -> {
                val text = intent.getStringExtra("value") ?: "Notivisor тест 123"
                Thread {
                    val ok = HeadsetInput.deliver(context, text)
                    BridgeLog.i(SCOPE, "PASTE delivered=$ok")
                }.apply { isDaemon = true }.start()
            }

            "selftest" -> HeadsetBridge.selfTest()

            "report" -> HeadsetBridge.reportStatus()

            "mirrortest" -> {
                val host = intent.getStringExtra("host") ?: settings.tcpHost
                if (host == null) {
                    BridgeLog.w(SCOPE, "mirrortest: no host, pass --es host <ip> or pair over TCP")
                    return
                }
                val testWidth = intent.getIntExtra("w", 384)
                val testHeight = intent.getIntExtra("h", 960)
                val reader =
                    ImageReader.newInstance(testWidth, testHeight, ImageFormat.PRIVATE, 12)
                testReceiver?.stop()
                val receiver = ScreenReceiver()
                testReceiver = receiver
                receiver.start(host, TransportConfig.SCREEN_STREAM_PORT, reader.surface)
                BridgeLog.i(SCOPE, "mirrortest: connecting to $host")
            }

            "mirrorstop" -> {
                testReceiver?.stop()
                testReceiver = null
            }

            "mirrorcheck" -> {
                val state = testReceiver?.state?.value
                BridgeLog.i(SCOPE, "mirrorcheck: $state")
            }

            "mirrortap" -> {
                val receiver = testReceiver
                if (receiver == null) {
                    BridgeLog.w(SCOPE, "mirrortap: run mirrortest first")
                    return
                }
                val x = intent.getFloatExtra("x", 0.5f)
                val y = intent.getFloatExtra("y", 0.5f)
                when (intent.getStringExtra("kind")) {
                    "key" -> receiver.send(ScreenControl.key(intent.getIntExtra("code", 0)))

                    "swipe" -> receiver.send(
                        ScreenControl.swipe(
                            x, y,
                            intent.getFloatExtra("x2", 0.5f),
                            intent.getFloatExtra("y2", 0.2f),
                            300,
                        )
                    )

                    else -> receiver.send(ScreenControl.tap(x, y))
                }
                BridgeLog.i(SCOPE, "mirrortap: sent")
            }

            "textreq" -> {
                HeadsetBridge.onTextReceived = { text ->
                    BridgeLog.i(SCOPE, "TEXT RECEIVED: '$text'")
                }
                HeadsetBridge.send(Envelope(action = Action.TEXT_REQ))
                BridgeLog.i(SCOPE, "text_req sent, listening for the reply")
            }

            "textdone" -> {
                HeadsetBridge.onTextReceived = null
                HeadsetBridge.send(Envelope(action = Action.TEXT_DONE))
            }

            "codes" -> {
                settings.offerCodes = intent.getBooleanExtra("value", true)
                BridgeLog.i(SCOPE, "offerCodes = ${settings.offerCodes}")
            }

            "restart" -> HeadsetBridge.restartLink("adb")

            "tap" -> {
                val key = intent.getStringExtra("key")
                if (key == null) {
                    BridgeLog.w(SCOPE, "tap: --es key <notification key> required")
                    return
                }
                val index = intent.getIntExtra("idx", 0)
                val text = intent.getStringExtra("text")
                val relay = Intent(context, RelayReceiver::class.java)
                    .setAction(
                        when {
                            intent.getBooleanExtra("dismiss", false) -> RelayReceiver.ACTION_DISMISS
                            text != null -> RelayReceiver.ACTION_REPLY
                            else -> RelayReceiver.ACTION_INVOKE
                        }
                    )
                    .putExtra(RelayReceiver.EXTRA_KEY, key)
                    .putExtra(RelayReceiver.EXTRA_INDEX, index)
                if (text != null) {
                    val results = Bundle().apply {
                        putCharSequence(RelayReceiver.REPLY_RESULT_KEY, text)
                    }
                    RemoteInput.addResultsToIntent(
                        arrayOf(RemoteInput.Builder(RelayReceiver.REPLY_RESULT_KEY).build()),
                        relay,
                        results,
                    )
                }
                context.sendBroadcast(relay)
                BridgeLog.i(SCOPE, "tap: relayed ${relay.action} for $key idx=$index")
            }

            else -> BridgeLog.w(SCOPE, "unknown cmd '$cmd'")
        }
    }

    private companion object {
        const val SCOPE = "debug"

        @Volatile
        var testReceiver: ScreenReceiver? = null
    }
}
