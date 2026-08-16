package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.core.net.toUri
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.link.LinkManager
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.LinkRole
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.util.Clipboard
import com.vasmarfas.notivisor.headset.service.ReceiverService

object HeadsetBridge {

    private const val SCOPE = "headset"

    @Volatile
    private var initialised = false

    private lateinit var appContext: Context

    lateinit var settings: BridgeSettings
        private set

    lateinit var link: LinkManager
        private set

    lateinit var publisher: NotificationPublisher
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        val app = context.applicationContext
        appContext = app
        settings = BridgeSettings.get(app)
        publisher = NotificationPublisher(app, settings)
        link = LinkManager(app, settings, LinkRole.SINK)
        publisher.onPublished = { envelope ->
            link.sendDirect(Envelope.ack(envelope.seq, envelope.key))
        }
        publisher.onIconNeeded = { pkg ->
            link.sendDirect(Envelope(action = Action.ICON_REQ, pkg = pkg))
        }
        initialised = true
        BridgeLog.i(SCOPE, "bridge initialised")
    }

    fun startLink() {
        if (initialised) link.start()
    }

    fun stopLink() {
        if (initialised) link.stop()
    }

    fun restartLink(reason: String) {
        if (initialised) link.restart(reason)
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
        context.stopService(Intent(context, ReceiverService::class.java))
    }

    private fun paste(text: String, commit: ((String) -> Unit)?) {
        if (commit != null) {
            commit(text)
            return
        }
        Thread { HeadsetInput.deliver(appContext, text) }.apply { isDaemon = true }.start()
    }

    fun send(envelope: Envelope) {
        if (initialised) link.sendDirect(envelope)
    }

    var onTextReceived: ((String) -> Unit)? = null

    var onClipReceived: ((String) -> Unit)? = null

    var onMirrorStop: (() -> Unit)? = null

    @Volatile
    var uiVisible: Boolean = false

    fun onEnvelope(envelope: Envelope) {
        when (envelope.action) {
            Action.POST, Action.REMOVE -> publisher.submit(envelope)

            Action.ICON -> {
                val pkg = envelope.pkg ?: return
                val data = envelope.data ?: return
                publisher.storeIcon(pkg, data)
            }

            Action.CLIP -> paste(envelope.data ?: return, onClipReceived)

            Action.OPEN -> open(envelope.data ?: return)

            Action.FIND -> Alarm.sound(appContext)

            Action.MEDIA -> MediaRemote.press(appContext, envelope.data ?: return)

            Action.VOLUME -> MediaRemote.changeVolume(appContext, envelope.idx ?: return)

            Action.TEXT -> paste(envelope.data ?: return, onTextReceived)

            Action.MIRROR_START -> MirrorPrompt.show(appContext, uiVisible)

            Action.MIRROR_STOP -> {
                MirrorPrompt.dismiss(appContext)
                onMirrorStop?.invoke()
            }

            else -> BridgeLog.d(SCOPE, "ignoring action '${envelope.action}'")
        }
    }

    fun reportStatus() {
        if (!initialised) return
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val charging = battery?.isCharging == true
        link.sendDirect(
            Envelope(
                action = Action.STATUS,
                battery = level,
                worn = !charging,
                ts = System.currentTimeMillis(),
            )
        )
    }

    private fun open(url: String) {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            BridgeLog.w(SCOPE, "refusing to open a link that is not http(s)")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, target.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onSuccess { BridgeLog.i(SCOPE, "opened a link from the phone") }
            .onFailure { BridgeLog.w(SCOPE, "no browser took the link: ${it.message}") }
    }

    fun selfTest() {
        val now = System.currentTimeMillis()
        publisher.submit(
            Envelope(
                action = Action.POST,
                key = "notivisor|selftest|$now",
                pkg = appContext.packageName,
                app = "Notivisor",
                title = appContext.getString(R.string.selftest_title),
                text = appContext.getString(R.string.selftest_text),
                ts = now,
                whenMs = now,
            )
        )
    }
}
