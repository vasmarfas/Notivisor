package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.vasmarfas.notivisor.core.protocol.MediaKey
import com.vasmarfas.notivisor.core.util.BridgeLog

object MediaRemote {

    private const val SCOPE = "media"

    fun press(context: Context, key: String) {
        val code = when (key) {
            MediaKey.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> {
                BridgeLog.w(SCOPE, "unknown media key '$key'")
                return
            }
        }
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val now = System.currentTimeMillis()
        manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        BridgeLog.i(SCOPE, "pressed $key")
    }

    fun changeVolume(context: Context, direction: Int) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val step = if (direction >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, step, AudioManager.FLAG_SHOW_UI)
        }.onFailure { BridgeLog.w(SCOPE, "could not change volume: ${it.message}") }
    }
}
