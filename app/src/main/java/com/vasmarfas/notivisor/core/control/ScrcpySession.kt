package com.vasmarfas.notivisor.core.control

import android.content.Context
import android.view.MotionEvent
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.adb.AdbConnection
import com.vasmarfas.notivisor.core.adb.AdbIdentity
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.random.Random

object ScrcpySession {

    private const val SCOPE = "scrcpy"
    private const val ASSET = "scrcpy-server"
    private const val REMOTE_PATH = "/data/local/tmp/notivisor-scrcpy-server.jar"
    private const val SOCKET_RETRIES = 40
    private const val SOCKET_RETRY_MS = 200L
    private const val MAX_FRAME_BYTES = 12_000_000

    sealed interface Packet {
        data class Size(val width: Int, val height: Int) : Packet
        data class Frame(val data: ByteArray, val isConfig: Boolean) : Packet
    }

    private val _controlReady = MutableStateFlow(false)
    val controlReady: StateFlow<Boolean> = _controlReady.asStateFlow()

    private var dadb: Dadb? = null
    private var shell: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private var videoIn: InputStream? = null

    @Volatile
    private var controlOut: OutputStream? = null

    @Volatile
    private var major = 0

    fun isAvailable(context: Context): Boolean = AdbConnection.resolvePort(context) != null

    @Synchronized
    fun start(context: Context, wantVideo: Boolean): Boolean = runCatching {
        close()
        val port = AdbConnection.resolvePort(context) ?: run {
            BridgeLog.w(SCOPE, "no reachable adb port; wireless debugging off or unpaired")
            return false
        }
        val version = detectVersion(context) ?: run {
            BridgeLog.w(SCOPE, "could not read the bundled server's version")
            return false
        }
        major = version.substringBefore('.').toIntOrNull() ?: 0

        val connection = Dadb.create("127.0.0.1", port, AdbIdentity.keyPair(context))
        dadb = connection

        val staged = File(context.cacheDir, ASSET).apply {
            context.assets.open(ASSET).use { input -> outputStream().use(input::copyTo) }
        }
        connection.push(staged, REMOTE_PATH)

        val scid = "%08x".format(Random.nextInt() and 0x7FFFFFFF)
        val socketName = "scrcpy_$scid"
        val command = buildString {
            append("shell:CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server ")
            append("$version scid=$scid log_level=info ")
            append("video=$wantVideo audio=false control=true tunnel_forward=true cleanup=false ")
            append("send_dummy_byte=false")
            if (wantVideo) append(" video_codec=h264 max_size=0 video_bit_rate=$VIDEO_BIT_RATE")
        }
        BridgeLog.i(SCOPE, "starting server $version (video=$wantVideo)")
        shell = connection.open(command).also { stream ->
            Thread({
                runCatching {
                    stream.source.inputStream().bufferedReader().forEachLine {
                        BridgeLog.i(SCOPE, "[server] $it")
                    }
                }
            }, "scrcpy-log").apply { isDaemon = true }.start()
        }

        if (wantVideo) videoStream = openSocket(connection, socketName) ?: return false
        controlStream = openSocket(connection, socketName) ?: return false
        controlOut = controlStream!!.sink.outputStream()
        if (wantVideo) {
            videoIn = videoStream!!.source.inputStream()
            readVideoHeader(videoIn!!)
        }
        _controlReady.value = true
        BridgeLog.i(SCOPE, "session up")
        true
    }.getOrElse {
        BridgeLog.w(SCOPE, "session failed: ${it.message}")
        close()
        false
    }

    private fun openSocket(connection: Dadb, name: String): AdbStream? {
        repeat(SOCKET_RETRIES) {
            runCatching { connection.open("localabstract:$name") }.getOrNull()?.let { return it }
            Thread.sleep(SOCKET_RETRY_MS)
        }
        BridgeLog.w(SCOPE, "socket '$name' never appeared")
        return null
    }

    private fun readVideoHeader(input: InputStream) {
        val deviceMeta = ByteArray(64)
        readFully(input, deviceMeta)
        val codecMeta = ByteArray(if (major >= 4) 4 else 12)
        readFully(input, codecMeta)
        val name = String(deviceMeta, Charsets.UTF_8).trimEnd('\u0000')
        BridgeLog.i(SCOPE, "device '$name', codec header ${codecMeta.size} B")
    }

