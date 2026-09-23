package com.eljuliodev.servidormc

import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN IPv4 address of this device, for players to connect to. */
object LanAddress {

    fun get(): String? {
        val addresses = NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { iface.name to it.hostAddress }
            }
            ?.toList()
            .orEmpty()
        // Wi-Fi first; fall back to any private IPv4 (e.g. hotspot/tethering).
        return (addresses.firstOrNull { it.first.startsWith("wlan") } ?: addresses.firstOrNull())?.second
    }
}
