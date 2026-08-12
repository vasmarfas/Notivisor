package com.vasmarfas.notivisor.phone.listener

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.settings.DeviceRole
import com.vasmarfas.notivisor.core.settings.RoleDetector
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.service.BridgeService

class NotifyListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        val settings = BridgeSettings.get(this)
        if (RoleDetector.resolve(this, settings) != DeviceRole.PHONE) {
            BridgeLog.i(SCOPE, "not the phone role, staying idle")
            return
        }
        if (settings.stoppedByUser) {
            BridgeLog.i(SCOPE, "stopped by the user, staying idle")
            return
        }
        instance = this
        PhoneBridge.init(this)
        PhoneBridge.setListenerConnected(true)
        BridgeLog.i(SCOPE, "listener connected")
        BridgeService.start(this)
        catchUp()
    }

    private fun catchUp() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        BridgeLog.i(SCOPE, "catching up on ${active.size} active notification(s)")
        active.forEach { PhoneBridge.onPosted(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        PhoneBridge.setListenerConnected(false)
        if (BridgeSettings.get(this).stoppedByUser) {
            BridgeLog.i(SCOPE, "listener disconnected after Quit, not rebinding")
            return
        }
        BridgeLog.w(SCOPE, "listener disconnected, asking for a rebind")
        rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (instance !== this) return
        PhoneBridge.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (instance !== this) return
        PhoneBridge.onRemoved(sbn)
    }

    companion object {
        private const val SCOPE = "listener"

        @Volatile
        var instance: NotifyListener? = null
            private set

        fun component(context: Context) = ComponentName(context, NotifyListener::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val target = component(context).flattenToString()
            val targetShort = component(context).flattenToShortString()
            return flat.split(':').any { it == target || it == targetShort }
        }

        fun unbind() {
            runCatching { instance?.requestUnbind() }
                .onFailure { BridgeLog.w(SCOPE, "requestUnbind failed: ${it.message}") }
        }

        fun rebind(context: Context) {
            runCatching { requestRebind(component(context)) }
                .onFailure { BridgeLog.w(SCOPE, "requestRebind failed: ${it.message}") }
        }
    }
}
