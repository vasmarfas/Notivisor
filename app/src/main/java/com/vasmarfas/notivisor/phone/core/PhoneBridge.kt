package com.vasmarfas.notivisor.phone.core

import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.link.LinkManager
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.LinkRole
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.util.Clipboard
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import com.vasmarfas.notivisor.phone.service.BridgeService
import com.vasmarfas.notivisor.phone.service.ScreenCaptureService
import com.vasmarfas.notivisor.phone.service.TaskerReceiver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class MirrorCounters(
    val mirrored: Long = 0,
    val skipped: Long = 0,
    val removed: Long = 0,
    val lastEvent: String? = null,
)

data class HeadsetStatus(
    val battery: Int? = null,
    val worn: Boolean? = null,
    val updatedAt: Long = 0L,
)

object PhoneBridge {

    private const val SCOPE = "phone"

    private const val PRESENCE_STALE_MS = 180_000L

    @Volatile
    private var initialised = false

    private lateinit var appContext: Context

    lateinit var settings: BridgeSettings
        private set

    lateinit var link: LinkManager
        private set

    lateinit var mirror: NotificationMirror
        private set

    private val _counters = MutableStateFlow(MirrorCounters())
    val counters: StateFlow<MirrorCounters> = _counters.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _headset = MutableStateFlow(HeadsetStatus())
    val headset: StateFlow<HeadsetStatus> = _headset.asStateFlow()

