// Test shim: the subset of OkHttp/Okio/Android the listener's net code (StreamConnection, InfoClient, LinkParser)
// compiles against, so that code runs unchanged on a plain JVM. The WebSocket is a small RFC 6455 client that
// follows OkHttp's callback contract: one reader thread, onOpen → onMessage… → onClosing/onClosed or onFailure
// (also after cancel()), a failed upgrade reports onFailure with the HTTP response.
package okhttp3

import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

class HttpUrl private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    private val segments: List<String>,
    private val query: List<Pair<String, String>>
) {
    fun queryParameter(name: String): String? = query.firstOrNull { it.first == name }?.second

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(scheme).append("://")
        sb.append(if (host.contains(':')) "[$host]" else host)
        if (port != 80) sb.append(':').append(port)
        sb.append('/')
        sb.append(segments.joinToString("/") { encode(it, PATH) })
        if (query.isNotEmpty()) {
            sb.append('?')
            sb.append(query.joinToString("&") { encode(it.first, QUERY) + "=" + encode(it.second, QUERY) })
        }
        return sb.toString()
    }

    class Builder {
        private var scheme = "http"
        private var host = ""
        private var port = 80
        private val segments = ArrayList<String>()
        private val query = ArrayList<Pair<String, String>>()
        fun scheme(s: String) = apply { scheme = s }
        fun host(h: String) = apply { host = h.removePrefix("[").removeSuffix("]") }
        fun port(p: Int) = apply { port = p }
        fun addPathSegment(s: String) = apply { segments += s }
        fun addQueryParameter(k: String, v: String?) = apply { query += k to (v ?: "") }
        fun build() = HttpUrl(scheme, host, port, segments.toList(), query.toList())
    }

    companion object {
        // OkHttp's QUERY_COMPONENT_ENCODE_SET (+ is encoded too) and PATH_SEGMENT_ENCODE_SET
        private const val QUERY = " !\"#$&'(),/:;<=>?@[]\\^`{|}~+"
        private const val PATH = " \"<>^`{}|/\\?#"

        private fun encode(s: String, set: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c < 0x20 || c >= 0x7F || set.indexOf(c.toChar()) >= 0 || c == '%'.code) sb.append(String.format("%%%02X", c))
                else sb.append(c.toChar())
            }
            return sb.toString()
        }

        @JvmStatic
        fun String.toHttpUrlOrNull(): HttpUrl? = try {
            val u = URI(this)
            val b = Builder().scheme(u.scheme ?: "http").host(u.host ?: return null).port(if (u.port < 0) 80 else u.port)
            u.rawPath?.split('/')?.filter { it.isNotEmpty() }?.forEach { b.addPathSegment(java.net.URLDecoder.decode(it, "UTF-8")) }
            u.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach {
                val i = it.indexOf('=')
                val k = if (i < 0) it else it.substring(0, i)
                val v = if (i < 0) "" else it.substring(i + 1)
                b.addQueryParameter(java.net.URLDecoder.decode(k, "UTF-8"), java.net.URLDecoder.decode(v, "UTF-8"))
            }
            b.build()
        } catch (_: Exception) {
            null
        }
    }
}

class Request private constructor(val url: HttpUrl, val headers: List<Pair<String, String>>) {
    class Builder {
        private var url: HttpUrl? = null
        private val headers = ArrayList<Pair<String, String>>()
        fun url(u: HttpUrl) = apply { url = u }
        fun header(k: String, v: String) = apply { headers += k to v }
        fun build() = Request(url ?: throw IllegalStateException("url"), headers.toList())
    }
}

class ResponseBody(private val bytes: ByteArray) {
    fun string(): String = String(bytes, Charsets.UTF_8)
}

class Response(val code: Int, val body: ResponseBody?) : Closeable {
    override fun close() = Unit
}

interface Call {
    fun execute(): Response
}

interface WebSocket {
    fun send(text: String): Boolean
    fun send(bytes: ByteString): Boolean
    fun close(code: Int, reason: String?): Boolean
    fun cancel()
}

abstract class WebSocketListener {
    open fun onOpen(webSocket: WebSocket, response: Response) {}
    open fun onMessage(webSocket: WebSocket, text: String) {}
    open fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
    open fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}
    open fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
    open fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
}

class OkHttpClient(val connectTimeoutMs: Int = 4000) {
    class Builder {
        private var connect = 4000
        fun connectTimeout(v: Long, u: TimeUnit) = apply { connect = u.toMillis(v).toInt() }
        fun readTimeout(v: Long, u: TimeUnit) = this
        fun writeTimeout(v: Long, u: TimeUnit) = this
        fun callTimeout(v: Long, u: TimeUnit) = this
        fun retryOnConnectionFailure(b: Boolean) = this
        fun build() = OkHttpClient(connect)
    }

    fun newBuilder() = Builder()

