package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.link.LinkManager
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.LinkRole
import com.vasmarfas.notivisor.core.util.BridgeLog
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

    fun onEnvelope(envelope: Envelope) {
        when (envelope.action) {
            Action.POST, Action.REMOVE -> publisher.submit(envelope)
            else -> BridgeLog.d(SCOPE, "ignoring action '${envelope.action}'")
        }
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
