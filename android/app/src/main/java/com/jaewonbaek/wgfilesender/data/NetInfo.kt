package com.jaewonbaek.wgfilesender.data

import java.net.Inet4Address
import java.net.NetworkInterface

object NetInfo {
    /** IPv4 addresses on tunnel interfaces (tun*) — best-effort for showing this device's
     *  WireGuard address so it can be read off when pairing from the other side. */
    fun tunnelIPv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())
}
