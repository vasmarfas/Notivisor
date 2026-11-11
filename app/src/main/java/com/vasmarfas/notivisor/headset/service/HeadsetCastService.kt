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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.vasmarfas.notivisor.MainActivity
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.adb.AdbAccess
import com.vasmarfas.notivisor.core.control.AudioSession
import com.vasmarfas.notivisor.core.control.CapturePacket
import com.vasmarfas.notivisor.core.control.CastConfig
import com.vasmarfas.notivisor.core.control.CastSource
import com.vasmarfas.notivisor.core.control.MagicCastSession
import com.vasmarfas.notivisor.core.control.ProjectionSession
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.CastHub
import com.vasmarfas.notivisor.headset.core.CastViewer
import com.vasmarfas.notivisor.headset.core.HeadsetAdb
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.headset.core.HeadsetWebApi
import com.vasmarfas.notivisor.headset.core.PhoneViewer
import com.vasmarfas.notivisor.headset.core.WebCastServer
import com.vasmarfas.notivisor.headset.core.WebViewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket

data class CastStatus(
    val phone: Boolean = false,
    val webPort: Int? = null,
    val browsers: Int = 0,
    val source: CastSource? = null,
    val capturing: Boolean = false,
    val error: String? = null,
) {
    val running: Boolean get() = phone || webPort != null
}

class HeadsetCastService : Service() {

    private val control = HandlerThread("cast-control").apply { start() }
    private val handler = Handler(control.looper)
    private val hub = CastHub(::requestKeyframe) { handler.post(::audienceChanged) }

    @Volatile
    private var source = CastSource.MAGIC

    @Volatile
    private var generation = 0

    private var config = CastConfig()
    private var chosen = CastSource.MAGIC
    private var consent: Pair<Int, Intent>? = null
    private var capture: Thread? = null
    private var audio: Thread? = null
    private var dialing: Thread? = null
    private var phone: CastViewer? = null

    @Volatile
    private var phoneWanted = false
    private var web: WebCastServer? = null
    private var webPort = 0
    private var webPassword: String? = null
    private var error: String? = null
    private var live = false
    private var attempts = 0
    private var retrying = false

    private val idleStop = Runnable {
        if (hub.audience == 0) {
            cancelRetry()
            stopCapture()
            publish()
        }
    }

