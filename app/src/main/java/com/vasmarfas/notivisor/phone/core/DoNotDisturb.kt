package com.vasmarfas.notivisor.phone.core

import android.app.NotificationManager
import android.content.Context
import com.vasmarfas.notivisor.core.util.BridgeLog

object DoNotDisturb {

    private const val SCOPE = "dnd"

    @Volatile
    private var previousFilter: Int? = null

    fun granted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true

    fun engage(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) {
            BridgeLog.w(SCOPE, "no policy access, cannot silence the phone")
            return
        }
        if (previousFilter != null) return
        val current = runCatching { manager.currentInterruptionFilter }.getOrNull() ?: return
        if (current == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
            BridgeLog.i(SCOPE, "phone is already quiet, leaving it alone")
            return
        }
        previousFilter = current
        runCatching {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
            .onSuccess { BridgeLog.i(SCOPE, "phone silenced while the headset is connected") }
            .onFailure {
                previousFilter = null
                BridgeLog.w(SCOPE, "could not silence the phone: ${it.message}")
            }
    }

    fun release(context: Context) {
        val restore = previousFilter ?: return
        previousFilter = null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        runCatching { manager.setInterruptionFilter(restore) }
            .onSuccess { BridgeLog.i(SCOPE, "phone is audible again") }
            .onFailure { BridgeLog.w(SCOPE, "could not restore the filter: ${it.message}") }
    }
}
