package com.vasmarfas.notivisor.headset.core

import android.app.NotificationManager
import android.os.Build

enum class ShadeState {
    UNKNOWN,
    OK,
    APP_BLOCKED,
    CHANNEL_BLOCKED,
    DROPPED,
}

fun NotificationManager.probeShade(channelId: String, postedId: Int): ShadeState {
    if (!areNotificationsEnabled()) return ShadeState.APP_BLOCKED

    val channel = getNotificationChannel(channelId)
    if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
        return ShadeState.CHANNEL_BLOCKED
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val group = channel?.group?.let { id -> notificationChannelGroups.find { it.id == id } }
        if (group?.isBlocked == true) return ShadeState.CHANNEL_BLOCKED
    }

    val live = runCatching { activeNotifications.any { it.id == postedId } }.getOrDefault(true)
    return if (live) ShadeState.OK else ShadeState.DROPPED
}

fun ShadeState.describe(): String = when (this) {
    ShadeState.UNKNOWN -> "not checked yet"
    ShadeState.OK -> "accepted by the system"
    ShadeState.APP_BLOCKED -> "notifications are switched off for this app"
    ShadeState.CHANNEL_BLOCKED -> "the channel is switched off"
    ShadeState.DROPPED -> "the system took it and then dropped it"
}
