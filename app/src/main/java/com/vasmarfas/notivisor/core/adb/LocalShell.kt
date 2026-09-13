package com.vasmarfas.notivisor.core.adb

import android.content.Context
import com.vasmarfas.notivisor.core.util.BridgeLog
import dadb.Dadb

object LocalShell {

    private const val SCOPE = "shell"

    fun run(context: Context, vararg commands: String): String? {
        val port = AdbConnection.resolvePort(context) ?: run {
            BridgeLog.w(SCOPE, "no reachable adb port on this device")
            return null
        }
        return runCatching {
            Dadb.create("127.0.0.1", port, AdbIdentity.keyPair(context)).use { connection ->
                commands.joinToString("\n") { command ->
                    val response = connection.shell(command)
                    if (response.exitCode != 0) {
                        BridgeLog.d(SCOPE, "'$command' exited ${response.exitCode}")
                    }
                    response.output
                }
            }
        }.onFailure { BridgeLog.w(SCOPE, "shell failed: ${it.message}") }.getOrNull()
    }
}
