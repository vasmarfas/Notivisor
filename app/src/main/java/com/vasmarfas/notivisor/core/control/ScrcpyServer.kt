package com.vasmarfas.notivisor.core.control

import android.content.Context
import com.vasmarfas.notivisor.core.util.BridgeLog
import dadb.AdbStream
import dadb.Dadb
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.random.Random

internal object ScrcpyServer {

    fun version(context: Context): String? = runCatching {
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

    fun scid(): String = "%08x".format(Random.nextInt() and 0x7FFFFFFF)

    fun launch(
        context: Context,
        connection: Dadb,
        version: String,
        scid: String,
        options: String,
    ): AdbStream {
        stage(context, connection)

        val command = "shell:CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
                "$version scid=$scid log_level=info tunnel_forward=true cleanup=false " +
                "send_dummy_byte=false $options"
        return connection.open(command).also { stream ->
            Thread({
                runCatching {
                    stream.source.inputStream().bufferedReader().forEachLine {
                        BridgeLog.i(SCOPE, "[server] $it")
                    }
                }
            }, "scrcpy-log").apply { isDaemon = true }.start()
        }
    }

    fun open(connection: Dadb, scid: String): AdbStream? {
        repeat(SOCKET_RETRIES) {
            runCatching { connection.open("localabstract:scrcpy_$scid") }.getOrNull()
                ?.let { return it }
            Thread.sleep(SOCKET_RETRY_MS)
        }
        BridgeLog.w(SCOPE, "socket 'scrcpy_$scid' never appeared")
        return null
    }

    private fun stage(context: Context, connection: Dadb) {
        val staged = File(context.cacheDir, ASSET).apply {
            context.assets.open(ASSET).use { input -> outputStream().use(input::copyTo) }
        }
        val onDevice = connection.shell("stat -c %s $REMOTE_PATH").output.trim().toLongOrNull()
        if (onDevice == staged.length()) return
        connection.push(staged, REMOTE_PATH)
    }

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

    private const val SCOPE = "scrcpy"
    private const val ASSET = "scrcpy-server"
    private const val REMOTE_PATH = "/data/local/tmp/notivisor-scrcpy-server.jar"
    private const val SOCKET_RETRIES = 40
    private const val SOCKET_RETRY_MS = 200L
}
