package com.vasmarfas.notivisor.core.protocol

import java.io.DataInputStream
import java.nio.ByteBuffer

object ScreenControl {

    const val TAP = 1
    const val SWIPE = 2
    const val KEY = 3
    const val POINTER = 4

    const val KEY_BACK = 0
    const val KEY_HOME = 1
    const val KEY_RECENTS = 2

    sealed interface Event {
        data class Tap(val x: Float, val y: Float) : Event
        data class Swipe(
            val fromX: Float,
            val fromY: Float,
            val toX: Float,
            val toY: Float,
            val durationMs: Int,
        ) : Event

        data class Key(val code: Int) : Event

        data class Pointer(val action: Int, val x: Float, val y: Float) : Event
    }

    fun tap(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(9).put(TAP.toByte()).putFloat(x).putFloat(y).array()

    fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Int,
    ): ByteArray = ByteBuffer.allocate(21)
        .put(SWIPE.toByte())
        .putFloat(fromX).putFloat(fromY)
        .putFloat(toX).putFloat(toY)
        .putInt(durationMs)
        .array()

    fun key(code: Int): ByteArray =
        ByteBuffer.allocate(2).put(KEY.toByte()).put(code.toByte()).array()

    fun pointer(action: Int, x: Float, y: Float): ByteArray = ByteBuffer.allocate(10)
        .put(POINTER.toByte())
        .put(action.toByte())
        .putFloat(x)
        .putFloat(y)
        .array()

    fun read(input: DataInputStream): Event? = when (input.readByte().toInt()) {
        TAP -> Event.Tap(input.readFloat(), input.readFloat())
        SWIPE -> Event.Swipe(
            input.readFloat(),
            input.readFloat(),
            input.readFloat(),
            input.readFloat(),
            input.readInt(),
        )

        KEY -> Event.Key(input.readByte().toInt())
        POINTER -> Event.Pointer(input.readByte().toInt(), input.readFloat(), input.readFloat())
        else -> null
    }
}
