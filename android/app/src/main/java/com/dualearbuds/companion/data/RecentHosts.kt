package com.dualearbuds.companion.data

import com.dualearbuds.companion.net.HostKind

/** A host this phone listened to (or checked) successfully. [link] is the canonical `http://IP:PORT/?k=KEY`. */
data class RecentHost(val link: String, val name: String, val kind: HostKind)

/**
 * The last [MAX] hosts, newest first, de-duplicated by address (IP:port — a *New link* on the same host replaces
 * the old key). Pure Kotlin: [encode]/[decode] turn the list into one preference string.
 */
object RecentHosts {
    const val MAX = 5

    fun add(list: List<RecentHost>, host: RecentHost): List<RecentHost> {
        val key = addressOf(host.link)
        return (listOf(host) + list.filter { addressOf(it.link) != key }).take(MAX)
    }

    fun remove(list: List<RecentHost>, link: String): List<RecentHost> {
        val key = addressOf(link)
        return list.filter { addressOf(it.link) != key }
    }

    /** "http://192.168.1.20:8790/?k=ABCD2345" -> "192.168.1.20:8790" (lower-cased). */
    fun addressOf(link: String): String =
        link.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

    /** One host per line: kind \t name \t link (tabs/newlines in names are replaced by spaces). */
    fun encode(list: List<RecentHost>): String = list.take(MAX).joinToString("\n") { h ->
        listOf(h.kind.name, clean(h.name), clean(h.link)).joinToString("\t")
    }

    fun decode(text: String?): List<RecentHost> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[2].isBlank()) return@mapNotNull null
            val kind = HostKind.entries.firstOrNull { it.name == parts[0] } ?: HostKind.UNKNOWN
            RecentHost(parts[2], parts[1], kind)
        }.take(MAX).toList()
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
