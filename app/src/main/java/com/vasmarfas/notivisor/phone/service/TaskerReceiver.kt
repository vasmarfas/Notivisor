package com.vasmarfas.notivisor.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.PhoneBridge

class TaskerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        PhoneBridge.init(context)
        val settings = BridgeSettings.get(context)
        val target = when (intent.action) {
            ACTION_ENABLE -> true
            ACTION_DISABLE -> false
            ACTION_TOGGLE -> !settings.enabled
            else -> return
        }
        settings.enabled = target
        if (target) {
            settings.stoppedByUser = false
            BridgeService.start(context)
        } else {
            PhoneBridge.stopLink()
        }
        PhoneBridge.notifyStateChanged(context)
        BridgeLog.i(SCOPE, "forwarding ${if (target) "enabled" else "disabled"} by automation")
    }

    companion object {
        private const val SCOPE = "tasker"

        const val ACTION_ENABLE = "com.vasmarfas.notivisor.action.ENABLE"
        const val ACTION_DISABLE = "com.vasmarfas.notivisor.action.DISABLE"
        const val ACTION_TOGGLE = "com.vasmarfas.notivisor.action.TOGGLE"

        const val ACTION_STATE_CHANGED = "com.vasmarfas.notivisor.action.STATE_CHANGED"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_CONNECTED = "connected"

        fun broadcastState(context: Context, enabled: Boolean, state: LinkState) {
            context.sendBroadcast(
                Intent(ACTION_STATE_CHANGED)
                    .putExtra(EXTRA_ENABLED, enabled)
                    .putExtra(EXTRA_CONNECTED, state.isConnected)
            )
        }
    }
}
