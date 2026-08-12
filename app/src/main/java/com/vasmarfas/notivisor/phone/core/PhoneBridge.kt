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
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import com.vasmarfas.notivisor.phone.service.BridgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MirrorCounters(
    val mirrored: Long = 0,
    val skipped: Long = 0,
    val removed: Long = 0,
    val lastEvent: String? = null,
)

object PhoneBridge {

    private const val SCOPE = "phone"

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

    fun onPosted(sbn: StatusBarNotification) {
        if (!initialised || !settings.enabled) return
        when (val decision = mirror.inspect(sbn)) {
            is MirrorDecision.Send -> {
                link.enqueue(decision.envelope)
                _counters.value = _counters.value.copy(
                    mirrored = _counters.value.mirrored + 1,
                    lastEvent = "${decision.envelope.app}: ${decision.envelope.title ?: decision.envelope.text}",
                )
                BridgeLog.i(SCOPE, "mirror ${sbn.packageName} '${decision.envelope.title}'")
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
