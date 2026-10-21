package com.dualearbuds.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the address other phones should use: the Wi-Fi address, else this phone's hotspot address. */
internal object HostNetwork {

    data class Info(val address: String?, val onWifi: Boolean)

    fun current(context: Context): Info {
        val wifiAddress = wifiAddress(context)
        if (wifiAddress != null) return Info(wifiAddress, true)
        return Info(pickAddress(interfaceAddresses()), false)
    }

    /** IPv4 address of a connected Wi-Fi (or Ethernet) network, or null. */
    @Suppress("DEPRECATION")
    private fun wifiAddress(context: Context): String? {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                val v4 = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                if (v4 != null) return v4.hostAddress
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun interfaceAddresses(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifaces) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                } catch (_: Throwable) {
                    continue
                }
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        out.add(ni.name to (addr.hostAddress ?: continue))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private val cellularPrefixes = listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "tun", "ppp", "dummy", "ipsec", "seth", "radio")
    private val hotspotPrefixes = listOf("ap", "swlan", "softap", "wlan", "wigig", "bt-pan", "rndis", "usb", "eth")

    /** Picks a hotspot-like interface address from (interface name, IPv4) pairs. */
    fun pickAddress(candidates: List<Pair<String, String>>): String? {
        val usable = candidates.filter { (name, _) -> cellularPrefixes.none { name.startsWith(it) } }
        for (prefix in hotspotPrefixes) {
            usable.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return usable.firstOrNull()?.second
    }
}
