package com.vasmarfas.notivisor.core.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object ProjectionSession {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var running = false

    private val ready = LinkedBlockingQueue<CapturePacket>()

    @Synchronized
    fun start(context: Context, resultCode: Int, data: Intent, config: CastOptions): Boolean = runCatching {
        close()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(resultCode, data) ?: run {
            BridgeLog.w(SCOPE, "system refused the screen capture consent")
            return false
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                BridgeLog.i(SCOPE, "capture stopped by the system")
                running = false
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = context.resources.displayMetrics
        val (width, height) = fit(metrics.widthPixels, metrics.heightPixels, config.quality.longSide)
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate.takeIf { it > 0 } ?: bitRateFor(width, height, config.fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_US)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        display = proj.createVirtualDisplay(
            "notivisor-cast",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )

        running = true
        ready += CapturePacket.Size(width, height)
        Thread { drain(codec) }.apply { isDaemon = true; name = "projection-video"; start() }
        if (config.sound) runCatching { startSound(context, proj) }
            .onFailure { BridgeLog.w(SCOPE, "casting without sound: ${it.message}") }
        BridgeLog.i(SCOPE, "capturing at ${width}x$height, ${config.fps} fps")
        true
    }.getOrElse {
        BridgeLog.w(SCOPE, "projection failed: ${it.message}")
        close()
        false
    }

    fun readPacket(): CapturePacket? =
        if (!running) null else ready.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    fun requestKeyframe() {
        val codec = encoder ?: return
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    @Synchronized
    fun close() {
        running = false
        val record = recorder
        recorder = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { display?.release() }
        display = null
        val codec = encoder
        encoder = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { projection?.stop() }
        projection = null
        ready.clear()
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (encoder === codec) {
            val index = try {
                codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (_: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                ready += CapturePacket.Frame(bytes, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun startSound(context: Context, proj: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            BridgeLog.i(SCOPE, "no audio permission, casting without sound")
            return
        }
        val capture = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioSession.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(SOUND_CHUNK_BYTES * 8)
            .setAudioPlaybackCaptureConfig(capture)
            .build()
        record.startRecording()
        recorder = record
        Thread {
            while (recorder === record) {
                val chunk = ByteArray(SOUND_CHUNK_BYTES)
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break
                ready += CapturePacket.Sound(if (read == chunk.size) chunk else chunk.copyOf(read))
            }
        }.apply { isDaemon = true; name = "projection-sound"; start() }
    }

    private fun fit(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        val scale = minOf(1f, longSide.toFloat() / maxOf(width, height))
        return align(width * scale) to align(height * scale)
    }

    private fun align(value: Float): Int = (value.toInt() / 16) * 16

    private fun bitRateFor(width: Int, height: Int, fps: Int): Int =
        (width.toLong() * height * fps * BITS_PER_PIXEL).toInt().coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)

    private const val SCOPE = "projection"
    private const val MIME = "video/avc"
    private const val I_FRAME_INTERVAL_S = 2
    private const val REPEAT_FRAME_US = 100_000L
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val READ_TIMEOUT_MS = 8_000L
    private const val BITS_PER_PIXEL = 0.1
    private const val MIN_BIT_RATE = 2_000_000
    private const val MAX_BIT_RATE = 20_000_000
    private const val SOUND_CHUNK_BYTES = 3_840
}
