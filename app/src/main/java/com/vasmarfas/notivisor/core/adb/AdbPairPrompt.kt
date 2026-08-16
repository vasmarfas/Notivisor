package com.vasmarfas.notivisor.core.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.util.BridgeLog

object AdbPairPrompt {

    const val RESULT_KEY = "pairing_code"

    private const val CHANNEL_ID = "adb_pairing"
    private const val NOTIFICATION_ID = 5

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.adb_pair_title),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        val remoteInput = RemoteInput.Builder(RESULT_KEY)
            .setLabel(context.getString(R.string.adb_pair_code))
            .build()

        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AdbPairReceiver::class.java).setAction(AdbPairReceiver.ACTION_PAIR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.adb_pair_title))
                .setContentText(context.getString(R.string.adb_pair_notification))
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(context.getString(R.string.adb_pair_steps))
                )
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.adb_pair_confirm),
                        reply,
                    ).addRemoteInput(remoteInput).build()
                )
                .build()
        )
    }

    fun finish(context: Context, paired: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.adb_pair_title))
                .setContentText(
                    context.getString(
                        if (paired) R.string.adb_pair_ok else R.string.adb_pair_failed
                    )
                )
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setAutoCancel(true)
                .setTimeoutAfter(if (paired) DISMISS_MS else FAILURE_DISMISS_MS)
                .build()
        )
    }

    fun working(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.adb_pair_title))
                .setContentText(context.getString(R.string.adb_pair_working))
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        )
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    fun openPairingScreen(context: Context): Boolean {
        val attempts = listOf(
            Intent(ACTION_ADB_WIRELESS),
            Intent().setClassName(
                SETTINGS_PACKAGE,
                "$SETTINGS_PACKAGE.Settings\$WirelessDebuggingActivity",
            ),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage(SETTINGS_PACKAGE),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        attempts.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                BridgeLog.i(
                    SCOPE,
                    "opened ${intent.component?.shortClassName ?: intent.action}"
                )
                return true
            }
        }
        BridgeLog.w(SCOPE, "no settings screen would open on this device")
        return false
    }

    private const val SCOPE = "adb"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val ACTION_ADB_WIRELESS = "android.settings.ADB_WIRELESS_SETTINGS"
    private const val DISMISS_MS = 8_000L
    private const val FAILURE_DISMISS_MS = 30_000L
}
