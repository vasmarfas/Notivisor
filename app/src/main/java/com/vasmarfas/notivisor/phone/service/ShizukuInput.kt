package com.vasmarfas.notivisor.phone.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

object ShizukuInput {

    private const val SCOPE = "shizuku"
    private const val PERMISSION_REQUEST = 4711

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile
    private var remote: IRemoteInput? = null

    private val userService by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.vasmarfas.notivisor",
                RemoteInputService::class.java.name,
            )
        )
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.let { IRemoteInput.Stub.asInterface(it) }
            _available.value = remote != null
            BridgeLog.i(SCOPE, "shell input service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            _available.value = false
            BridgeLog.i(SCOPE, "shell input service lost")
        }
    }

    fun connect(context: Context) {
        val ready = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!ready) {
            _available.value = false
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
            return
        }
        runCatching { Shizuku.bindUserService(userService, connection) }
            .onFailure { BridgeLog.w(SCOPE, "could not bind the shell service: ${it.message}") }
    }

    fun tap(x: Float, y: Float): Boolean = call { it.tap(x, y) }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Int): Boolean =
        call { it.swipe(fromX, fromY, toX, toY, durationMs) }

    fun key(keyCode: Int): Boolean = call { it.key(keyCode) }

    private inline fun call(block: (IRemoteInput) -> Unit): Boolean {
        val service = remote ?: return false
        return runCatching { block(service) }
            .onFailure {
                BridgeLog.w(SCOPE, "shell input failed: ${it.message}")
                remote = null
                _available.value = false
            }
            .isSuccess
    }
}
