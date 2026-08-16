package com.vasmarfas.notivisor.phone.service

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.system.exitProcess

class RemoteInputService : IRemoteInput.Stub() {

    private val injector: ((InputEvent) -> Unit)? = runCatching {
        val manager = InputManager::class.java
            .getMethod("getInstance")
            .invoke(null)
        val inject = manager.javaClass
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        return@runCatching { event: InputEvent ->
            inject.invoke(manager, event, INJECT_ASYNC)
            Unit
        }
    }.getOrNull()

    override fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (!motion(MotionEvent.ACTION_DOWN, x, y, now, now)) return shellFallback("input tap ${x.toInt()} ${y.toInt()}")
        motion(MotionEvent.ACTION_UP, x, y, now, now + TAP_MS)
    }

    override fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Int) {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        val start = SystemClock.uptimeMillis()
        if (!motion(MotionEvent.ACTION_DOWN, fromX, fromY, start, start)) {
            return shellFallback(
                "input swipe ${fromX.toInt()} ${fromY.toInt()} ${toX.toInt()} ${toY.toInt()} $duration"
            )
        }

        for (step in 1 until STEPS) {
            val fraction = step.toFloat() / STEPS
            motion(
                MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * fraction,
                fromY + (toY - fromY) * fraction,
                start,
                start + (duration * fraction).toLong(),
            )
            Thread.sleep((duration / STEPS).toLong())
        }
        motion(MotionEvent.ACTION_UP, toX, toY, start, start + duration)
    }

    override fun key(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val send = injector ?: return shellFallback("input keyevent $keyCode")
        runCatching {
            send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            send(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }.onFailure { shellFallback("input keyevent $keyCode") }
    }

    override fun destroy() = exitProcess(0)

    private fun motion(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): Boolean {
        val send = injector ?: return false
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = SOURCE_TOUCHSCREEN
        }
        return runCatching { send(event) }.isSuccess.also { event.recycle() }
    }

    private fun shellFallback(command: String) {
        runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).waitFor() }
    }

    private companion object {
        const val INJECT_ASYNC = 0
        const val SOURCE_TOUCHSCREEN = 0x1002
        const val TAP_MS = 40L
        const val MIN_SWIPE_MS = 40
        const val MAX_SWIPE_MS = 2_000
        const val STEPS = 12
    }
}
