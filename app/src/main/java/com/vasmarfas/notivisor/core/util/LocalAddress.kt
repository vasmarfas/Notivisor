package com.vasmarfas.notivisor.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

fun localAddress(context: Context): String? = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
    @Suppress("DEPRECATION")
    val networks = manager.allNetworks.toList()
    val wifi = networks.firstOrNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
    val wifiAddress = wifi?.let { manager.getLinkProperties(it) }
        ?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address }
    if (wifiAddress != null) return@runCatching wifiAddress.hostAddress

    val claimed = networks.mapNotNull { manager.getLinkProperties(it)?.interfaceName }.toSet()
    NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback && it.name !in claimed }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
