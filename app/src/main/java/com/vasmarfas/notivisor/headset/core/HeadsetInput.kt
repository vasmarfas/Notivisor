package com.vasmarfas.notivisor.headset.core

import android.content.Context
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.util.Clipboard

object HeadsetInput {

    private const val SCOPE = "input"

    fun deliver(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false

        if (sendOverShell(context, text, paste = true)) {
            BridgeLog.i(SCOPE, "pasted ${BridgeLog.redact(text)} into the focused field")
            return true
        }

        if (Clipboard.write(context, text)) return true
        BridgeLog.w(SCOPE, "no shell session and no focus, offering the copy notification")
        CopyPrompt.show(context, text)
        return false
    }

    private fun sendOverShell(context: Context, text: String, paste: Boolean): Boolean {
        if (!ScrcpySession.controlReady.value) {
            ScrcpySession.start(context, wantVideo = false)
        }
        return ScrcpySession.controlReady.value && ScrcpySession.setClipboard(text, paste)
    }
}