    private val retry = Runnable {
        retrying = false
        if (capture == null && hub.audience > 0) startCapture()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) goForeground(projecting(intent))
        handler.post {
            when (intent?.action) {
                ACTION_STOP -> shutdown()
                ACTION_STOP_PHONE -> {
                    dropPhone()
                    stopIfIdle()
                }

                ACTION_STOP_WEB -> {
                    stopWeb()
                    stopIfIdle()
                }

                ACTION_REFRESH -> reconfigure()

                else -> begin(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        if (!_status.value.running) {
            val settings = BridgeSettings.get(this)
            source = CastSource.parse(intent?.getStringExtra(EXTRA_SOURCE))
            chosen = settings.castSource
            config = settings.castConfig
            consent = projectionConsent(intent)
            error = null
            attempts = 0
        }
        if (intent?.getBooleanExtra(EXTRA_PHONE, false) == true) connectPhone()
        if (intent?.getBooleanExtra(EXTRA_WEB, false) == true) startWeb()
        if (source == CastSource.PROJECTION && capture == null && (phoneWanted || web != null)) startCapture()
        publish()
        stopIfIdle()
    }

    private fun connectPhone() {
        if (phoneWanted) return
        val host = BridgeSettings.get(this).tcpHost ?: run {
            BridgeLog.w(SCOPE, "no phone address stored, cannot cast over Wi-Fi")
            return
        }
        phoneWanted = true
        dialing = Thread {
            val socket = dial(host)
            val me = Thread.currentThread()
            handler.post {
                if (dialing !== me) {
                    runCatching { socket?.close() }
                    return@post
                }
                dialing = null
                when {
                    socket == null -> {
                        BridgeLog.w(SCOPE, "the phone never accepted the stream")
                        phoneWanted = false
                        publish()
                        stopIfIdle()
                    }

                    else -> {
                        val viewer = PhoneViewer(socket) { gone -> handler.post { viewerClosed(gone) } }
                        phone = viewer
                        hub.add(viewer)
                        BridgeLog.i(SCOPE, "the phone is watching")
                    }
                }
            }
        }.apply { isDaemon = true; name = "cast-dial"; start() }
    }

    private fun dial(host: String): Socket? {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (!phoneWanted) return null
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

    private fun viewerClosed(viewer: CastViewer) {
        hub.remove(viewer)
        if (viewer !== phone) return
        phone = null
        phoneWanted = false
        if (viewer.outgrown) {
            BridgeLog.i(SCOPE, "the picture changed size, calling the phone again")
            connectPhone()
            return
        }
        BridgeLog.i(SCOPE, "the phone stopped watching")
        publish()
        stopIfIdle()
    }

    private fun reconfigure() {
        if (!_status.value.running) return
        val settings = BridgeSettings.get(this)
        if (web != null && (settings.webPort != webPort || settings.webPassword != webPassword)) {
            stopWeb()
            startWeb()
            if (web == null) {
                publish()
                stopIfIdle()
                return
            }
        }
        val wanted = settings.castSource.takeIf { it != chosen } ?: source
        chosen = settings.castSource
        val next = settings.castConfig
        val projecting = source == CastSource.PROJECTION || wanted == CastSource.PROJECTION
        if (projecting || (wanted == source && next == config)) {
            publish()
            return
        }
        BridgeLog.i(SCOPE, "applying new cast settings via $wanted")
        cancelRetry()
        stopCapture()
        source = wanted
        config = next
        error = null
        attempts = 0
        if (hub.audience > 0) startCapture()
        publish()
    }

    private fun dropPhone() {
        phoneWanted = false
        dialing?.interrupt()
        dialing = null
        phone?.let(hub::remove)
        phone = null
    }

    private fun startWeb() {
        if (web != null) return
        val settings = BridgeSettings.get(this)
        val page = assets.open(PAGE).use { it.readBytes() }
        webPort = settings.webPort
        webPassword = settings.webPassword
        val server = WebCastServer(webPort, webPassword, page, hub, HeadsetWebApi(applicationContext, hub))
        runCatching { server.start() }.onFailure {
            BridgeLog.w(SCOPE, "browser view did not start: ${it.message}")
            error = getString(R.string.cast_web_port_busy, webPort)
            return
        }
        web = server
    }

    private fun stopWeb() {
        val server = web ?: return
        server.stop()
        web = null
        hub.closeAll(browsersOnly = true)
    }

    private fun audienceChanged() {
        if (hub.audience > 0) {
            handler.removeCallbacks(idleStop)
            if (capture == null && !retrying && (source != CastSource.PROJECTION || consent != null)) startCapture()
        } else if ((capture != null || retrying) && source != CastSource.PROJECTION) {
            handler.removeCallbacks(idleStop)
            handler.postDelayed(idleStop, IDLE_STOP_MS)
        }
        publish()
    }

    private fun startCapture() {
        cancelRetry()
        val mine = ++generation
        val selected = source
        val settings = config
        val token = consent
        consent = null
        hub.notice(WebViewer.NOTICE_STARTING)
        capture = Thread { capture(mine, selected, settings, token) }
            .apply { isDaemon = true; name = "cast-capture"; start() }
        BridgeLog.i(SCOPE, "capture starting via $selected")
    }

    private fun capture(mine: Int, selected: CastSource, settings: CastConfig, token: Pair<Int, Intent>?) {
        val context = applicationContext
        if (selected == CastSource.MAGIC_ADB || selected == CastSource.SCRCPY) {
            val reason = when (HeadsetAdb.probe(context)) {
                AdbAccess.READY -> null
                AdbAccess.UNAUTHORIZED -> R.string.cast_error_adb_unauthorized
                AdbAccess.OFFLINE -> R.string.cast_error_adb_offline
            }
            if (reason != null) {
                handler.post { if (generation == mine) trouble(reason) }
                return
            }
        }
        val started = when (selected) {
            CastSource.MAGIC -> MagicCastSession.start(context, settings, viaAdb = false)
            CastSource.MAGIC_ADB -> MagicCastSession.start(context, settings, viaAdb = true)
            CastSource.SCRCPY -> ScrcpySession.start(
                context,
                wantVideo = true,
                maxSize = settings.quality.longSide,
                videoOptions = scrcpyOptions(settings),
            )

            CastSource.PROJECTION -> token != null &&
                    ProjectionSession.start(context, token.first, token.second, settings)
        }
        if (!started) {
            handler.post { if (generation == mine) trouble(failureFor(selected)) }
            return
        }

        var announced = false
        while (generation == mine) {
            when (val packet = readPacket(selected) ?: break) {
                is CapturePacket.Size -> {
                    hub.resize(packet)
                    if (!announced) {
                        announced = true
                        BridgeLog.i(SCOPE, "casting at ${packet.width}x${packet.height} via $selected")
                        handler.post { if (generation == mine) went(mine, selected, settings) }
                    }
                }

                is CapturePacket.Frame -> if (packet.isConfig) hub.config(packet.data) else hub.frame(packet.data)
                is CapturePacket.Sound -> hub.sound(packet.data)
            }
        }
        handler.post {
            if (generation != mine) return@post
            trouble(if (announced) R.string.cast_error_stalled else R.string.cast_error_no_picture)
        }
    }

    private fun went(mine: Int, selected: CastSource, settings: CastConfig) {
        live = true
        error = null
        attempts = 0
        hub.notice(WebViewer.NOTICE_LIVE)
        if (selected == CastSource.SCRCPY && settings.sound) startAudio(mine)
        publish()
    }

    private fun readPacket(selected: CastSource): CapturePacket? = runCatching {
        when (selected) {
            CastSource.MAGIC, CastSource.MAGIC_ADB -> MagicCastSession.readPacket()
            CastSource.SCRCPY -> ScrcpySession.readPacket()
            CastSource.PROJECTION -> ProjectionSession.readPacket()
        }
    }.getOrNull()

    private fun requestKeyframe() {
        when (source) {
            CastSource.MAGIC, CastSource.MAGIC_ADB -> MagicCastSession.requestKeyframe()
            CastSource.PROJECTION -> ProjectionSession.requestKeyframe()
            CastSource.SCRCPY -> Unit
        }
    }

    private fun startAudio(mine: Int) {
        if (generation != mine) return
        audio = Thread {
            if (!AudioSession.start(applicationContext)) {
                BridgeLog.i(SCOPE, "casting without sound")
                return@Thread
            }
            while (generation == mine) {
                val chunk = AudioSession.read() ?: break
                hub.sound(chunk)
            }
        }.apply { isDaemon = true; name = "cast-audio"; start() }
    }

    private fun stopCapture() {
        val thread = capture ?: return
        capture = null
        generation++
        thread.interrupt()
        audio?.interrupt()
        audio = null
        when (source) {
            CastSource.MAGIC, CastSource.MAGIC_ADB -> MagicCastSession.close(applicationContext)
            CastSource.SCRCPY -> {
                AudioSession.close()
                ScrcpySession.close()
            }

            CastSource.PROJECTION -> ProjectionSession.close()
        }
        hub.reset()
        live = false
        BridgeLog.i(SCOPE, "capture stopped")
    }

    private fun trouble(reason: Int) {
        val text = getString(reason)
        BridgeLog.w(SCOPE, "capture failed: $text")
        stopCapture()
        if (web == null) {
            if (phoneWanted) HeadsetBridge.send(Envelope(action = Action.CAST_STOP, text = text, data = source.name))
            shutdown()
            error = text
            publish()
            return
        }
        error = text
        hub.notice(WebViewer.NOTICE_TROUBLE, text)
        if (source != CastSource.PROJECTION && hub.audience > 0) {
            retrying = true
            handler.postDelayed(retry, RETRY_STEP_MS * (1 shl attempts.coerceAtMost(RETRY_DOUBLINGS)))
            attempts++
        }
        publish()
    }

    private fun cancelRetry() {
        retrying = false
        handler.removeCallbacks(retry)
    }

    private fun stopIfIdle() {
        if (!phoneWanted && web == null) shutdown()
    }

    private fun shutdown() {
        handler.removeCallbacks(idleStop)
        cancelRetry()
        dropPhone()
        stopWeb()
        hub.closeAll()
        stopCapture()
        consent = null
        publish()
        stopSelf()
    }

    private fun publish() {
        _status.value = CastStatus(
            phone = phoneWanted,
            webPort = web?.localPort,
            browsers = hub.browsers,
            source = if (phoneWanted || web != null) source else null,
            capturing = live,
            error = error,
        )
    }

    override fun onDestroy() {
        handler.post {
            if (_status.value.running) shutdown()
            control.quitSafely()
        }
        super.onDestroy()
    }

    private fun projecting(intent: Intent?): Boolean {
        val current = _status.value
        if (current.running) return current.source == CastSource.PROJECTION
        return CastSource.parse(intent?.getStringExtra(EXTRA_SOURCE)) == CastSource.PROJECTION &&
                intent?.hasExtra(EXTRA_DATA) == true
    }

    private fun goForeground(projecting: Boolean) {
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
                if (projecting) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun projectionConsent(intent: Intent?): Pair<Int, Intent>? {
        intent ?: return null
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        return data?.let { intent.getIntExtra(EXTRA_RESULT_CODE, 0) to it }
    }

    private fun scrcpyOptions(settings: CastConfig): String = buildString {
        append("max_fps=${settings.fps} video_codec_options=i-frame-interval=1")
        if (settings.bitRate > 0) append(" video_bit_rate=${settings.bitRate}")
    }

    private fun failureFor(selected: CastSource): Int = when (selected) {
        CastSource.MAGIC, CastSource.MAGIC_ADB -> R.string.cast_error_no_service
        CastSource.SCRCPY -> R.string.cast_error_no_scrcpy
        CastSource.PROJECTION -> R.string.cast_error_no_projection
    }

    companion object {
        private const val SCOPE = "cast"
        private const val CHANNEL_ID = "headset_cast"
        private const val NOTIFICATION_ID = 6
        private const val CONNECT_ATTEMPTS = 30
        private const val CONNECT_RETRY_MS = 500L
        private const val IDLE_STOP_MS = 15_000L
        private const val RETRY_STEP_MS = 2_000L
        private const val RETRY_DOUBLINGS = 2
        private const val PAGE = "cast.html"

        private const val ACTION_STOP = "com.vasmarfas.notivisor.cast.STOP"
        private const val ACTION_STOP_PHONE = "com.vasmarfas.notivisor.cast.STOP_PHONE"
        private const val ACTION_STOP_WEB = "com.vasmarfas.notivisor.cast.STOP_WEB"
        private const val ACTION_REFRESH = "com.vasmarfas.notivisor.cast.REFRESH"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_PHONE = "phone"
        private const val EXTRA_WEB = "web"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        private val _status = MutableStateFlow(CastStatus())
        val status: StateFlow<CastStatus> = _status.asStateFlow()

        fun start(
            context: Context,
            source: CastSource,
            phone: Boolean = false,
            web: Boolean = false,
            consent: Pair<Int, Intent>? = null,
        ) {
            val intent = Intent(context, HeadsetCastService::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_PHONE, phone)
                .putExtra(EXTRA_WEB, web)
            consent?.let { (code, data) -> intent.putExtra(EXTRA_RESULT_CODE, code).putExtra(EXTRA_DATA, data) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) = command(context, ACTION_STOP)

        fun stopPhone(context: Context) = command(context, ACTION_STOP_PHONE)

        fun stopWeb(context: Context) = command(context, ACTION_STOP_WEB)

        fun refresh(context: Context) = command(context, ACTION_REFRESH)

        private fun command(context: Context, action: String) {
            if (!_status.value.running) return
            context.startService(Intent(context, HeadsetCastService::class.java).setAction(action))
        }
    }
}
