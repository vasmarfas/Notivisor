package com.vasmarfas.notivisor.phone.core

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.listener.NotifyListener

object NotificationCommands {

    private const val SCOPE = "command"

    fun invoke(context: Context, key: String, index: Int) {
        val action = actionAt(key, index) ?: return
        if (action.remoteInputs?.isNotEmpty() == true) {
            BridgeLog.w(SCOPE, "action $index of $key wants text, ignoring a plain tap")
            return
        }
        fire(context, action.actionIntent, Intent(), key, index)
    }

    fun reply(context: Context, key: String, index: Int, text: String) {
        val action = actionAt(key, index) ?: return
        val inputs = action.remoteInputs
        if (inputs.isNullOrEmpty()) {
            BridgeLog.w(SCOPE, "action $index of $key takes no text")
            return
        }

        val results = Bundle()
        inputs.forEach { input -> results.putCharSequence(input.resultKey, text) }

        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        fire(context, action.actionIntent, intent, key, index)
    }

    fun dismiss(key: String) {
        val listener = NotifyListener.instance
        if (listener == null) {
            BridgeLog.w(SCOPE, "cannot dismiss $key, the listener is not bound")
            return
        }
        runCatching { listener.cancelNotification(key) }
            .onSuccess { BridgeLog.i(SCOPE, "dismissed $key on the phone") }
            .onFailure { BridgeLog.w(SCOPE, "dismiss of $key failed: ${it.message}") }
    }

    private fun actionAt(key: String, index: Int): Notification.Action? {
        val listener = NotifyListener.instance
        if (listener == null) {
            BridgeLog.w(SCOPE, "no listener bound, cannot act on $key")
            return null
        }
        val active = runCatching { listener.getActiveNotifications(arrayOf(key)) }
            .getOrElse {
                BridgeLog.w(SCOPE, "could not read $key back: ${it.message}")
                null
            }
        val notification = active?.firstOrNull()?.notification
        if (notification == null) {
            BridgeLog.w(SCOPE, "$key is no longer posted, nothing to act on")
            return null
        }
        val action = notification.actions?.getOrNull(index)
        if (action?.actionIntent == null) {
            BridgeLog.w(SCOPE, "$key has no action $index any more")
            return null
        }
        return action
    }

    private fun fire(
        context: Context,
        intent: PendingIntent,
        fillIn: Intent,
        key: String,
        index: Int,
    ) {
        runCatching { intent.send(context, 0, fillIn) }
            .onSuccess { BridgeLog.i(SCOPE, "ran action $index of $key") }
            .onFailure { BridgeLog.w(SCOPE, "action $index of $key failed: ${it.message}") }
    }
}
