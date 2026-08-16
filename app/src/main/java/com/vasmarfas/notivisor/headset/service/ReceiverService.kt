package com.vasmarfas.notivisor.headset.service

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
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReceiverService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        HeadsetBridge.init(this)
        createChannel()
        goForeground(buildNotification(getString(R.string.status_starting)))

        HeadsetBridge.startLink()

        lifecycleScope.launch {
            HeadsetBridge.link.incoming.collect { HeadsetBridge.onEnvelope(it) }
        }
        lifecycleScope.launch {
            HeadsetBridge.link.state.collectLatest { state ->
                updateNotification(state.describe())
                if (state.isConnected) HeadsetBridge.reportStatus()
            }
        }
        lifecycleScope.launch { heartbeat() }
        BridgeLog.i(SCOPE, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            HeadsetBridge.stopLink()
            clearNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeLog.w(SCOPE, "service destroyed")
        HeadsetBridge.stopLink()
        clearNotification()
        super.onDestroy()
    }

    private fun clearNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private suspend fun heartbeat() {
        val startedAt = System.currentTimeMillis()
        while (lifecycleScope.isActive) {
            delay(HEARTBEAT_MS)
            HeadsetBridge.reportStatus()
            val counters = HeadsetBridge.publisher.counters.value
            val stats = HeadsetBridge.link.stats.value
            BridgeLog.i(
                SCOPE,
                "ALIVE ${(System.currentTimeMillis() - startedAt) / 60_000} min " +
                        "state=${HeadsetBridge.link.state.value.describe()} " +
                        "received=${counters.received} published=${counters.published} " +
                        "removed=${counters.removed} reconnects=${stats.reconnects}"
            )
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
        ).apply { setShowBadge(false) }
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
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(status)
        )
    }

    companion object {
        private const val SCOPE = "service"
        private const val CHANNEL_ID = "receiver_service"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 60_000L

        const val ACTION_STOP = "STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
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
