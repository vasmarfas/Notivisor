package com.vasmarfas.notivisor.core.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.notivisor.core.util.BridgeLog

class AdbPairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAIR) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbPairPrompt.RESULT_KEY)
            ?.toString()
            ?.filter(Char::isDigit)
            .orEmpty()

        if (code.length != CODE_LENGTH) {
            BridgeLog.w(SCOPE, "pairing code should be $CODE_LENGTH digits, got ${code.length}")
            AdbPairPrompt.finish(context, paired = false)
            return
        }

        val app = context.applicationContext
        AdbPairPrompt.working(app)

        val pending = goAsync()
        Thread {
            val paired = runCatching { AdbConnection.pair(app, code) }.getOrDefault(false)
            val ready = paired && AdbConnection.resolvePort(app) != null
            BridgeLog.i(SCOPE, "pairing finished: paired=$paired ready=$ready")
            AdbPairPrompt.finish(app, ready)
            pending.finish()
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val ACTION_PAIR = "com.vasmarfas.notivisor.adb.PAIR"

        private const val SCOPE = "adb"
        private const val CODE_LENGTH = 6
    }
}
