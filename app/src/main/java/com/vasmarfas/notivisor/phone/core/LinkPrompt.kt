package com.vasmarfas.notivisor.phone.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.vasmarfas.notivisor.R

object LinkPrompt {

    private const val CHANNEL_ID = "headset_link"

    fun show(context: Context, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_links),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val id = url.hashCode()
        manager.notify(
            id,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.link_prompt_title))
                .setContentText(url)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        id,
                        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setAutoCancel(true)
                .build()
        )
    }
}
