package com.dualearbuds.host.net

/** Builds the join link and picks the address other phones should use. Pure logic. */
object LinkBuilder {
    const val DEFAULT_PORT = 8790

    /**
     * The exact URL the QR code holds: `http://<ip>:<port>/?k=<KEY>`. The room PIN is never part of
     * it (protocol v3.1). Returns null without an address or port.
     */
    fun link(address: String?, port: Int, key: String): String? {
        if (address.isNullOrBlank() || port !in 1..65535 || key.isBlank()) return null
        val host = if (address.contains(':')) "[$address]" else address
        return "http://$host:$port/?k=${key.trim().uppercase()}"
    }

    /** Valid user-chosen port (1024–65535), else null. */
    fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1024..65535 }

    private val cellularPrefixes = listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "tun", "ppp", "dummy", "ipsec", "seth", "radio")
    private val hotspotPrefixes = listOf("ap", "swlan", "softap", "wlan", "wigig", "bt-pan", "rndis", "usb", "eth")

    /**
     * Picks a hotspot-like interface from (interface name, IPv4) pairs: never a mobile-data
     * interface; hotspot names first (ap0, swlan0, softap0, wlan1…).
     */
    fun pickHotspotAddress(candidates: List<Pair<String, String>>): String? {
        val usable = candidates.filter { (name, _) -> cellularPrefixes.none { name.startsWith(it) } }
        for (prefix in hotspotPrefixes) {
            usable.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return usable.firstOrNull()?.second
    }
}
