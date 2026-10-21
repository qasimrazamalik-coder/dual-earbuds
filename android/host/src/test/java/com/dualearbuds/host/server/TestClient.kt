package com.dualearbuds.host.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

/** Minimal blocking HTTP / WebSocket client for tests. */
class HttpResult(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

fun httpGet(port: Int, target: String, timeoutMs: Int = 3000): HttpResult? {
    Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        s.soTimeout = timeoutMs
        s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        val all = try {
            s.getInputStream().readBytes()
        } catch (_: java.io.IOException) {
            ByteArray(0)
        }
        return parseResponse(all)
    }
}

fun parseResponse(all: ByteArray): HttpResult? {
    val text = String(all, Charsets.ISO_8859_1)
    val end = text.indexOf("\r\n\r\n")
    if (end < 0) return null
    val lines = text.substring(0, end).split("\r\n")
    val status = lines[0].split(' ')[1].toInt()
    val headers = HashMap<String, String>()
    for (l in lines.drop(1)) {
        val c = l.indexOf(':')
        if (c > 0) headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
    }
    return HttpResult(status, headers, all.copyOfRange(end + 4, all.size))
}

class WsTestClient(port: Int, query: String, timeoutMs: Int = 3000) : AutoCloseable {
    val socket = Socket()
    val input: InputStream
    var status = 0
        private set
    var responseHeaders: Map<String, String> = emptyMap()
        private set
    val sentKey = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

    init {
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        input = BufferedInputStream(socket.getInputStream())
        val req = "GET /ws?$query HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $sentKey\r\nSec-WebSocket-Version: 13\r\n\r\n"
        socket.getOutputStream().write(req.toByteArray())
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val text = head.toString("ISO-8859-1")
        if (text.isNotEmpty()) {
            val lines = text.trim().split("\r\n")
            status = lines[0].split(' ')[1].toInt()
            val h = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val c = l.indexOf(':')
                if (c > 0) h[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
            }
            responseHeaders = h
        }
    }

    class Frame(val opcode: Int, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    /** Reads one server frame (asserting it is unmasked). Returns null on EOF/timeout. */
    var lastTimedOut = false
        private set

    fun readFrame(): Frame? {
        lastTimedOut = false
        return try {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) return null
            check(b1 and 0x80 == 0) { "server frames must not be masked" }
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) {
                var l = 0L
                repeat(8) { l = (l shl 8) or input.read().toLong() }
                len = l
            }
            val p = ByteArray(len.toInt())
            var off = 0
            while (off < p.size) {
                val r = input.read(p, off, p.size - off)
                if (r < 0) throw EOFException()
                off += r
            }
            Frame(b0 and 0x0F, p)
        } catch (_: SocketTimeoutException) {
            lastTimedOut = true
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    /** Reads frames until a text frame whose JSON type is [type]. */
    fun readJson(type: String, maxFrames: Int = 200): Map<String, Any?>? {
        repeat(maxFrames) {
            val f = readFrame() ?: return null
            if (f.opcode == WsCodec.OP_TEXT) {
                val m = MiniJson.parseObject(f.text)
                if (m?.get("type") == type) return m
            }
        }
        return null
    }

    fun sendText(text: String) = sendRaw(WsCodec.maskedFrame(WsCodec.OP_TEXT, text.toByteArray(), byteArrayOf(1, 2, 3, 4)))

    fun sendRaw(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /** Close code (or -1 for a bare EOF) when the server closed the connection; null on timeout. */
    fun awaitClosed(maxFrames: Int = 500): Int? {
        repeat(maxFrames) {
            val f = readFrame() ?: return if (lastTimedOut) null else -1
            if (f.opcode == WsCodec.OP_CLOSE) {
                return if (f.payload.size >= 2) ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF) else 1005
            }
        }
        return null
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(10)
    }
    return cond()
}
