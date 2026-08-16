package com.vasmarfas.notivisor.core.util

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BridgeLog {

    const val TAG = "Notivisor"
    private const val CAPACITY = 300

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>(CAPACITY)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun d(scope: String, message: String) = write('D', scope, message)
    fun i(scope: String, message: String) = write('I', scope, message)
    fun w(scope: String, message: String) = write('W', scope, message)

    fun redact(value: CharSequence?): String = when {
        value == null -> "null"
        value.isEmpty() -> "empty"
        else -> "${value.length} chars"
    }

    fun e(scope: String, message: String, error: Throwable? = null) {
        write(
            'E',
            scope,
            if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message}"
        )
    }

    private fun write(level: Char, scope: String, message: String) {
        val line = "$scope: $message"
        when (level) {
            'D' -> Log.d(TAG, line)
            'W' -> Log.w(TAG, line)
            'E' -> Log.e(TAG, line)
            else -> Log.i(TAG, line)
        }
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast("${stamp.format(Date())} $level $line")
            _lines.value = buffer.toList()
        }
    }

    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun share(context: Context) {
        val text = snapshot().joinToString("\n")
        if (text.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { w("log", "nothing takes a shared log here: ${it.message}") }
    }
}
