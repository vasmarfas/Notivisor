package com.vasmarfas.notivisor.core.transport

import android.Manifest
import android.os.Build

object LocalNetwork {

    val permissions: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)
    } else {
        emptyList()
    }
}
