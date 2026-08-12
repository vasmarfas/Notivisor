package com.vasmarfas.notivisor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.util.BridgeLog

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = BridgeSettings.get(context)
        if (settings.stoppedByUser || !settings.enabled) {
            BridgeLog.i(SCOPE, "${intent.action}: stopped by the user, not starting")
            return
        }
        BridgeLog.i(SCOPE, "${intent.action}: starting as ${AppRole.current(context)}")
        AppRole.start(context)
    }

    private companion object {
        const val SCOPE = "boot"
    }
}
