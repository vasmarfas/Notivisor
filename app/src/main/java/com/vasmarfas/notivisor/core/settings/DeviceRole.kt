package com.vasmarfas.notivisor.core.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.vasmarfas.notivisor.core.util.BridgeLog

enum class DeviceRole { PHONE, HEADSET }

object RoleDetector {

    private const val SCOPE = "role"

    private val HEADSET_VENDORS = setOf("oculus", "meta", "facebook", "pico", "picovr", "bytedance")

    private val HEADSET_FEATURES = setOf(
        "android.hardware.vr.headtracking",
        "android.hardware.vr.high_performance",
        "android.software.vr.mode",
    )

    private val HEADSET_FEATURE_PREFIXES =
        listOf("oculus.", "com.oculus.", "pico.", "picovr.", "pvr.")

    fun resolve(context: Context, settings: BridgeSettings): DeviceRole =
        settings.roleOverride ?: detect(context)

    fun detect(context: Context): DeviceRole {
        val reason = detectWithReason(context)
        BridgeLog.i(SCOPE, "detected ${reason.first} (${reason.second})")
        return reason.first
    }

    fun detectWithReason(context: Context): Pair<DeviceRole, String> {
        val vendor = Build.MANUFACTURER.lowercase()
        if (HEADSET_VENDORS.any { vendor.contains(it) }) {
            return DeviceRole.HEADSET to "manufacturer ${Build.MANUFACTURER}"
        }

        val pm = context.packageManager
        HEADSET_FEATURES.firstOrNull { pm.hasSystemFeature(it) }?.let {
            return DeviceRole.HEADSET to "feature $it"
        }

        val features = runCatching {
            pm.systemAvailableFeatures.mapNotNull { it.name }
        }.getOrDefault(emptyList())
        features.firstOrNull { name -> HEADSET_FEATURE_PREFIXES.any { name.startsWith(it) } }?.let {
            return DeviceRole.HEADSET to "feature $it"
        }

        val noTelephony = !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        if (noTelephony && noTouch) return DeviceRole.HEADSET to "no telephony, no touchscreen"

        return DeviceRole.PHONE to "no headset signals"
    }
}
