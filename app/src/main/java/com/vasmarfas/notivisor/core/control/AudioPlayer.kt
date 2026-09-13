package com.vasmarfas.notivisor.core.control

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.max

class AudioPlayer {

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)

    @Volatile
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        val minimum = AudioTrack.getMinBufferSize(
            AudioSession.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            BridgeLog.w(SCOPE, "no stereo 48 kHz output on this phone")
            return false
        }
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioSession.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minimum, BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            BridgeLog.w(SCOPE, "could not open the output: ${it.message}")
            return false
        }

        built.play()
        track = built
        thread = Thread(::drain, "cast-audio-out").apply { isDaemon = true; start() }
        BridgeLog.i(SCOPE, "playing the headset's audio")
        return true
    }

    fun write(pcm: ByteArray) {
        if (track == null) return
        if (!queue.offer(pcm)) {
            queue.poll()
            queue.offer(pcm)
        }
    }

    fun stop() {
        val current = track ?: return
        track = null
        thread?.interrupt()
        thread = null
        queue.clear()
        runCatching {
            current.pause()
            current.flush()
            current.stop()
        }
        current.release()
    }

    private fun drain() {
        while (track != null) {
            val chunk = runCatching { queue.take() }.getOrNull() ?: return
            val current = track ?: return
            if (current.write(chunk, 0, chunk.size) < 0) {
                BridgeLog.w(SCOPE, "output stopped accepting audio")
                return
            }
        }
    }

    private companion object {
        const val SCOPE = "castaudio"
        const val QUEUE_CHUNKS = 16
        const val BUFFER_BYTES = 32 * 1024
    }
}