    fun readPacket(): Packet? {
        val input = videoIn ?: return null
        val header = ByteArray(12)
        return runCatching {
            readFully(input, header)
            val ptsAndFlags = ByteBuffer.wrap(header, 0, 8).order(ByteOrder.BIG_ENDIAN).long
            val length = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.BIG_ENDIAN).int

            if (major >= 4 && (ptsAndFlags and Long.MIN_VALUE) != 0L) {
                return@runCatching Packet.Size((ptsAndFlags and 0xFFFFFFFFL).toInt(), length)
            }
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                BridgeLog.w(SCOPE, "bogus frame length $length, stream is out of sync")
                return@runCatching null
            }
            val configMask = if (major >= 4) (1L shl 62) else Long.MIN_VALUE
            val payload = ByteArray(length).also { readFully(input, it) }
            Packet.Frame(payload, (ptsAndFlags and configMask) != 0L)
        }.getOrNull()
    }

    fun touch(action: Int, x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        write(
            ByteBuffer.allocate(32)
                .put(TYPE_INJECT_TOUCH_EVENT.toByte())
                .put(action.toByte())
                .putLong(POINTER_ID)
                .putInt(x)
                .putInt(y)
                .putShort(screenWidth.toShort())
                .putShort(screenHeight.toShort())
                .putShort(if (action == MotionEvent.ACTION_UP) 0 else PRESSURE_ONE)
                .putInt(if (action == MotionEvent.ACTION_DOWN) BUTTON_PRIMARY else 0)
                .putInt(if (action == MotionEvent.ACTION_UP) 0 else BUTTON_PRIMARY)
                .array()
        )
    }

    fun key(keyCode: Int) {
        write(keyMessage(0, keyCode))
        write(keyMessage(1, keyCode))
    }

    fun setClipboard(text: String, paste: Boolean = true): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return write(
            ByteBuffer.allocate(14 + bytes.size)
                .put(TYPE_SET_CLIPBOARD.toByte())
                .putLong(0L)
                .put(if (paste) 1 else 0)
                .putInt(bytes.size)
                .put(bytes)
                .array()
        )
    }

    private fun keyMessage(action: Int, keyCode: Int): ByteArray = ByteBuffer.allocate(14)
        .put(TYPE_INJECT_KEYCODE.toByte())
        .put(action.toByte())
        .putInt(keyCode)
        .putInt(0)
        .putInt(0)
        .array()

    private fun write(bytes: ByteArray): Boolean {
        val stream = controlOut ?: return false
        return runCatching {
            synchronized(this) {
                stream.write(bytes)
                stream.flush()
            }
        }.onFailure {
            BridgeLog.w(SCOPE, "control write failed: ${it.message}")
            _controlReady.value = false
            controlOut = null
        }.isSuccess
    }

    @Synchronized
    fun close() {
        controlOut = null
        videoIn = null
        _controlReady.value = false
        runCatching { controlStream?.close() }
        runCatching { videoStream?.close() }
        runCatching { shell?.close() }
        runCatching { dadb?.close() }
        controlStream = null
        videoStream = null
        shell = null
        dadb = null
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) throw java.io.EOFException("stream ended after $read of ${buffer.size} B")
            read += count
        }
    }

    private fun detectVersion(context: Context): String? = runCatching {
        ZipInputStream(context.assets.open(ASSET)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "META-INF/MANIFEST.MF" -> zip.bufferedReader().readLines()
                        .find { it.startsWith("Scrcpy-Version:") }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.let { return@runCatching it }

                    "AndroidManifest.xml" -> scanForSemver(zip.readBytes())
                        ?.let { return@runCatching it }
                }
                entry = zip.nextEntry
            }
        }
        null
    }.getOrNull()

    private fun scanForSemver(data: ByteArray): String? {
        val semver = Regex("^\\d+\\.\\d+(\\.\\d+)*$")
        var i = 0
        while (i < data.size - 4) {
            if (data[i].toInt() in 0x30..0x39 && data[i + 1] == 0.toByte()) {
                val text = StringBuilder()
                var j = i
                while (j + 1 < data.size && data[j + 1] == 0.toByte()) {
                    val c = (data[j].toInt() and 0xFF).toChar()
                    if (!c.isDigit() && c != '.') break
                    text.append(c)
                    j += 2
                }
                val candidate = text.toString()
                if (candidate.contains('.') && semver.matches(candidate)) return candidate
            }
            i++
        }
        return null
    }

    private const val VIDEO_BIT_RATE = 8_000_000
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_SET_CLIPBOARD = 9
    private const val BUTTON_PRIMARY = 1 shl 0

    private const val PRESSURE_ONE: Short = -1
    private const val POINTER_ID = -42L
}
