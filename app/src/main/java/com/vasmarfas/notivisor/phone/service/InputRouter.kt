package com.vasmarfas.notivisor.phone.service

import android.view.KeyEvent
import android.view.MotionEvent
import com.vasmarfas.notivisor.core.protocol.ScreenControl
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.control.ScrcpySession
import kotlin.math.abs

object InputRouter {

    enum class Path { ADB, SHIZUKU, NONE }

    val path: Path
        get() = when {
            ScrcpySession.controlReady.value -> Path.ADB
            ShizukuInput.available.value -> Path.SHIZUKU
            else -> Path.NONE
        }

    fun handle(event: ScreenControl.Event, width: Int, height: Int): Boolean = when (path) {
        Path.SHIZUKU -> shizuku(event, width, height)
        Path.ADB -> scrcpy(event, width, height)
        Path.NONE -> false
    }

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private fun shizukuPointer(event: ScreenControl.Event.Pointer, width: Int, height: Int): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = System.currentTimeMillis()
            }

            MotionEvent.ACTION_UP -> {
                val duration = (System.currentTimeMillis() - downAt).toInt().coerceAtLeast(1)
                val moved = abs(event.x - downX) * width > SLOP_PX ||
                        abs(event.y - downY) * height > SLOP_PX
                return if (moved) {
                    ShizukuInput.swipe(
                        downX * width, downY * height,
                        event.x * width, event.y * height,
                        duration,
                    )
                } else {
                    ShizukuInput.tap(downX * width, downY * height)
                }
            }
        }
        return true
    }

    private fun shizuku(event: ScreenControl.Event, width: Int, height: Int): Boolean = when (event) {
        is ScreenControl.Event.Pointer -> shizukuPointer(event, width, height)
        is ScreenControl.Event.Tap -> ShizukuInput.tap(event.x * width, event.y * height)
        is ScreenControl.Event.Swipe -> ShizukuInput.swipe(
            event.fromX * width,
            event.fromY * height,
            event.toX * width,
            event.toY * height,
            event.durationMs,
        )

        is ScreenControl.Event.Key -> ShizukuInput.key(keyCode(event.code))
    }

    private fun scrcpy(event: ScreenControl.Event, width: Int, height: Int): Boolean {
        when (event) {

            is ScreenControl.Event.Pointer -> ScrcpySession.touch(
                event.action,
                (event.x * width).toInt(),
                (event.y * height).toInt(),
                width,
                height,
            )

            is ScreenControl.Event.Tap -> {
                val x = (event.x * width).toInt()
                val y = (event.y * height).toInt()
                ScrcpySession.touch(MotionEvent.ACTION_DOWN, x, y, width, height)
                ScrcpySession.touch(MotionEvent.ACTION_UP, x, y, width, height)
            }

            is ScreenControl.Event.Swipe -> {
                val fromX = event.fromX * width
                val fromY = event.fromY * height
                val toX = event.toX * width
                val toY = event.toY * height
                ScrcpySession.touch(MotionEvent.ACTION_DOWN, fromX.toInt(), fromY.toInt(), width, height)
                for (step in 1..SWIPE_STEPS) {
                    val fraction = step.toFloat() / SWIPE_STEPS
                    ScrcpySession.touch(
                        MotionEvent.ACTION_MOVE,
                        (fromX + (toX - fromX) * fraction).toInt(),
                        (fromY + (toY - fromY) * fraction).toInt(),
                        width,
                        height,
                    )
                    Thread.sleep((event.durationMs / SWIPE_STEPS).toLong().coerceIn(4, 40))
                }
                ScrcpySession.touch(MotionEvent.ACTION_UP, toX.toInt(), toY.toInt(), width, height)
                BridgeLog.d(
                    SCOPE,
                    "swipe ${fromX.toInt()},${fromY.toInt()} -> ${toX.toInt()},${toY.toInt()} " +
                            "in ${SWIPE_STEPS + 2} pointer events over ${event.durationMs} ms"
                )
            }

            is ScreenControl.Event.Key -> ScrcpySession.key(keyCode(event.code))
        }
        return true
    }

    private fun keyCode(code: Int): Int = when (code) {
        ScreenControl.KEY_HOME -> KeyEvent.KEYCODE_HOME
        ScreenControl.KEY_RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
        else -> KeyEvent.KEYCODE_BACK
    }

    private const val SCOPE = "input"
    private const val SWIPE_STEPS = 12
    private const val SLOP_PX = 24f
}
