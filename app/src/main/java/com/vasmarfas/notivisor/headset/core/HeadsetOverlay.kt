package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.vasmarfas.notivisor.core.util.BridgeLog

class HeadsetOverlay(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null

    fun show(heading: String, body: String) {
        if (!granted(appContext)) {
            BridgeLog.w(SCOPE, "no draw-over-apps permission, nothing to show")
            return
        }
        main.post {
            hideNow()
            val manager = appContext.getSystemService(WindowManager::class.java) ?: return@post
            val view = card(heading, body)
            val added = runCatching { manager.addView(view, params()) }
            if (added.isFailure) {
                BridgeLog.w(SCOPE, "window manager refused it: ${added.exceptionOrNull()?.message}")
                return@post
            }
            shown = view
            main.postDelayed(::hideNow, VISIBLE_MS)
            BridgeLog.i(SCOPE, "overlay shown")
        }
    }

    fun toast(heading: String, body: String) {
        val text = listOfNotNull(heading, body.takeIf { it.isNotEmpty() }).joinToString("\n")
        main.post {
            runCatching { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
                .onFailure { BridgeLog.w(SCOPE, "toast refused: ${it.message}") }
        }
    }

    private fun hideNow() {
        val view = shown ?: return
        shown = null
        val manager = appContext.getSystemService(WindowManager::class.java) ?: return
        runCatching { manager.removeView(view) }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 64
    }

    private fun card(heading: String, body: String): View {
        val scale = appContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * scale).toInt()

        return LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(BACKGROUND)
            }
            addView(
                TextView(appContext).apply {
                    text = heading
                    setTextColor(TITLE_COLOR)
                    textSize = 19f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            if (body.isNotEmpty()) {
                addView(
                    TextView(appContext).apply {
                        text = body
                        setTextColor(BODY_COLOR)
                        textSize = 16f
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, 0)
                    }
                )
            }
        }
    }

    companion object {
        private const val SCOPE = "overlay"
        private const val VISIBLE_MS = 6_000L
        private const val BACKGROUND = 0xF21C1B1F.toInt()
        private const val TITLE_COLOR = 0xFFFFFFFF.toInt()
        private const val BODY_COLOR = 0xFFCAC4D0.toInt()

        fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
