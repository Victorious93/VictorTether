package com.victorious.tether.util

import com.victorious.tether.data.IpCandidate
import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    private val AP_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "wlan2", "rndis", "p2p", "bt-pan")

    /**
     * Private IPv4 addresses on this device. Android exposes no public API for "the hotspot
     * interface", so this is a heuristic: interfaces whose names look like an access point
     * are listed first. The user should confirm which address clients can actually reach.
     */
    fun candidateAddresses(): List<IpCandidate> {
        val found = mutableListOf<IpCandidate>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in ifaces) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    val name = ni.name.lowercase()
                    found += IpCandidate(ni.name, addr.hostAddress.orEmpty(), AP_PREFIXES.any { name.startsWith(it) })
                }
            }
        }
        return found.sortedByDescending { it.looksLikeAccessPoint }
    }

    fun humanBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }

    /** Standard Wi-Fi QR payload. H:true lets scanners join a hidden network. */
    fun wifiQrPayload(ssid: String, password: String, hidden: Boolean): String {
        fun esc(s: String) = buildString {
            for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) }
        }
        return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};${if (hidden) "H:true;" else ""};"
    }
}
