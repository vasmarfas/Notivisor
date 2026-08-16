package com.vasmarfas.notivisor.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vasmarfas.notivisor.MainActivity
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.DoNotDisturb
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class BridgeService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        PhoneBridge.init(this)
        createChannel()
        goForeground(buildNotification(getString(R.string.status_starting)))
        PhoneBridge.startLink()

        lifecycleScope.launch {
            PhoneBridge.link.state.collectLatest { state ->
                updateNotification(state.describe())
                applyDoNotDisturb(state.isConnected)
                PhoneBridge.notifyStateChanged(this@BridgeService)
            }
        }
        lifecycleScope.launch {
            PhoneBridge.link.incoming.collect { PhoneBridge.onEnvelope(it) }
        }
        lifecycleScope.launch { listenerWatchdog() }
        BridgeLog.i(SCOPE, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                PhoneBridge.stopLink()
                clearNotification()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> PhoneBridge.restartLink("requested by UI")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeLog.w(SCOPE, "service destroyed")
        PhoneBridge.stopLink()
        DoNotDisturb.release(this)
        clearNotification()
        super.onDestroy()
    }

    private fun applyDoNotDisturb(connected: Boolean) {
        if (!PhoneBridge.settings.autoDnd) {
            DoNotDisturb.release(this)
            return
        }
        if (connected) DoNotDisturb.engage(this) else DoNotDisturb.release(this)
    }

    private fun clearNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private suspend fun listenerWatchdog() {
        while (lifecycleScope.isActive) {
            delay(WATCHDOG_INTERVAL_MS.milliseconds)
            if (PhoneBridge.settings.stoppedByUser) continue
            if (!NotifyListener.isEnabled(this)) continue
            if (!PhoneBridge.listenerConnected.value) {
                BridgeLog.w(SCOPE, "listener still detached, requesting rebind")
                NotifyListener.rebind(this)
            }
        }
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: SecurityException) {
            BridgeLog.w(
                SCOPE,
                "connectedDevice foreground type refused, falling back to dataSync: ${e.message}"
            )
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.channel_service_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val SCOPE = "service"
        private const val CHANNEL_ID = "bridge_service"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        const val ACTION_STOP = "STOP"
        const val ACTION_RESTART = "RESTART"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, BridgeService::class.java).apply { this.action = action }
            runCatching { context.startForegroundService(intent) }.onFailure {
                BridgeLog.e(
                    SCOPE,
                    "could not start service",
                    it
                )
            }
        }

    }
}
