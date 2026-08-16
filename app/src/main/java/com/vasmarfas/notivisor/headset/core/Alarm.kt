package com.vasmarfas.notivisor.headset.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.vasmarfas.notivisor.core.util.BridgeLog

object Alarm {

    private const val SCOPE = "alarm"
    private const val PLAY_MS = 8_000L
    private const val BEEP_MS = 700
    private const val BEEP_GAP_MS = 1_000L
    private const val BEEPS = 8

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var ringtone: Ringtone? = null

    @Volatile
    private var tones: ToneGenerator? = null

    fun sound(context: Context) {
        stop()
        if (playRingtone(context)) return
        playTone()
    }

    private fun playRingtone(context: Context): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return false
        val tone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return false
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return runCatching { tone.play() }
            .onSuccess {
                ringtone = tone
                BridgeLog.i(SCOPE, "playing the locator ringtone")
                handler.postDelayed(::stop, PLAY_MS)
            }
            .onFailure { BridgeLog.w(SCOPE, "ringtone refused to play: ${it.message}") }
            .isSuccess
    }

    private fun playTone() {
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        }.getOrNull()
        if (generator == null) {
            BridgeLog.w(SCOPE, "no way to make a sound on this device")
            return
        }
        tones = generator
        BridgeLog.i(SCOPE, "playing the locator tone")
        repeat(BEEPS) { index ->
            handler.postDelayed({
                runCatching {
                    tones?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, BEEP_MS)
                }
            }, index * BEEP_GAP_MS)
        }
        handler.postDelayed(::stop, BEEPS * BEEP_GAP_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.let { current ->
            ringtone = null
            runCatching { if (current.isPlaying) current.stop() }
        }
        tones?.let { current ->
            tones = null
            runCatching {
                current.stopTone()
                current.release()
            }
        }
    }
}
