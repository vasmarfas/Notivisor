package com.vasmarfas.notivisor.core.control

import android.content.Context
import android.content.Intent
import androidx.core.os.bundleOf
import com.vasmarfas.notivisor.core.adb.AdbConnection
import com.vasmarfas.notivisor.core.adb.AdbIdentity
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.util.BridgeLog
import dadb.Dadb
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object MagicCastSession {

    private var dadb: Dadb? = null

    @Volatile
    private var direct = false

    @Volatile
    private var session: String? = null

    @Volatile
    private var server: ServerSocket? = null
    private val sockets = mutableListOf<Socket>()
    private var accepting: Thread? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var announced: CapturePacket.Size? = null

    @Volatile
    private var config = CastOptions()

    private val ready = LinkedBlockingQueue<CapturePacket>()
    private val sendLock = Any()
    private var appId = 0
    private var datagramId = 1
    private var topic = 0

    @Synchronized
    fun start(context: Context, config: CastOptions, viaAdb: Boolean): Boolean = runCatching {
        release(context)
        this.config = config
        val listener = ServerSocket(TransportConfig.MAGIC_CAST_PORT, BACKLOG, InetAddress.getByName(LOOPBACK))
        listener.soTimeout = if (viaAdb) ACCEPT_TIMEOUT_MS else DIRECT_ACCEPT_TIMEOUT_MS
        server = listener

        val first = if (viaAdb) connectViaAdb(context, listener) else connectDirectly(context, listener)
        if (first == null) {
            release(context)
            return false
        }

        running = true
        output = first.getOutputStream()
        reader(first, "conn1", owns = true)
        accepting = Thread { acceptLoop(listener) }.apply { isDaemon = true; start() }
        BridgeLog.i(SCOPE, "casting service connected ${if (viaAdb) "via adb" else "directly"}")
        true
    }.getOrElse {
        BridgeLog.w(SCOPE, "session failed: ${it.message}")
        release(context)
        false
    }

    fun readPacket(): CapturePacket? =
        if (!running) null else ready.poll(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)

    fun requestKeyframe() {
        if (running) send(message(TYPE_RESYNC_FRAME, 0))
    }

    fun close(context: Context) {
        runCatching { server?.close() }
        release(context)
    }

    @Synchronized
    private fun release(context: Context) {
        if (running) send(ByteBuffer.allocate(4).putInt(TYPE_STOP_CASTING).array())
        running = false
        output = null
        announced = null
        ready.clear()
        appId = 0
        datagramId = 1
        topic = 0
        accepting?.interrupt()
        accepting = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        runCatching { server?.close() }
        dadb?.let { connection ->
            runCatching { tearDown(connection) }
            runCatching { connection.close() }
        }
        if (direct) runCatching { context.stopService(castingService()) }
        server = null
        dadb = null
        direct = false
        session = null
    }

    private fun connectViaAdb(context: Context, listener: ServerSocket): Socket? {
        val port = AdbConnection.resolvePort(context) ?: run {
            BridgeLog.w(SCOPE, "no reachable adb port; wireless debugging off or unpaired")
            return null
        }
        val connection = Dadb.create(LOOPBACK, port, AdbIdentity.keyPair(context))
        dadb = connection
        AdbConnection.keep(context, connection)

        bringUp(connection, connection.exec("getprop ro.serialno").trim())
        return accept(listener) ?: run {
            BridgeLog.w(SCOPE, "casting service never called back, retrying the bring-up")
            recover(connection)
            accept(listener)
        }
    }

    private fun connectDirectly(context: Context, listener: ServerSocket): Socket? {
        val installed = runCatching { context.packageManager.getPackageInfo(CASTING_SERVICE, 0) }.isSuccess
        if (!installed) {
            BridgeLog.w(SCOPE, "$CASTING_SERVICE is not installed")
            return null
        }
        direct = true
        val address = arrayOf("$LOOPBACK:${listener.localPort}")
        for (attempt in 1..DIRECT_ATTEMPTS) {
            if (attempt > 1) {
                BridgeLog.w(SCOPE, "casting service never called back, restarting it")
                context.stopService(castingService())
                Thread.sleep(SETTLE_MS)
            }
            val launch = castingService("$CASTING_SERVICE.START_SERVICE")
                .putExtras(bundleOf(EXTRA_CLIENT to context.packageName))
            if (context.startForegroundService(launch) == null) {
                BridgeLog.w(SCOPE, "the casting service refused to start")
                return null
            }
            Thread.sleep(INIT_MS)
            val id = UUID.randomUUID().toString().also { session = it }
            val call = bundleOf(EXTRA_SERVERS to address, EXTRA_SESSION to id)
            context.sendBroadcast(Intent("$CASTING_SERVICE.CONNECT").setPackage(CASTING_SERVICE).putExtras(call))
            accept(listener)?.let { return it }
        }
        return null
    }

    private fun castingService(action: String? = null): Intent =
        Intent(action).setClassName(CASTING_SERVICE, "$CASTING_SERVICE.CastingService")

    private fun accept(listener: ServerSocket): Socket? =
        runCatching { listener.accept() }.getOrNull()?.also { register(it) }

    private fun acceptLoop(listener: ServerSocket) {
        var index = 1
        while (running) {
            val next = runCatching { listener.accept() }.getOrNull() ?: continue
            register(next)
            index++
            reader(next, "conn$index", owns = false)
        }
    }

    private fun register(socket: Socket) {
        socket.tcpNoDelay = true
        synchronized(sockets) { sockets += socket }
    }

    private fun reader(socket: Socket, label: String, owns: Boolean) {
        Thread {
            runCatching { readLoop(socket.getInputStream()) }
                .onFailure { BridgeLog.d(SCOPE, "$label ended: ${it.message}") }
            if (owns) output = null
            runCatching { socket.close() }
        }.apply { isDaemon = true; name = "magiccast-$label"; start() }
    }

    private fun bringUp(connection: Dadb, serial: String) {
        BridgeLog.i(SCOPE, "bringing casting up on port ${TransportConfig.MAGIC_CAST_PORT} at ${config.fps} fps")

        connection.exec("am force-stop $CASTING_SERVICE")
        connection.exec("am force-stop com.oculus.metacam")
        Thread.sleep(SETTLE_MS)
        connection.exec("am startservice -a STOP_CASTING $METACAM")
        Thread.sleep(SETTLE_MS)
        connection.exec("am broadcast -a $CASTING_SERVICE.STOP_CASTING")
        connection.exec("am broadcast -a $CASTING_SERVICE.DISABLE_PANEL_STREAMING")
        Thread.sleep(SETTLE_MS)
        connection.exec("setprop debug.oculus.command_line_media_capture true")
        connection.exec("setprop debug.oculus.magic.enabled 1")
        connection.exec("setprop debug.oculus.magic.serialNumber $serial")
        connection.exec("setprop debug.oculus.magic.maxFps ${config.fps}")
        connection.exec("setprop debug.oculus.magic.minFps ${config.fps}")
        connection.exec("setprop debug.oculus.magic.port ${TransportConfig.MAGIC_CAST_PORT}")
        connection.exec("am start-foreground-service -n $CASTING_SERVICE/.CastingService --ez use_openxr true")
        Thread.sleep(INIT_MS)
        val session = UUID.randomUUID().toString()
        connection.exec(
            "am startservice -a CONNECT_MAGIC_CASTING " +
                    "--es session_id $session --es SESSION_ID $session $METACAM"
        )
    }

    private fun recover(connection: Dadb) {
        connection.exec("am broadcast -a com.oculus.magicisland.sdk.intent.CONNECT")
        connection.exec("am start-foreground-service -n $CASTING_SERVICE/.CastingService --ez use_openxr true")
        Thread.sleep(INIT_MS)
        connection.exec("am broadcast -a $CASTING_SERVICE.CONNECT")
    }

    private fun tearDown(connection: Dadb) {
        connection.exec("am broadcast -a $CASTING_SERVICE.STOP_CASTING")
        connection.exec("am broadcast -a $CASTING_SERVICE.DISABLE_PANEL_STREAMING")
        connection.exec("setprop debug.oculus.magic.enabled 0")
    }

    private fun Dadb.exec(command: String): String {
        val response = shell(command)
        if (response.exitCode != 0) {
            BridgeLog.d(SCOPE, "'$command' exited ${response.exitCode}: ${response.errorOutput.trim()}")
        }
        return response.output
    }

    private fun readLoop(stream: InputStream) {
        var group: Group? = null
        var fragments: Fragments? = null

        while (running) {
            val payload = readFrame(stream) ?: return

            if (payload.size >= 8 && payload.startsWith(GROUP_MARKER)) {
                val count = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                group = if (count in 1..MAX_GROUP) Group(count) else null
                continue
            }

            val header: AppHeader
            val body: ByteArray
            val pending = group
            if (pending != null) {
                pending.parts += payload
                if (pending.parts.size < pending.count) continue
                group = null
                header = appHeader(pending.parts.first()) ?: continue
                body = pending.parts.drop(1).join()
            } else {
                header = appHeader(payload) ?: continue
                body = payload.copyOfRange(APP_HEADER, payload.size)
            }
            appId = header.appId

            if (header.partialCount <= 1) {
                fragments = null
                handle(body)
                continue
            }
            if (header.partialIndex == 0) {
                fragments = Fragments(header, mutableListOf(body))
                continue
            }

            val started = fragments
            val fits = started != null &&
                    started.header.appId == header.appId &&
                    started.header.messageId == header.messageId &&
                    started.header.partialCount == header.partialCount &&
                    header.partialIndex == started.parts.size
            if (!fits) {
                fragments = null
                continue
            }
            started!!.parts += body
            if (started.parts.size < header.partialCount) continue
            fragments = null
            handle(started.parts.join())
        }
    }

    private fun handle(body: ByteArray) {
        when (body.int(0) ?: return) {
            MESSAGE_HANDSHAKE -> {
                val quality = config.quality
                BridgeLog.i(SCOPE, "handshake, asking for ${quality.width}x${quality.height} at ${config.fps} fps")
                mapOf(
                    "maxWidth" to quality.width.toString(),
                    "maxHeight" to quality.height.toString(),
                    "bitrate" to config.bitRate.takeIf { it > 0 }?.toString().orEmpty(),
                ).forEach { (name, value) -> send(property("debug.oculus.magic.$name", value)) }
                send(eyeFovConfig())
                send(message(TYPE_SET_AUDIO_STATE, if (config.sound) 1 else 0))
                send(startCasting())
            }

            MESSAGE_PING -> body.int(4)?.let { send(message(TYPE_PONG, it)) }

            MESSAGE_LAYER_CONFIGURATION -> {
                BridgeLog.i(SCOPE, "layer configuration, activating the stream")
                send(message(TYPE_RESYNC_FRAME, 0))
                send(message(TYPE_INPUT_FORWARDING, 0))
                send(message(TYPE_RESYNC_FRAME, 0))
                send(message(TYPE_ACTIVATE_LAYER, 0))
                send(message(TYPE_RESYNC_FRAME, 0))
            }

            MESSAGE_VIDEO_SEGMENT -> if (body.size > VIDEO_HEADER) {
                queueVideo(body.copyOfRange(VIDEO_HEADER, body.size))
            }

            MESSAGE_AUDIO_SEGMENT -> if (announced != null && body.size > AUDIO_HEADER) {
                ready += CapturePacket.Sound(littleEndian(body, AUDIO_HEADER))
            }
        }
    }

    private fun queueVideo(payload: ByteArray) {
        val prefix = H264.configPrefixLength(payload)
        if (prefix == 0) {
            if (announced == null) return
            ready += CapturePacket.Frame(payload, isConfig = false)
            return
        }
        val config = payload.copyOfRange(0, prefix)
        if (announced == null) {
            val (width, height) = H264.dimensions(config) ?: return
            val size = CapturePacket.Size(width, height)
            announced = size
            BridgeLog.i(SCOPE, "stream is ${width}x$height")
            ready += size
        }
        ready += CapturePacket.Frame(config, isConfig = true)
        if (prefix < payload.size) {
            ready += CapturePacket.Frame(payload.copyOfRange(prefix, payload.size), isConfig = false)
        }
    }

    private fun littleEndian(body: ByteArray, from: Int): ByteArray {
        val pcm = ByteArray((body.size - from) and 1.inv())
        for (index in pcm.indices step 2) {
            pcm[index] = body[from + index + 1]
            pcm[index + 1] = body[from + index]
        }
        return pcm
    }

    private fun readFrame(stream: InputStream): ByteArray? {
        val header = ByteArray(8)
        if (!readFully(stream, header)) return null
        val words = ByteBuffer.wrap(header, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short
        val total = ((words.toInt() and 0xFFFF) + 1) * 4
        if (total < 8) throw IllegalStateException("bad XRSP length $total")
        val payload = ByteArray(total - 8)
        if (!readFully(stream, payload)) return null
        if (header[0].toInt() and FLAG_PADDED == 0 || payload.isEmpty()) return payload
        val padding = payload.last().toInt() and 0xFF
        return if (padding in 1..payload.size) payload.copyOf(payload.size - padding) else payload
    }

    private fun appHeader(payload: ByteArray): AppHeader? {
        if (payload.size < APP_HEADER) return null
        val header = AppHeader(
            appId = payload.int(0) ?: return null,
            messageId = payload.int(8) ?: return null,
            partialIndex = payload.int(12) ?: return null,
            partialCount = payload.int(16) ?: return null,
        )
        val datagram = payload.int(4) ?: return null
        val qos = payload.int(20) ?: return null
        val plausible = header.appId != 0 &&
                header.partialCount in 1..128 &&
                header.partialIndex < header.partialCount &&
                qos in 0..8 &&
                datagram in 0 until DATAGRAM_LIMIT &&
                header.messageId in 0 until DATAGRAM_LIMIT
        return header.takeIf { plausible }
    }

    private fun send(body: ByteArray) {
        val stream = output ?: return
        synchronized(sendLock) {
            val payload = ByteBuffer.allocate(APP_HEADER + body.size)
                .putInt(appId)
                .putInt(datagramId)
                .putInt(datagramId)
                .putInt(0)
                .putInt(1)
                .putInt(2)
                .put(body)
                .array()
            datagramId++

            val padded = (payload.size + 8 + 3) and 3.inv()
            val packet = ByteBuffer.allocate(padded).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(WORD0)
                .putShort((padded / 4 - 1).toShort())
                .putShort(topic.toShort())
                .putShort(0)
                .put(payload)
                .array()
            topic++

            runCatching {
                stream.write(packet)
                stream.flush()
            }.onFailure {
                BridgeLog.w(SCOPE, "could not answer the casting service: ${it.message}")
                output = null
            }
        }
    }

    private fun eyeFovConfig(): ByteArray = ByteBuffer.allocate(20)
        .putInt(TYPE_EYE_FOV_CONFIG)
        .putInt(0).putInt(0).putInt(0).putInt(0)
        .array()

    private fun startCasting(): ByteArray {
        val tail = session?.let(::string) ?: ByteArray(0)
        return ByteBuffer.allocate(28 + tail.size)
            .putInt(TYPE_START_CASTING)
            .putInt(config.quality.width)
            .putInt(config.quality.height)
            .putFloat(0f)
            .putInt(if (config.rightEye) 1 else 0)
            .putInt(config.fps)
            .putInt(if (config.stabilized) 1 else 0)
            .put(tail)
            .array()
    }

    private fun property(key: String, value: String): ByteArray {
        val name = string(key)
        val content = string(value)
        return ByteBuffer.allocate(4 + name.size + content.size)
            .putInt(TYPE_SET_PROPERTY)
            .put(name)
            .put(content)
            .array()
    }

    private fun string(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + ((bytes.size + 3) and 3.inv()))
            .putInt(bytes.size)
            .put(bytes)
            .array()
    }

    private fun message(type: Int, value: Int): ByteArray =
        ByteBuffer.allocate(8).putInt(type).putInt(value).array()

    private fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = stream.read(buffer, read, buffer.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun ByteArray.int(at: Int): Int? =
        if (size >= at + 4) ByteBuffer.wrap(this, at, 4).int else null

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.indices.all { this[it] == prefix[it] }

    private fun List<ByteArray>.join(): ByteArray {
        val joined = ByteArray(sumOf { it.size })
        var at = 0
        forEach { part ->
            part.copyInto(joined, at)
            at += part.size
        }
        return joined
    }

    private data class AppHeader(
        val appId: Int,
        val messageId: Int,
        val partialIndex: Int,
        val partialCount: Int,
    )

    private class Fragments(val header: AppHeader, val parts: MutableList<ByteArray>)

    private class Group(val count: Int) {
        val parts = mutableListOf<ByteArray>()
    }

    private const val SCOPE = "magiccast"
    private const val LOOPBACK = "127.0.0.1"
    private const val BACKLOG = 4
    private const val ACCEPT_TIMEOUT_MS = 12_000
    private const val DIRECT_ACCEPT_TIMEOUT_MS = 5_000
    private const val DIRECT_ATTEMPTS = 2
    private const val READ_TIMEOUT_MS = 8_000
    private const val SETTLE_MS = 500L
    private const val INIT_MS = 1_000L

    private const val METACAM = "com.oculus.metacam/com.oculus.metacam.casting.CastingService"
    private const val CASTING_SERVICE = "com.oculus.magicislandcastingservice"
    private const val EXTRA_CLIENT = "id"
    private const val EXTRA_SERVERS = "servers"
    private const val EXTRA_SESSION = "session_uuid"

    private const val APP_HEADER = 24
    private const val VIDEO_HEADER = 16
    private const val AUDIO_HEADER = 16
    private const val DATAGRAM_LIMIT = 1_000_000
    private const val MAX_GROUP = 1_024
    private const val WORD0: Short = 0x0210
    private const val FLAG_PADDED = 0x08

    private const val MESSAGE_HANDSHAKE = 1
    private const val MESSAGE_PING = 3
    private const val MESSAGE_VIDEO_SEGMENT = 100
    private const val MESSAGE_AUDIO_SEGMENT = 102
    private const val MESSAGE_LAYER_CONFIGURATION = 300

    private const val TYPE_PONG = 4
    private const val TYPE_START_CASTING = 7
    private const val TYPE_STOP_CASTING = 8
    private const val TYPE_SET_PROPERTY = 10
    private const val TYPE_RESYNC_FRAME = 101
    private const val TYPE_INPUT_FORWARDING = 205
    private const val TYPE_ACTIVATE_LAYER = 301
    private const val TYPE_SET_AUDIO_STATE = 503
    private const val TYPE_EYE_FOV_CONFIG = 600

    private val GROUP_MARKER = "MGIK".toByteArray(Charsets.US_ASCII)
}