    fun newCall(request: Request): Call = object : Call {
        override fun execute(): Response {
            val c = java.net.URL(request.url.toString()).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = 4000
            for ((k, v) in request.headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            c.disconnect()
            return Response(code, ResponseBody(bytes))
        }
    }

    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val ws = RealWebSocket(request, listener, connectTimeoutMs)
        ws.start()
        return ws
    }
}

private class RealWebSocket(val request: Request, val listener: WebSocketListener, val connectTimeoutMs: Int) : WebSocket {
    private val socket = Socket()
    private var out: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var failed = false
    @Volatile private var closeSent = false
    private val random = SecureRandom()

    fun start() {
        Thread({ run() }, "OkHttp WebSocket shim").apply { isDaemon = true; start() }
    }

    private fun run() {
        try {
            val url = request.url
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(url.host, url.port), connectTimeoutMs)
            val o = socket.getOutputStream()
            out = o
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
            val s = url.toString()
            val pathAndQuery = s.substring(s.indexOf('/', s.indexOf("://") + 3))
            val req = "GET $pathAndQuery HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            synchronized(writeLock) { o.write(req.toByteArray(Charsets.ISO_8859_1)); o.flush() }
            val input = socket.getInputStream()
            val head = readHead(input)
            val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (status != 101) {
                fail(IOException("Expected HTTP 101 response but was '$status'"), Response(status, null))
                return
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
            )
            if (!head.contains(accept)) {
                fail(IOException("bad Sec-WebSocket-Accept"), Response(101, null))
                return
            }
            listener.onOpen(this, Response(101, null))
            readFrames(input)
        } catch (t: Throwable) {
            fail(t, null)
        }
    }

    private fun readHead(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("closed during handshake")
            b.write(c)
            val a = b.toByteArray()
            val n = a.size
            if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) {
                return String(a, Charsets.ISO_8859_1)
            }
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw IOException("stream closed")
            got += r
        }
        return b
    }

    private fun readFrames(input: InputStream) {
        var msg = ByteArrayOutputStream()
        var msgOp = -1
        while (true) {
            val h = readFully(input, 2)
            val fin = (h[0].toInt() and 0x80) != 0
            val op = h[0].toInt() and 0x0F
            if ((h[1].toInt() and 0x80) != 0) throw IOException("server frames must not be masked")
            var len = (h[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                val e = readFully(input, 2)
                len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val e = readFully(input, 8)
                len = 0
                for (x in e) len = (len shl 8) or (x.toLong() and 0xFF)
            }
            val payload = readFully(input, len.toInt())
            when (op) {
                0x8 -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                    listener.onClosing(this, code, reason)
                    try { socket.close() } catch (_: IOException) {}
                    listener.onClosed(this, code, reason)
                    return
                }
                0x9 -> writeFrame(0xA, payload)
                0xA -> Unit
                0x1, 0x2 -> {
                    if (fin) deliver(op, payload) else { msg = ByteArrayOutputStream(); msg.write(payload); msgOp = op }
                }
                0x0 -> {
                    msg.write(payload)
                    if (fin) { deliver(msgOp, msg.toByteArray()); msgOp = -1 }
                }
            }
        }
    }

    private fun deliver(op: Int, payload: ByteArray) {
        if (op == 0x1) listener.onMessage(this, String(payload, Charsets.UTF_8))
        else listener.onMessage(this, ByteString(payload))
    }

    private fun fail(t: Throwable, response: Response?) {
        if (failed) return
        failed = true
        try { socket.close() } catch (_: IOException) {}
        listener.onFailure(this, t, response)
    }

    private fun writeFrame(op: Int, payload: ByteArray): Boolean {
        val o = out ?: return false
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val b = ByteArrayOutputStream()
        b.write(0x80 or op)
        when {
            payload.size < 126 -> b.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> { b.write(0x80 or 126); b.write(payload.size shr 8); b.write(payload.size and 0xFF) }
            else -> { b.write(0x80 or 127); for (i in 7 downTo 0) b.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        b.write(mask)
        for (i in payload.indices) b.write((payload[i].toInt() xor mask[i and 3].toInt()) and 0xFF)
        return try {
            synchronized(writeLock) { o.write(b.toByteArray()); o.flush() }
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun send(text: String): Boolean = !failed && !closeSent && writeFrame(0x1, text.toByteArray(Charsets.UTF_8))
    override fun send(bytes: ByteString): Boolean = !failed && !closeSent && writeFrame(0x2, bytes.toByteArray())

    override fun close(code: Int, reason: String?): Boolean {
        if (failed || closeSent) return false
        closeSent = true
        val r = (reason ?: "").toByteArray(Charsets.UTF_8)
        val p = ByteArray(2 + r.size)
        p[0] = (code shr 8).toByte()
        p[1] = code.toByte()
        System.arraycopy(r, 0, p, 2, r.size)
        writeFrame(0x8, p)
        // OkHttp gives the peer 60 s to answer; the shim closes soon after so threads don't linger.
        Thread({ Thread.sleep(500); try { socket.close() } catch (_: IOException) {} }, "ws-close").apply { isDaemon = true; start() }
        return true
    }

    override fun cancel() = fail(IOException("Canceled"), null)
}
