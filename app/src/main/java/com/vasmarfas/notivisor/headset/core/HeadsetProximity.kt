package com.vasmarfas.notivisor.headset.core

import android.content.Context
import com.vasmarfas.notivisor.core.adb.LocalShell
import com.vasmarfas.notivisor.core.util.BridgeLog

object HeadsetProximity {

    @Volatile
    private var lastKnown: Boolean? = null

    @Volatile
    private var probed = false

    fun set(context: Context, overridden: Boolean): Boolean? {
        LocalShell.run(context, if (overridden) OVERRIDE else RESTORE) ?: return null
        val state = query(context)
        lastKnown = state
        BridgeLog.i(SCOPE, "sensor ${if (overridden) "overridden" else "restored"}, now off=$state")
        return state
    }

    fun state(context: Context): Boolean? {
        if (!probed) {
            probed = true
            Thread { lastKnown = query(context) }.apply { isDaemon = true }.start()
        }
        return lastKnown
    }

    fun query(context: Context): Boolean? {
        val dump = LocalShell.run(context, "dumpsys $POWER_SERVICE") ?: return null
        val line = dump.lineSequence().firstOrNull { it.contains(STATE_LINE) } ?: return null
        return when (line.substringAfter(STATE_LINE).trim()) {
            "CLOSE" -> true
            "DISABLED" -> false
            else -> null
        }
    }

    private const val SCOPE = "proximity"
    private const val POWER_SERVICE = "oculus.internal.power.IVrPowerManager/default"
    private const val OVERRIDE = "am broadcast -a com.oculus.vrpowermanager.prox_close"
    private const val RESTORE = "am broadcast -a com.oculus.vrpowermanager.automation_disable"
    private const val STATE_LINE = "Virtual proximity state:"
}
