package com.vasmarfas.notivisor.core.util

import android.app.Notification
import android.os.Bundle

private const val PICO_TYPE = "notification_type"
private const val PICO_HEADS_UP = "notification_isheadsup"

private const val PICO_TYPE_SOCIAL = "1"
private const val PICO_ON = "1"

fun Notification.Builder.vrTagged(): Notification.Builder = addExtras(
    Bundle().apply {
        putString(PICO_TYPE, PICO_TYPE_SOCIAL)
        putString(PICO_HEADS_UP, PICO_ON)
    }
)
