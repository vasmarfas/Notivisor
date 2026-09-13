package com.vasmarfas.notivisor.headset.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vasmarfas.notivisor.MainActivity
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.control.CapturePacket
import com.vasmarfas.notivisor.core.control.CastSource
import com.vasmarfas.notivisor.core.control.MagicCastSession
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket
import java.nio.ByteBuffer

class HeadsetCastService : Service() {

    private var worker: Thread? = null
    private var source = CastSource.MAGIC
    private var announced: CapturePacket.Size? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCasting()
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        stopping = false
        announced = null
        source = CastSource.parse(intent?.getStringExtra(EXTRA_SOURCE))
        goForeground()
        _running.value = true
        worker = Thread { serve() }.apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun serve() {
        val host = BridgeSettings.get(this).tcpHost
        if (host == null) {
            BridgeLog.w(SCOPE, "no phone address stored, cannot cast over Wi-Fi")
            stopEverything()
            return
        }
        val viewer = dial(host)
        if (viewer == null) {
            BridgeLog.w(SCOPE, "the phone never accepted the stream")
            stopEverything()
            return
        }
        socket = viewer

        val started = when (source) {
            CastSource.MAGIC -> MagicCastSession.start(applicationContext)
            CastSource.SCRCPY -> ScrcpySession.start(applicationContext, wantVideo = true, maxSize = SCRCPY_MAX_SIZE)
        }
        if (!started) {
            fail(R.string.cast_error_no_adb)
            return
        }

        runCatching { pump(viewer) }
            .onFailure { BridgeLog.w(SCOPE, "cast session ended: ${it.message}") }
        if (announced == null && !stopping) fail(R.string.cast_error_no_picture) else stopEverything()
    }

    private fun fail(reason: Int) {
        BridgeLog.w(SCOPE, "cast failed: ${getString(reason)}")
        HeadsetBridge.send(
            Envelope(action = Action.CAST_STOP, text = getString(reason), data = source.name)
        )
        stopEverything()
    }

    private fun readPacket(): CapturePacket? = when (source) {
        CastSource.MAGIC -> MagicCastSession.readPacket()
        CastSource.SCRCPY -> ScrcpySession.readPacket()
    }

    private fun dial(host: String): Socket? {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (stopping) return null
            runCatching { Socket(host, TransportConfig.CAST_STREAM_PORT) }
                .getOrNull()
                ?.let {
                    it.tcpNoDelay = true
                    BridgeLog.i(SCOPE, "phone accepted the stream on attempt ${attempt + 1}")
                    return it
                }
            runCatching { Thread.sleep(CONNECT_RETRY_MS) }.onFailure { return null }
        }
        return null
    }

    private fun pump(viewer: Socket) {
        while (!stopping && socket === viewer && !viewer.isClosed) {
            when (val packet = readPacket()) {
                is CapturePacket.Size -> {
                    if (announced != null) {
                        BridgeLog.i(SCOPE, "stream resized, dropping the viewer to re-handshake")
                        return
                    }
                    announced = packet
                    viewer.getOutputStream().apply {
                        write(
                            ByteBuffer.allocate(8)
                                .putInt(packet.width)
                                .putInt(packet.height)
                                .array()
                        )
                        flush()
                    }
                    BridgeLog.i(SCOPE, "casting at ${packet.width}x${packet.height} via $source")
                }

                is CapturePacket.Frame -> {
                    if (announced == null) continue
                    send(viewer, packet.data)
                }

                null -> return
            }
        }
    }

    private fun send(viewer: Socket, bytes: ByteArray) {
        val out = viewer.getOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        out.write(bytes)
        out.flush()
    }

    private fun stopEverything() {
        stopCasting()
        stopSelf()
    }

    private fun stopCasting() {
        if (stopping) return
        stopping = true
        _running.value = false
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null

        Thread {
            MagicCastSession.close()
            ScrcpySession.close()
        }.apply { isDaemon = true }.start()
        BridgeLog.i(SCOPE, "casting stopped")
    }

    override fun onDestroy() {
        stopCasting()
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_cast),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.channel_cast))
            .setContentText(getString(R.string.cast_notification_text))
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val SCOPE = "cast"
        private const val CHANNEL_ID = "headset_cast"
        private const val NOTIFICATION_ID = 6
        private const val SCRCPY_MAX_SIZE = 1600
        private const val CONNECT_ATTEMPTS = 30
        private const val CONNECT_RETRY_MS = 500L

        const val ACTION_STOP = "com.vasmarfas.notivisor.cast.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        const val EXTRA_SOURCE = "source"

        fun start(context: Context, source: CastSource) {
            context.startForegroundService(
                Intent(context, HeadsetCastService::class.java)
                    .putExtra(EXTRA_SOURCE, source.name)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HeadsetCastService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
