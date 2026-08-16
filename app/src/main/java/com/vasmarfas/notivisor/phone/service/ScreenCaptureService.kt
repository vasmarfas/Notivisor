package com.vasmarfas.notivisor.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.vasmarfas.notivisor.MainActivity
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.ScreenControl
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.control.ScrcpySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    enum class Mode { SCRCPY, PROJECTION }

    private var mode = Mode.SCRCPY
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var drainThread: Thread? = null
    private var controlThread: Thread? = null

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var awaitingKeyframe = true

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        val data = intentExtra(intent)
        mode = if (data == null) Mode.SCRCPY else Mode.PROJECTION

        goForeground()

        if (data == null) {
            startScrcpyMode()
        } else {
            startProjectionMode(intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0, data)
        }
        return START_NOT_STICKY
    }

    private fun startScrcpyMode() {
        acceptThread = Thread { acceptLoop() }.apply { isDaemon = true; start() }
        _running.value = true
        BridgeLog.i(SCOPE, "ready, capture starts when a headset connects")
    }

    private fun serveWithScrcpy(socket: Socket) {
        if (!ScrcpySession.start(applicationContext, wantVideo = true)) {
            BridgeLog.w(SCOPE, "scrcpy would not start for this viewer")
            runCatching { socket.close() }
            return
        }

        var size: ScrcpySession.Packet.Size? = null
        while (size == null && client === socket) {
            when (val packet = ScrcpySession.readPacket()) {
                is ScrcpySession.Packet.Size -> size = packet
                is ScrcpySession.Packet.Frame -> Unit
                null -> {
                    BridgeLog.w(SCOPE, "scrcpy stream ended before announcing a size")
                    return
                }
            }
        }
        val announced = size ?: return

        val handshake = runCatching {
            socket.getOutputStream().apply {
                write(ByteBuffer.allocate(8).putInt(announced.width).putInt(announced.height).array())
                flush()
            }
        }
        if (handshake.isFailure) {
            runCatching { socket.close() }
            return
        }
        BridgeLog.i(SCOPE, "scrcpy streaming at ${announced.width}x${announced.height}")

        while (client === socket && !socket.isClosed) {
            when (val packet = ScrcpySession.readPacket()) {
                is ScrcpySession.Packet.Frame -> sendFrame(packet.data)

                is ScrcpySession.Packet.Size -> if (
                    packet.width != announced.width || packet.height != announced.height
                ) {
                    BridgeLog.i(SCOPE, "screen resized, dropping the viewer to re-handshake")
                    return
                }

                null -> return
            }
        }
    }

    private fun startProjectionMode(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            BridgeLog.w(SCOPE, "system refused the screen capture consent")
            stopSelf()
            return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                BridgeLog.i(SCOPE, "capture stopped by the system")
                stopStreaming()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val metrics = resources.displayMetrics
        val (width, height) = scaledSize(metrics.widthPixels, metrics.heightPixels)

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_US)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        virtualDisplay = proj.createVirtualDisplay(
            "notivisor-mirror",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null,
        )

        acceptThread = Thread { acceptLoop(width, height) }.apply { isDaemon = true; start() }
        drainThread = Thread { drainLoop(codec) }.apply { isDaemon = true; start() }

        Thread { ScrcpySession.start(applicationContext, wantVideo = false) }
            .apply { isDaemon = true; start() }
        _running.value = true
        BridgeLog.i(SCOPE, "capturing at ${width}x$height via MediaProjection")
    }

    private fun acceptLoop(width: Int = 0, height: Int = 0) {
        val server = runCatching { ServerSocket(TransportConfig.SCREEN_STREAM_PORT) }
            .getOrElse {
                BridgeLog.w(SCOPE, "could not open the streaming port: ${it.message}")
                return
            }
        serverSocket = server
        BridgeLog.i(SCOPE, "streaming server listening on ${TransportConfig.SCREEN_STREAM_PORT}")
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            BridgeLog.i(SCOPE, "viewer connected: ${socket.inetAddress}")
            client?.let { old -> runCatching { old.close() } }
            client = socket
            controlThread?.interrupt()
            controlThread = Thread { controlLoop(socket) }.apply { isDaemon = true; start() }

            if (mode == Mode.SCRCPY) {

                runCatching { serveWithScrcpy(socket) }
                    .onFailure { BridgeLog.w(SCOPE, "viewer session ended: ${it.message}") }
                ScrcpySession.close()
                if (client === socket) client = null
                continue
            }

            val handshake = runCatching {
                socket.getOutputStream().apply {
                    write(ByteBuffer.allocate(8).putInt(width).putInt(height).array())
                    flush()
                }
            }
            if (handshake.isFailure) {
                runCatching { socket.close() }
                continue
            }

            awaitingKeyframe = true
            codecConfig?.let { config -> sendFrame(config) }
            requestKeyframe()
        }
    }

    private fun controlLoop(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val metrics = resources.displayMetrics
        var warnedDisabled = false
        while (client === socket && !socket.isClosed) {
            val event = runCatching { ScreenControl.read(input) }.getOrNull() ?: break
            val delivered = InputRouter.handle(event, metrics.widthPixels, metrics.heightPixels)
            if (!delivered && !warnedDisabled) {
                warnedDisabled = true
                BridgeLog.w(SCOPE, "headset sent input but no injection path is enabled")
            }
        }
    }

    private fun requestKeyframe() {
        val codec = encoder ?: return
        runCatching {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { BridgeLog.w(SCOPE, "could not ask for a keyframe: ${it.message}") }
    }

    private fun drainLoop(codec: MediaCodec) {
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
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (isConfig) codecConfig = bytes
                when {
                    isConfig -> sendFrame(bytes)
                    isKeyFrame -> {
                        awaitingKeyframe = false
                        sendFrame(bytes)
                    }

                    !awaitingKeyframe -> sendFrame(bytes)

                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun sendFrame(bytes: ByteArray) {
        val socket = client ?: return
        runCatching {
            synchronized(socket) {
                val out = socket.getOutputStream()
                out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                out.write(bytes)
                out.flush()
            }
        }.onFailure {
            BridgeLog.i(SCOPE, "viewer disconnected")
            runCatching { socket.close() }
            client = null
        }
    }

    private fun stopStreaming() {

        if (stopping) return
        stopping = true
        _running.value = false
        acceptThread?.interrupt()
        drainThread?.interrupt()
        controlThread?.interrupt()
        acceptThread = null
        drainThread = null
        controlThread = null
        ScrcpySession.close()
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { client?.close() }
        client = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val current = encoder
        encoder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
        runCatching { projection?.stop() }
        projection = null
        BridgeLog.i(SCOPE, "capture stopped")
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun goForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_mirror),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.channel_mirror))
            .setContentText(getString(R.string.mirror_notification_text))
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val type = if (mode == Mode.PROJECTION) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun intentExtra(intent: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_DIMENSION) return macroblockAlign(width) to macroblockAlign(height)
        val scale = MAX_DIMENSION.toFloat() / longest
        return macroblockAlign((width * scale).toInt()) to macroblockAlign((height * scale).toInt())
    }

    private fun bitrateFor(width: Int, height: Int): Int =
        (width.toLong() * height * FRAME_RATE * BITS_PER_PIXEL)
            .toInt()
            .coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)

    private fun macroblockAlign(value: Int): Int = (value / 16) * 16

    companion object {
        private const val SCOPE = "mirror"
        private const val CHANNEL_ID = "screen_mirror"
        private const val NOTIFICATION_ID = 3
        private const val MIME = "video/avc"
        private const val FRAME_RATE = 30
        private const val BITS_PER_PIXEL = 0.12
        private const val MIN_BIT_RATE = 3_000_000
        private const val MAX_BIT_RATE = 16_000_000
        private const val MAX_DIMENSION = 4096
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val REPEAT_FRAME_US = 100_000L

        const val ACTION_STOP = "com.vasmarfas.notivisor.mirror.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun startWithScrcpy(context: Context) {
            context.startForegroundService(Intent(context, ScreenCaptureService::class.java))
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
