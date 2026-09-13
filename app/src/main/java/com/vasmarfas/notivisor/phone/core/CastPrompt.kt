package com.vasmarfas.notivisor.phone.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.phone.ui.CastActivity

object CastPrompt {

    private const val CHANNEL_ID = "cast_request"
    private const val NOTIFICATION_ID = 5

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_cast),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, CastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.cast_window_title))
                .setContentText(context.getString(R.string.cast_request_text))
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
