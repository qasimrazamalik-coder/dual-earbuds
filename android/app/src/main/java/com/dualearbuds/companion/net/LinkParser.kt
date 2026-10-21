package com.dualearbuds.companion.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Where the laptop is and the key it expects. */
data class LinkTarget(val host: String, val port: Int, val key: String) {

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
        .build()

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
    private val EXPLICIT_PORT = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(\\[[^\\]]+\\]|[^/?#:\\[\\]]+):(\\d+)")

    fun parse(raw: String): ParseResult {
        var s = raw.trim().replace(WHITESPACE, "")
        if (s.isEmpty()) {
            return ParseResult.Error("Paste the link shown in the Windows app, or scan its QR code.")
        }
        if (s.startsWith("ws://", ignoreCase = true)) s = "http://" + s.substring(5)
        if (!s.contains("://")) s = "http://$s"

        val url = s.toHttpUrlOrNull()
            ?: return ParseResult.Error("That doesn't look like a Dual Earbuds link. It should look like http://192.168.1.20:8790/?k=ABCD2345")

        val key = url.queryParameter("k")?.trim()?.uppercase(Locale.ROOT)
        if (key.isNullOrEmpty()) {
            return ParseResult.Error("The link is missing its key (?k=…). Copy the full link from the Windows app.")
        }
        if (!KEY_REGEX.matches(key)) {
            return ParseResult.Error("The key in the link looks wrong. It should be 8 characters like ABCD2345.")
        }
        val port = if (EXPLICIT_PORT.containsMatchIn(s)) url.port else DEFAULT_PORT
        if (port !in 1..65535) {
            return ParseResult.Error("The port number in the link is not valid.")
        }
        return ParseResult.Ok(LinkTarget(url.host, port, key))
    }
}
