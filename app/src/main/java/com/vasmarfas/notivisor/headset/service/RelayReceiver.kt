package com.vasmarfas.notivisor.headset.service

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.HeadsetBridge

class RelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        HeadsetBridge.init(context)
        val key = intent.getStringExtra(EXTRA_KEY)
        val index = intent.getIntExtra(EXTRA_INDEX, 0)

        when (intent.action) {
            ACTION_INVOKE -> {
                key ?: return
                HeadsetBridge.send(Envelope(action = Action.INVOKE, key = key, idx = index))
                dismissLocally(context, intent)
                BridgeLog.i(SCOPE, "action $index of $key sent to the phone")
            }

            ACTION_REPLY -> {
                key ?: return
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REPLY_RESULT_KEY)
                    ?.toString()
                    ?.trim()
                if (text.isNullOrEmpty()) {
                    BridgeLog.w(SCOPE, "reply for $key arrived empty")
                    return
                }
                HeadsetBridge.send(
                    Envelope(action = Action.REPLY, key = key, idx = index, data = text)
                )
                dismissLocally(context, intent)
                BridgeLog.i(SCOPE, "reply for $key sent (${BridgeLog.redact(text)})")
            }

            ACTION_DISMISS -> {
                key ?: return
                HeadsetBridge.send(Envelope(action = Action.DISMISS, key = key))
                BridgeLog.i(SCOPE, "dismissal of $key sent to the phone")
            }
        }
    }

    private fun dismissLocally(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    companion object {
        private const val SCOPE = "relay"

        const val ACTION_INVOKE = "com.vasmarfas.notivisor.headset.INVOKE"
        const val ACTION_REPLY = "com.vasmarfas.notivisor.headset.REPLY"
        const val ACTION_DISMISS = "com.vasmarfas.notivisor.headset.DISMISS"

        const val EXTRA_KEY = "key"
        const val EXTRA_INDEX = "index"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        const val REPLY_RESULT_KEY = "notivisor_reply"
    }
}
