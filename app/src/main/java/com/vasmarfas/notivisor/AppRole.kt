package com.vasmarfas.notivisor

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.settings.DeviceRole
import com.vasmarfas.notivisor.core.settings.RoleDetector
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.headset.service.ReceiverService
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import com.vasmarfas.notivisor.phone.service.BridgeService

object AppRole {

    fun current(context: Context): DeviceRole =
        RoleDetector.resolve(context, BridgeSettings.get(context))

    fun start(context: Context) {
        when (current(context)) {
            DeviceRole.PHONE -> {
                PhoneBridge.init(context)
                if (PhoneBridge.settings.enabled) {
                    BridgeService.start(context)
                    NotifyListener.rebind(context)
                }
            }

            DeviceRole.HEADSET -> {
                HeadsetBridge.init(context)
                ReceiverService.start(context)
            }
        }
    }

    fun stopEverything(context: Context) {
        PhoneBridge.shutdownIfRunning(context)
        HeadsetBridge.shutdownIfRunning(context)
    }

    fun quit(activity: Activity) {
        when (current(activity)) {
            DeviceRole.PHONE -> PhoneBridge.shutdown(activity)
            DeviceRole.HEADSET -> HeadsetBridge.shutdown(activity)
        }
        activity.finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, QUIT_GRACE_MS)
    }

    private const val QUIT_GRACE_MS = 400L

    fun override(context: Context, role: DeviceRole?) {
        stopEverything(context)
        BridgeSettings.get(context).roleOverride = role
    }
}
