package com.dualearbuds.companion.net.discovery

import com.dualearbuds.companion.net.HostKind
import java.util.Locale

/**
 * A host found by mDNS / DNS-SD (`_dualearbuds._tcp`, docs/protocol.md v3.1 "Discovery").
 * [key] is present only while the host's network is Private (or "Show on this network" is on): then one tap
 * joins; without it the user must scan the QR or paste the link.
 */
data class DiscoveredHost(
    val serviceName: String,
    val name: String,
    val kind: HostKind,
    val address: String,
    val port: Int,
    val pinRequired: Boolean,
    val key: String?,
    val protocol: Int
) {
    val canJoinDirectly: Boolean
        get() = key != null

    val display: String
        get() = if (address.contains(':')) "[$address]:$port" else "$address:$port"

    companion object {
        private val KEY = Regex("^[A-Z2-7]{8}$")

        /** Builds a host from a resolved service and its TXT record (values as UTF-8 strings). */
        fun fromTxt(serviceName: String, address: String, port: Int, txt: Map<String, String?>): DiscoveredHost? {
            if (address.isBlank() || port !in 1..65535) return null
            val t = txt.mapKeys { it.key.lowercase(Locale.ROOT) }
            val key = t["k"]?.trim()?.uppercase(Locale.ROOT)?.takeIf { KEY.matches(it) }
            return DiscoveredHost(
                serviceName = serviceName,
                name = t["name"]?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: serviceName,
                kind = HostKind.fromWire(t["host"]),
                address = address.removePrefix("/").substringBefore('%'),
                port = port,
                pinRequired = t["pin"]?.trim() == "1",
                key = key,
                protocol = t["v"]?.trim()?.toIntOrNull() ?: 0
            )
        }
    }
}

/**
 * NsdManager resolves only one service at a time (a second resolveService call fails with
 * FAILURE_ALREADY_ACTIVE). This queue hands out one name at a time, de-duplicates, and lets a lost service
 * leave the queue. Pure, not thread-safe (used on the main thread).
 */
class ResolveQueue {
    private val pending = ArrayDeque<String>()
    var inFlight: String? = null
        private set

    /** Queues [name]; returns the name to resolve now (if nothing was in flight), else null. */
    fun offer(name: String): String? {
        if (name == inFlight || name in pending) return null
        pending.addLast(name)
        return takeNextIfIdle()
    }

    /** The current resolve finished (success or failure): returns the next name to resolve, if any. */
    fun done(): String? {
        inFlight = null
        return takeNextIfIdle()
    }

    fun remove(name: String) {
        pending.remove(name)
    }

    fun clear() {
        pending.clear()
        inFlight = null
    }

    val size: Int
        get() = pending.size + if (inFlight != null) 1 else 0

    private fun takeNextIfIdle(): String? {
        if (inFlight != null) return null
        val next = pending.removeFirstOrNull() ?: return null
        inFlight = next
        return next
    }
}
