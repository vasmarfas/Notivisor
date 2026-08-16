package com.vasmarfas.notivisor.headset.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.headset.ui.MirrorActivity

object MirrorPrompt {

    private const val CHANNEL_ID = "mirror_request"
    private const val NOTIFICATION_ID = 4

    fun show(context: Context, foreground: Boolean) {
        if (foreground) {
            MirrorActivity.open(context)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_mirror),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.mirror_window_title))
                .setContentText(context.getString(R.string.mirror_request_text))
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
