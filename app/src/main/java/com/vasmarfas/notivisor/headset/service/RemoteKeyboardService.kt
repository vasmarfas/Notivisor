package com.vasmarfas.notivisor.headset.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.util.Clipboard
import com.vasmarfas.notivisor.headset.core.HeadsetBridge

class RemoteKeyboardService : InputMethodService() {

    private lateinit var status: TextView

    override fun onCreateInputView(): View {
        HeadsetBridge.init(this)
        val padding = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            textSize = 16f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(status)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        status.text = getString(R.string.keyboard_waiting)
        HeadsetBridge.onTextReceived = { text ->
            currentInputConnection?.commitText(text, 1)
            status.text = getString(R.string.keyboard_sent)
        }
        HeadsetBridge.onClipReceived = { text ->
            currentInputConnection?.commitText(text, 1)
            Clipboard.write(this, text)
            status.text = getString(R.string.keyboard_sent)
        }
        HeadsetBridge.send(Envelope(action = Action.TEXT_REQ))
        BridgeLog.i(SCOPE, "field focused, asked the phone to show its input sheet")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        HeadsetBridge.onTextReceived = null
        HeadsetBridge.onClipReceived = null
        HeadsetBridge.send(Envelope(action = Action.TEXT_DONE))
        BridgeLog.i(SCOPE, "field lost focus")
    }

    companion object {
        private const val SCOPE = "keyboard"

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(InputMethodManager::class.java) ?: return false
            return runCatching {
                manager.enabledInputMethodList.any { it.packageName == context.packageName }
            }.getOrDefault(false)
        }
    }
}