    private val _typeDismiss = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val typeDismiss: SharedFlow<Unit> = _typeDismiss.asSharedFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        val app = context.applicationContext
        appContext = app
        settings = BridgeSettings.get(app)
        mirror = NotificationMirror(app, settings)
        link = LinkManager(app, settings, LinkRole.SOURCE)
        initialised = true
        BridgeLog.i(SCOPE, "bridge initialised")
    }

    fun startLink() {
        if (!initialised) return
        link.start()
    }

    fun stopLink() {
        if (!initialised) return
        link.stop()
    }

    fun restartLink(reason: String) {
        if (!initialised) return
        mirror.forget()
        link.restart(reason)
    }

    fun shutdown(context: Context) {
        if (!initialised) return
        settings.stoppedByUser = true
        shutdownIfRunning(context)
        BridgeLog.i(SCOPE, "shut down by the user")
    }

    fun shutdownIfRunning(context: Context) {
        if (!initialised) return
        link.stop()
        NotifyListener.unbind()
        context.stopService(Intent(context, BridgeService::class.java))
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun notifyStateChanged(context: Context) {
        if (!initialised) return
        TaskerReceiver.broadcastState(context, settings.enabled, link.state.value)
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (!initialised || !settings.enabled) return
        if (settings.presenceGated && !headsetLikelyWorn()) {
            _counters.value = _counters.value.copy(skipped = _counters.value.skipped + 1)
            BridgeLog.d(SCOPE, "skip ${sbn.packageName}: headset is not worn")
            return
        }
        when (val decision = mirror.inspect(sbn)) {
            is MirrorDecision.Send -> {
                link.enqueue(decision.envelope)
                _counters.value = _counters.value.copy(
                    mirrored = _counters.value.mirrored + 1,
                    lastEvent = "${decision.envelope.app}: ${decision.envelope.title ?: decision.envelope.text}",
                )
                BridgeLog.i(
                    SCOPE,
                    "mirror ${sbn.packageName} title=${BridgeLog.redact(decision.envelope.title)}"
                )
            }

            is MirrorDecision.Skip -> {
                _counters.value = _counters.value.copy(skipped = _counters.value.skipped + 1)
                BridgeLog.d(SCOPE, "skip ${sbn.packageName}: ${decision.reason}")
            }
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (!initialised || !settings.enabled) return
        val envelope = mirror.removal(sbn) ?: return
        link.enqueue(envelope)
        _counters.value = _counters.value.copy(removed = _counters.value.removed + 1)
        BridgeLog.d(SCOPE, "remove ${sbn.packageName}")
    }

    fun onEnvelope(envelope: Envelope) {
        if (!initialised) return
        when (envelope.action) {
            Action.INVOKE -> {
                val key = envelope.key ?: return
                NotificationCommands.invoke(appContext, key, envelope.idx ?: return)
            }

            Action.REPLY -> {
                val key = envelope.key ?: return
                val text = envelope.data ?: return
                NotificationCommands.reply(appContext, key, envelope.idx ?: 0, text)
            }

            Action.DISMISS -> NotificationCommands.dismiss(envelope.key ?: return)

            Action.ICON_REQ -> sendIcon(envelope.pkg ?: return)

            Action.CLIP -> Clipboard.write(appContext, envelope.data ?: return)

            Action.STATUS -> {
                _headset.value = HeadsetStatus(
                    battery = envelope.battery,
                    worn = envelope.worn,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            Action.TEXT_REQ -> TypePrompt.show(appContext)

            Action.TEXT_DONE -> {
                _typeDismiss.tryEmit(Unit)
                TypePrompt.dismiss(appContext)
            }

            Action.MIRROR_START -> Thread {
                if (ScrcpySession.isAvailable(appContext)) {
                    ScreenCaptureService.startWithScrcpy(appContext)
                    BridgeLog.i(SCOPE, "headset asked to start mirroring")
                } else {
                    BridgeLog.w(SCOPE, "headset asked to mirror, but adb is not reachable here")
                }
            }.apply { isDaemon = true }.start()

            Action.MIRROR_STOP -> {
                ScreenCaptureService.stop(appContext)
                BridgeLog.i(SCOPE, "headset asked to stop mirroring")
            }

            else -> BridgeLog.d(SCOPE, "ignoring '${envelope.action}' from the headset")
        }
    }

    private fun headsetLikelyWorn(): Boolean {
        val status = _headset.value
        if (status.updatedAt == 0L) return true
        if (System.currentTimeMillis() - status.updatedAt > PRESENCE_STALE_MS) return true
        return status.worn != false
    }

    private fun sendIcon(pkg: String) {
        val encoded = AppIcons.encode(appContext, pkg) ?: return
        link.sendIfItFits(
            Envelope(action = Action.ICON, pkg = pkg, data = encoded),
            "icon for $pkg",
        )
    }

    fun sendClipboard() {
        if (!initialised) return
        val text = Clipboard.read(appContext)
        if (text.isNullOrEmpty()) {
            BridgeLog.w(SCOPE, "clipboard is empty, nothing to send")
            return
        }
        link.sendIfItFits(Envelope(action = Action.CLIP, data = text), "clipboard")
        BridgeLog.i(SCOPE, "clipboard sent (${BridgeLog.redact(text)})")
    }

    fun sendToHeadset(url: String) {
        if (!initialised) return
        link.enqueue(Envelope(action = Action.OPEN, data = url))
        BridgeLog.i(SCOPE, "asked the headset to open a link")
    }

    fun findHeadset() {
        if (!initialised) return
        link.sendDirect(Envelope(action = Action.FIND, ts = System.currentTimeMillis()))
        BridgeLog.i(SCOPE, "find-my-headset sent")
    }

    fun pressMediaKey(key: String) {
        if (!initialised) return
        link.sendDirect(Envelope(action = Action.MEDIA, data = key))
    }

    fun changeHeadsetVolume(direction: Int) {
        if (!initialised) return
        link.sendDirect(Envelope(action = Action.VOLUME, idx = direction))
    }

    fun sendMirrorState(on: Boolean) {
        link.sendDirect(
            Envelope(
                action = if (on) Action.MIRROR_START else Action.MIRROR_STOP,
                ts = System.currentTimeMillis(),
            )
        )
    }

    fun sendTypedText(text: String) {
        if (!initialised) return
        link.sendDirect(Envelope(action = Action.TEXT, data = text))
        BridgeLog.i(SCOPE, "typed text sent to the headset (${BridgeLog.redact(text)})")
    }

    fun sendTest() {
        if (!initialised) return
        val now = System.currentTimeMillis()
        link.enqueue(
            Envelope(
                action = Action.POST,
                key = "notivisor|test|$now",
                pkg = appContext.packageName,
                app = appContext.getString(R.string.app_name),
                title = appContext.getString(R.string.test_title),
                text = appContext.getString(R.string.test_text),
                ts = now,
                whenMs = now,
                prio = 1,
            )
        )
        BridgeLog.i(SCOPE, "test notification queued")
    }
}
