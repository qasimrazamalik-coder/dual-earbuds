package com.dualearbuds.host.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds this phone's addresses on the joined Wi-Fi and on its own hotspot / USB tethering. */
internal object HostNetwork {

    fun addresses(context: Context): List<HostAddress> =
        LinkBuilder.addresses(interfaceAddresses(), wifiAddress(context))

    /** IPv4 address of a joined Wi-Fi (or Ethernet) network, or null. */
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
                        val ip = addr.hostAddress ?: continue
                        out.add(ni.name to ip)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }
}
