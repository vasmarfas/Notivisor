package com.vasmarfas.notivisor.headset.core

import android.content.Context
import com.vasmarfas.notivisor.core.adb.AdbAccess
import com.vasmarfas.notivisor.core.adb.AdbConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HeadsetAdb {

    private val _access = MutableStateFlow<AdbAccess?>(null)
    private val _checking = MutableStateFlow(false)

    val access: StateFlow<AdbAccess?> = _access.asStateFlow()
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun probe(context: Context, wake: Boolean = true): AdbAccess =
        AdbConnection.access(context, wake).also { _access.value = it }

    fun check(context: Context, wake: Boolean = true) {
        if (!_checking.compareAndSet(expect = false, update = true)) return
        val app = context.applicationContext
        Thread {
            try {
                probe(app, wake)
            } finally {
                _checking.value = false
            }
        }.apply { isDaemon = true; name = "adb-check"; start() }
    }
}
