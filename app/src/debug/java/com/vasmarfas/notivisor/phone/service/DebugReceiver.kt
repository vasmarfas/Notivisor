package com.vasmarfas.notivisor.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.util.Base64
import androidx.core.graphics.createBitmap
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.adb.AdbConnection
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.ChatMessage
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.protocol.RemoteAction
import com.vasmarfas.notivisor.core.protocol.ScreenControl
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.DoNotDisturb
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

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

            "clip" -> {
                val text = intent.getStringExtra("value")
                if (text == null) {
                    PhoneBridge.sendClipboard()
                } else {
                    PhoneBridge.link.sendIfItFits(
                        Envelope(action = Action.CLIP, data = text),
                        "clipboard",
                    )
                }
            }

            "open" -> {
                val url = intent.getStringExtra("url")
                if (url == null) {
                    BridgeLog.w(SCOPE, "open: --es url <address> required")
                } else {
                    PhoneBridge.sendToHeadset(url)
                }
            }

            "find" -> PhoneBridge.findHeadset()

            "media" -> {
                val key = intent.getStringExtra("key")
                if (key == null) {
                    BridgeLog.w(SCOPE, "media: --es key play_pause|next|previous required")
                } else {
                    PhoneBridge.pressMediaKey(key)
                }
            }

            "volume" -> PhoneBridge.changeHeadsetVolume(intent.getIntExtra("dir", 1))

            "headset" -> {
                val status = PhoneBridge.headset.value
                BridgeLog.i(
                    SCOPE,
                    "HEADSET battery=${status.battery} worn=${status.worn} at=${status.updatedAt}"
                )
            }

            "actions" -> {
                settings.mirrorActions = intent.getBooleanExtra("value", true)
                BridgeLog.i(SCOPE, "mirrorActions = ${settings.mirrorActions}")
            }

            "presence" -> {
                settings.presenceGated = intent.getBooleanExtra("value", true)
                BridgeLog.i(SCOPE, "presenceGated = ${settings.presenceGated}")
            }

            "dnd" -> {
                settings.autoDnd = intent.getBooleanExtra("value", true)
                BridgeLog.i(
                    SCOPE,
                    "autoDnd = ${settings.autoDnd}, granted = ${DoNotDisturb.granted(context)}"
                )
            }

            "codetest" -> {
                val now = System.currentTimeMillis()
                PhoneBridge.link.enqueue(
                    Envelope(
                        action = Action.POST,
                        key = "notivisor|code|$now",
                        pkg = "com.vasmarfas.notivisor.test",
                        app = "Bank",
                        title = "Bank",
                        text = "Your verification code is 483927. Do not share it.",
                        ts = now,
                        whenMs = now,
                        prio = 1,
                    )
                )
                BridgeLog.i(SCOPE, "one-time-code sample queued")
            }

            "calltest" -> {
                val now = System.currentTimeMillis()
                PhoneBridge.link.enqueue(
                    Envelope(
                        action = Action.POST,
                        key = "notivisor|call|$now",
                        pkg = "com.vasmarfas.notivisor.test",
                        app = "Phone",
                        title = "Incoming call",
                        text = "+7 900 000-00-00",
                        ts = now,
                        whenMs = now,
                        prio = 2,
                        category = Notification.CATEGORY_CALL,
                        actions = listOf(
                            RemoteAction(0, "Answer"),
                            RemoteAction(1, "Decline"),
                        ),
                    )
                )
                BridgeLog.i(SCOPE, "call sample queued")
            }

            "richtest" -> {
                val now = System.currentTimeMillis()
                val bitmap = createBitmap(240, 160)
                Canvas(bitmap).drawColor(Color.rgb(90, 140, 220))
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val picture = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                PhoneBridge.link.enqueue(
                    Envelope(
                        action = Action.POST,
                        key = "notivisor|rich|$now",
                        pkg = "com.vasmarfas.notivisor.test",
                        app = "Gallery",
                        title = "New photo",
                        text = "A blue rectangle, sent as a big picture",
                        ts = now,
                        whenMs = now,
                        prio = 1,
                        picture = picture,
                    )
                )
                BridgeLog.i(SCOPE, "rich content sample queued (${picture.length} b64 chars)")
            }

            "chattest" -> {
                val now = System.currentTimeMillis()
                PhoneBridge.link.enqueue(
                    Envelope(
                        action = Action.POST,
                        key = "notivisor|chat|$now",
                        pkg = "com.vasmarfas.notivisor.test",
                        app = "Messenger",
                        title = "Team VR",
                        text = "Anna: see you in the headset",
                        ts = now,
                        whenMs = now,
                        prio = 1,
                        messages = listOf(
                            ChatMessage("Anna", "Are you still in VR?", now - 120_000),
                            ChatMessage(null, "Yeah, another 20 min", now - 90_000),
                            ChatMessage("Anna", "see you in the headset", now),
                        ),
                        actions = listOf(RemoteAction(0, "Reply", reply = true)),
                    )
                )
                BridgeLog.i(SCOPE, "chat sample queued")
            }

            "replytest" -> {
                val now = System.currentTimeMillis()
                PhoneBridge.link.enqueue(
                    Envelope(
                        action = Action.POST,
                        key = "notivisor|reply|$now",
                        pkg = "com.vasmarfas.notivisor.test",
                        app = "Messenger",
                        title = "Anna",
                        text = "Are you still in VR?",
                        ts = now,
                        whenMs = now,
                        prio = 1,
                        actions = listOf(
                            RemoteAction(0, "Reply", reply = true),
                            RemoteAction(1, "Mark read"),
                        ),
                    )
                )
                BridgeLog.i(SCOPE, "reply sample queued")
            }

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
                                text = "Отправлено в ${TIME_FORMAT.format(now)}",
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

            "bait" -> postBait(context)

            "replied" -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(BAIT_RESULT_KEY)
                BridgeLog.i(SCOPE, "BAIT REPLY RECEIVED: '$text'")
            }

            "baited" -> BridgeLog.i(SCOPE, "BAIT ACTION RECEIVED")

            "control" -> {
                val x = intent.getFloatExtra("x", 0.5f)
                val y = intent.getFloatExtra("y", 0.5f)
                Thread {
                    val connected = ScrcpySession.start(context, wantVideo = false)
                    BridgeLog.i(SCOPE, "CONTROL connect=$connected path=${InputRouter.path}")
                    if (connected) {
                        val metrics = context.resources.displayMetrics
                        InputRouter.handle(
                            ScreenControl.Event.Tap(x, y),
                            metrics.widthPixels,
                            metrics.heightPixels,
                        )
                        BridgeLog.i(SCOPE, "CONTROL tapped at $x,$y")
                    }
                }.start()
            }

            "mirror" -> {
                if (intent.getBooleanExtra("stop", false)) {
                    ScreenCaptureService.stop(context)
                    BridgeLog.i(SCOPE, "MIRROR stop requested")
                } else {
                    ScreenCaptureService.startWithScrcpy(context)
                    BridgeLog.i(SCOPE, "MIRROR start requested (scrcpy path)")
                }
            }

            "adbcheck" -> Thread {
                val port = AdbConnection.resolvePort(context)
                BridgeLog.i(SCOPE, "ADBCHECK port=$port path=${InputRouter.path}")
            }.apply { isDaemon = true }.start()

            "controlkey" -> {
                val code = intent.getIntExtra("code", 0)
                Thread {
                    val metrics = context.resources.displayMetrics
                    val sent = InputRouter.handle(
                        ScreenControl.Event.Key(code),
                        metrics.widthPixels,
                        metrics.heightPixels,
                    )
                    BridgeLog.i(SCOPE, "CONTROL key=$code sent=$sent")
                }.start()
            }

            else -> BridgeLog.w(SCOPE, "unknown cmd '$cmd'")
        }
    }

    private fun postBait(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(BAIT_CHANNEL, "Debug bait", NotificationManager.IMPORTANCE_DEFAULT)
        )

        fun pending(cmd: String, code: Int, mutable: Boolean) = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, DebugReceiver::class.java)
                .setAction(ACTION_DEBUG)
                .putExtra("cmd", cmd),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )

        val icon = Icon.createWithResource(context, R.drawable.ic_stat_bridge)

        val reply = Notification.Action.Builder(
            icon,
            "Reply",
            pending("replied", 9001, mutable = true),
        ).addRemoteInput(
            RemoteInput.Builder(BAIT_RESULT_KEY).setLabel("Reply").build()
        ).build()

        val plain = Notification.Action.Builder(
            icon,
            "Mark read",
            pending("baited", 9002, mutable = false),
        ).build()

        val notification = Notification.Builder(context, BAIT_CHANNEL)
            .setContentTitle("Debug bait")
            .setContentText("Reply to this from the headset")
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .addAction(reply)
            .addAction(plain)
            .build()

        manager.notify(BAIT_ID, notification)
        BridgeLog.i(SCOPE, "bait posted, id=$BAIT_ID")
    }

    private companion object {
        const val SCOPE = "debug"
        const val ACTION_DEBUG = "com.vasmarfas.notivisor.DEBUG"
        const val BAIT_CHANNEL = "debug_bait"
        const val BAIT_RESULT_KEY = "bait_reply"
        const val BAIT_ID = 4242

        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
