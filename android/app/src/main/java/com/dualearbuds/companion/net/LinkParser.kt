package com.dualearbuds.companion.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Where the host is and the key it expects. [pin] is the optional room PIN (v3.1): it is sent on `/ws` only and is
 * never part of [toLink] (the link and QR never contain the PIN).
 */
data class LinkTarget(val host: String, val port: Int, val key: String, val pin: String? = null) {

    /** The canonical link, exactly in the form the QR code uses: http://IP:PORT/?k=KEY */
    fun toLink(): String = HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addQueryParameter("k", key)
        .build()
        .toString()

    fun infoUrl(): HttpUrl = HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addPathSegment("info")
        .addQueryParameter("k", key)
        .build()

    /** WebSocket URL. OkHttp accepts http:// URLs for web sockets and performs the upgrade itself. */
    fun wsUrl(deviceName: String): HttpUrl = HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addPathSegment("ws")
        .addQueryParameter("k", key)
        .addQueryParameter("client", "android")
        .addQueryParameter("name", deviceName)
        .apply { if (!pin.isNullOrEmpty()) addQueryParameter("p", pin) }
        .build()

    /** Same host without the PIN (for storing and showing). */
    fun withoutPin(): LinkTarget = if (pin == null) this else copy(pin = null)

    /** Same address and key? (The PIN doesn't make it a different host.) */
    fun sameHost(other: LinkTarget?): Boolean =
        other != null && other.host.equals(host, ignoreCase = true) && other.port == port && other.key == key

    override fun toString(): String = "LinkTarget($display)" // never prints the key or PIN

    val display: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

sealed interface ParseResult {
    data class Ok(val target: LinkTarget) : ParseResult
    data class Error(val message: String) : ParseResult
}

object LinkParser {
    const val DEFAULT_PORT = 8790

    private val KEY_REGEX = Regex("^[A-Z2-7]{8}$")
    private val WHITESPACE = Regex("\\s+")

    /** "IP + key": `192.168.1.20 ABCD2345`, `192.168.1.20:8790 k=ABCD 2345`, `[fe80::1]:8790, abcd2345`. */
    private val HOST_AND_KEY = Regex(
        "^(?:https?://)?(\\[[0-9A-Fa-f:.]+\\]|[^\\s/?#\\[\\],;:]+)(?::(\\d{1,5}))?/?[\\s,;]+(?:k\\s*[=:]\\s*)?([A-Za-z0-9]{3,}(?:\\s[A-Za-z0-9]+)?)$"
    )
    private val EXPLICIT_PORT = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(\\[[^\\]]+\\]|[^/?#:\\[\\]]+):(\\d+)")

    /**
     * Accepts the QR link `http://IP:PORT/?k=KEY`, the same without the scheme (`IP:PORT/?k=KEY`), or an address
     * and the key typed separately (`IP KEY`, `IP:PORT KEY`). The port defaults to [DEFAULT_PORT].
     */
    fun parse(raw: String): ParseResult {
        val trimmed = raw.trim()
        HOST_AND_KEY.matchEntire(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val port = m.groupValues[2]
            val key = m.groupValues[3].replace(WHITESPACE, "")
            return parse("http://" + host + (if (port.isNotEmpty()) ":$port" else "") + "/?k=" + key)
        }
        var s = trimmed.replace(WHITESPACE, "")
        if (s.isEmpty()) {
            return ParseResult.Error("Paste the link shown on the laptop or the sharing phone, or scan its QR code.")
        }
        if (s.startsWith("ws://", ignoreCase = true)) s = "http://" + s.substring(5)
        if (!s.contains("://")) s = "http://$s"

        val url = s.toHttpUrlOrNull()
            ?: return ParseResult.Error("That doesn't look like a Dual Earbuds link. Use http://192.168.1.20:8790/?k=ABCD2345, or the address and key: 192.168.1.20 ABCD2345")

        val key = url.queryParameter("k")?.trim()?.uppercase(Locale.ROOT)
        if (key.isNullOrEmpty()) {
            return ParseResult.Error("The link is missing its key (?k=…). Copy the full link, or type the address and the 8-character key: 192.168.1.20 ABCD2345")
        }
        if (!KEY_REGEX.matches(key)) {
            return ParseResult.Error("The key looks wrong. It is 8 characters using A–Z and 2–7 (no 0, 1, 8 or 9), like ABCD2345.")
        }
        val port = if (EXPLICIT_PORT.containsMatchIn(s)) url.port else DEFAULT_PORT
        if (url.host.isBlank()) {
            return ParseResult.Error("The address is missing. It looks like 192.168.1.20.")
        }
        if (port !in 1..65535) {
            return ParseResult.Error("The port number in the link is not valid.")
        }
        return ParseResult.Ok(LinkTarget(url.host, port, key))
    }
}
