package com.vasmarfas.notivisor.headset.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.headset.ui.CopyActivity

object CopyPrompt {

    private const val CHANNEL_ID = "copy_fallback"
    private const val NOTIFICATION_ID = 6

    fun show(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_copy),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            CopyActivity.intent(context, text, sensitive = true)
                .putExtra(CopyActivity.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.copy_prompt_title))
                .setContentText(text)

                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(text)
                        .setSummaryText(context.getString(R.string.copy_prompt_text))
                )
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
