package com.vasmarfas.notivisor.core.util

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
}
