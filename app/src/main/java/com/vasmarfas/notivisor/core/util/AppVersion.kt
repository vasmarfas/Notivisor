package com.vasmarfas.notivisor.core.util

import android.content.Context
import android.os.Build

object AppVersion {

    fun name(context: Context): String? = info(context)?.versionName

    @Suppress("DEPRECATION")
    fun code(context: Context): Int = info(context)?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
        else it.versionCode
    } ?: 0

    private fun info(context: Context) = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
}
