package com.vasmarfas.notivisor.core.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object Clipboard {

    private const val SCOPE = "clip"
    private const val LABEL = "Notivisor"

    fun read(context: Context): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        return runCatching { clip.getItemAt(0).coerceToText(context).toString() }
            .onFailure { BridgeLog.w(SCOPE, "could not read the clipboard: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun write(context: Context, text: String, sensitive: Boolean = false): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(LABEL, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val written = runCatching { manager.setPrimaryClip(clip) }
            .onFailure { BridgeLog.w(SCOPE, "could not set the clipboard: ${it.message}") }
            .isSuccess
        if (!written) return false

        val stuck = runCatching {
            manager.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString() == text
        }.getOrDefault(false)

        if (stuck) {
            BridgeLog.i(SCOPE, "clipboard set (${BridgeLog.redact(text)})")
        } else {
            BridgeLog.w(
                SCOPE,
                "the system discarded the clipboard write — only the focused app or the active " +
                        "keyboard may set it"
            )
        }
        return stuck
    }
}
