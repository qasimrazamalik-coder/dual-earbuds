package com.dualearbuds.host.server

import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

class HeadTooLargeException : IOException("Request head too large")
class HeadTimeoutException : IOException("Request head too slow")

/** A parsed HTTP/1.1 request head. Header names are lower-case. */
class HttpRequest(
    val method: String,
    val target: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

object HttpHead {

    /**
     * Reads bytes up to and including the blank line that ends the head. Enforces [maxBytes] and an
     * absolute [deadlineMs] (monotonic clock, see [nowMs]) using the socket read timeout.
     * Returns null on EOF before a complete head.
     */
    fun read(input: InputStream, socket: Socket?, deadlineMs: Long, maxBytes: Int, nowMs: () -> Long): ByteArray? {
        val buf = ByteArray(maxBytes)
        var n = 0
        while (true) {
            if (socket != null && input.available() == 0) {
                val remaining = deadlineMs - nowMs()
                if (remaining <= 0) throw HeadTimeoutException()
                socket.soTimeout = remaining.toInt().coerceAtLeast(1)
            }
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                throw HeadTimeoutException()
            }
            if (b < 0) return null
            if (n >= maxBytes) throw HeadTooLargeException()
            buf[n++] = b.toByte()
            if (n >= 4 && buf[n - 1] == '\n'.code.toByte() && buf[n - 2] == '\r'.code.toByte() &&
                buf[n - 3] == '\n'.code.toByte() && buf[n - 4] == '\r'.code.toByte()
            ) {
                return buf.copyOf(n)
            }
            if (nowMs() > deadlineMs) throw HeadTimeoutException()
        }
    }

    /** Parses a head read by [read]. Returns null when malformed. */
    fun parse(head: ByteArray): HttpRequest? {
        val text = String(head, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val method = parts[0]
        val target = parts[1]
        if (method.isEmpty() || !target.startsWith("/")) return null
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val c = line.indexOf(':')
            if (c <= 0) return null
            val name = line.substring(0, c).trim().lowercase()
            val value = line.substring(c + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        return HttpRequest(method, target, path, query, headers)
    }

    fun parseQuery(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        val noFragment = raw.substringBefore('#')
        for (pair in noFragment.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            val v = if (eq >= 0) decode(pair.substring(eq + 1)) else ""
            if (k !in out) out[k] = v
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        s
    }

    /** True when the comma-separated [value] contains [token] (case-insensitive). */
    fun hasToken(value: String?, token: String): Boolean =
        value?.split(',')?.any { it.trim().equals(token, ignoreCase = true) } == true
}
