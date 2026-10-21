package com.dualearbuds.host.net

/** What kind of network the host is serving on. */
enum class NetKind {
    /** This phone's own hotspot: only people the owner let in are on it. */
    HOTSPOT,
    /** Wi-Fi the phone joined. Android can't tell home from café Wi-Fi, so it is treated as public/unknown. */
    WIFI,
    NONE
}

/** mDNS/DNS-SD advertisement rules (protocol v3.1 "Discovery"). Pure logic. */
object Discovery {
    const val SERVICE_TYPE = "_dualearbuds._tcp"
    private const val MAX_TXT_VALUE = 200

    /**
     * Whether "Show on this network" (which puts the link key in the TXT record) is on:
     * the user's choice for this session if any, else on for a hotspot, off for other networks.
     */
    fun showOnNetwork(kind: NetKind, userChoice: Boolean?): Boolean = userChoice ?: (kind == NetKind.HOTSPOT)

    /** TXT attributes: `v=3`, `name`, `host=android`, `pin=0|1`, and `k` only when [show]. */
    fun txt(deviceName: String, key: String, pin: Boolean, show: Boolean): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["v"] = "3"
        m["name"] = utf8Prefix(deviceName.ifBlank { "Android phone" }, MAX_TXT_VALUE)
        m["host"] = "android"
        m["pin"] = if (pin) "1" else "0"
        if (show) m["k"] = key.trim().uppercase()
        return m
    }

    /** Service instance name (max 63 bytes UTF-8 per DNS label). */
    fun instanceName(deviceName: String): String = utf8Prefix("Dual Earbuds - ${deviceName.ifBlank { "Android" }}", 63)

    /** Longest prefix of [s] whose UTF-8 encoding fits [maxBytes] without splitting a character. */
    fun utf8Prefix(s: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < s.length) {
            val cp = s.codePointAt(end)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            end += Character.charCount(cp)
        }
        return s.substring(0, end)
    }
}
