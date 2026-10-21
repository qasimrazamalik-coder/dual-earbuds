package com.dualearbuds.host.server

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Limits of protocol v3. Tests shrink the timeouts. */
data class HostLimits(
    val maxClients: Int = 8,
    val maxClientsParty: Int = 16,
    val maxPending: Int = 16,
    val headerDeadlineMs: Long = 5_000,
    val maxHeaderBytes: Int = 8 * 1024,
    val maxMessageBytes: Int = 4 * 1024,
    val idleTimeoutMs: Int = 30_000,
    val queueMs: Int = 600,
    val pingIntervalMs: Long = 2_000,
    val writeStallMs: Long = 1_200,
    val guessMaxDistinct: Int = 10,
    val guessWindowMs: Long = 60_000,
    val guessLockMs: Long = 5 * 60_000
)

/** Who this host is, as reported in `/info` and `hello`. */
data class HostIdentity(
    val pcName: String,
    val version: String = "3.0.0",
    val host: String = "android"
)

/** A listener as shown in the host's UI. */
data class ListenerInfo(
    val id: Int,
    val name: String,
    val client: String,
    val address: String,
    val device: String,
    val earbuds: String,
    val battery: Int,
    val volume: Int,
    val bufferMs: Int,
    val underruns: Int,
    val playing: Boolean,
    val rttMs: Int,
    val dropped: Long
) {
    val displayName: String get() = device.ifBlank { name.ifBlank { "Listener" } }
}

/**
 * Host side of Dual Earbuds link protocol v3 on plain sockets: HTTP/1.1 and RFC 6455 WebSocket on one
 * port. Free of Android APIs. One accept thread, one short-lived thread per HTTP request, and a
 * reader + writer thread per listener (max 16), plus one housekeeping thread for pings and stalls.
 *
 * [sendAudio] must be called from a single thread (the capture thread).
 */
class HostServer(
    private val identity: HostIdentity,
    initialKey: String,
    private val pageProvider: () -> ByteArray?,
    private val limits: HostLimits = HostLimits(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** Called (from any thread) when the listener list or a listener's stats change. */
    @Volatile
    var onChange: (() -> Unit)? = null

    @Volatile
    var key: String = LinkKey.normalize(initialKey)
        private set

    @Volatile
    var party: Boolean = false
        private set

    /** Current sample rate, 0 while not sharing. */
    @Volatile
    var rate: Int = 0
        private set

    @Volatile
    var port: Int = 0
        private set

    val maxClients: Int get() = if (party) limits.maxClientsParty else limits.maxClients

    private val startedAt = nowMs()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var houseThread: Thread? = null
    private val pending = AtomicInteger(0)
    private val nextId = AtomicInteger(1)
    private val guard = GuessGuard(nowMs, limits.guessMaxDistinct, limits.guessWindowMs, limits.guessLockMs)
    private val clientsLock = Any()

    @Volatile
    private var clients: Array<Session> = emptyArray()

    // Audio frame pool: frames are shared by all listeners and reused round-robin. The pool covers
    // well over queue (600 ms) + write-stall limit, so a frame is never rewritten while queued or sending.
    private var pool: Array<ByteArray> = emptyArray()
    private var poolIndex = 0
    private var poolFrameLen = 0
    private val seq = AtomicInteger(0)

    // ------------------------------------------------------------------ lifecycle

    /**
     * Binds to [preferredPort] (or the next free one, up to [attempts] ports) on all interfaces and
     * starts serving. Port 0 binds an ephemeral port. Returns the bound port.
     */
    @Throws(IOException::class)
    fun start(preferredPort: Int = 8790, attempts: Int = 20): Int {
        check(!running.get()) { "already running" }
        var lastError: IOException? = null
        var ss: ServerSocket? = null
        val tries = if (preferredPort == 0) 1 else attempts
        for (i in 0 until tries) {
            val p = if (preferredPort == 0) 0 else preferredPort + i
            if (p > 65535) break
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(p), 50)
                ss = s
                break
            } catch (e: IOException) {
                lastError = e
                try {
                    s.close()
                } catch (_: IOException) {
                }
            }
        }
        val bound = ss ?: throw (lastError ?: IOException("No free port"))
        serverSocket = bound
        port = bound.localPort
        running.set(true)
        acceptThread = Thread({ acceptLoop(bound) }, "host-accept").apply {
            isDaemon = true
            start()
        }
        houseThread = Thread({ housekeeping() }, "host-house").apply {
            isDaemon = true
            start()
        }
        return port
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        val all = clients
        // Polite close (listeners may auto-reconnect when sharing resumes); force after a short wait.
        for (c in all) c.sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_GOING_AWAY, "Host stopped"), closeAfter = true)
        val until = nowMs() + STOP_DRAIN_MS
        while (clients.isNotEmpty() && nowMs() < until) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                break
            }
        }
        for (c in clients) c.close()
        acceptThread?.interrupt()
        houseThread?.interrupt()
        acceptThread = null
        houseThread = null
        onChange?.invoke()
    }

    val isRunning: Boolean get() = running.get()

    // ------------------------------------------------------------------ host controls

    /** Sharing started ([rate] > 0) or stopped ([rate] = 0). Resets the sequence on start. */
    fun setSharing(rate: Int) {
        val on = rate > 0
        if (on && this.rate == 0) seq.set(0)
        this.rate = if (on) rate else 0
        broadcastText(MiniJson.obj("type" to "state", "mirroring" to on, "rate" to this.rate))
    }

    fun setParty(on: Boolean) {
        if (party == on) return
        party = on
        broadcastText(MiniJson.obj("type" to "party", "on" to on))
        onChange?.invoke()
    }

    /** Replaces the link key; current listeners are told and disconnected. */
    fun setKey(newKey: String, reason: String = "The host made a new link. Scan the new QR code.") {
        key = LinkKey.normalize(newKey)
        for (c in clients) kickSession(c, reason)
    }

    /** Sets a listener's volume (0–150 %). Returns false when the listener is gone. */
    fun setVolume(id: Int, volume: Int): Boolean {
        val c = clients.firstOrNull { it.id == id } ?: return false
        val v = volume.coerceIn(0, 150)
        c.volume = v
        c.send(MiniJson.obj("type" to "control", "volume" to v))
        onChange?.invoke()
        return true
    }

    fun kick(id: Int, reason: String = "Removed by the host"): Boolean {
        val c = clients.firstOrNull { it.id == id } ?: return false
        kickSession(c, reason)
        return true
    }

    fun listeners(): List<ListenerInfo> = clients.map { it.info() }

    fun listenerCount(): Int = clients.size

    /**
     * Broadcasts one packet of s16le stereo PCM ([len] bytes, normally 10 ms). Single caller thread.
     * No allocation once the frame pool exists.
     */
    fun sendAudio(pcm: ByteArray, off: Int, len: Int) {
        val r = rate
        val s = seq.getAndIncrement()
        if (r <= 0) return
        val targets = clients
        if (targets.isEmpty()) return
        val payloadLen = AudioPacket.HEADER_SIZE + len
        val frameLen = WsCodec.headerSize(payloadLen) + payloadLen
        if (frameLen != poolFrameLen || pool.isEmpty()) {
            pool = Array(POOL_SIZE) { ByteArray(frameLen) }
            poolFrameLen = frameLen
            poolIndex = 0
        }
        val buf = pool[poolIndex]
        poolIndex = (poolIndex + 1) % POOL_SIZE
        val h = WsCodec.writeHeader(buf, 0, WsCodec.OP_BINARY, payloadLen)
        AudioPacket.writeHeader(buf, h, r, s)
        System.arraycopy(pcm, off, buf, h + AudioPacket.HEADER_SIZE, len)
        for (c in targets) c.offerAudio(buf)
    }

    // ------------------------------------------------------------------ internals

    private fun msSinceStart(): Long = nowMs() - startedAt

    private fun broadcastText(json: String) {
        val all = clients
        if (all.isEmpty()) return
        val frame = WsCodec.textFrame(json)
        for (c in all) c.sendFrame(frame)
    }

    private fun kickSession(c: Session, reason: String) {
        c.send(MiniJson.obj("type" to "kick", "reason" to reason))
        c.sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_NORMAL, "kicked"), closeAfter = true)
        removeClient(c)
    }

    private fun removeClient(c: Session) {
        var changed = false
        synchronized(clientsLock) {
            val cur = clients
            if (cur.any { it === c }) {
                clients = cur.filter { it !== c }.toTypedArray()
                changed = true
            }
        }
        if (changed) onChange?.invoke()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (_: IOException) {
                if (!running.get()) return
                continue
            }
            val address = socket.inetAddress?.hostAddress ?: "?"
            if (guard.isLocked(address)) {
                closeQuietly(socket) // ignored while locked out
                continue
            }
            if (pending.incrementAndGet() > limits.maxPending) {
                pending.decrementAndGet()
                try {
                    socket.soTimeout = 1000
                    writeResponse(socket.getOutputStream(), 503, "Service Unavailable", "text/plain; charset=utf-8", "Busy".toByteArray())
                } catch (_: IOException) {
                }
                closeQuietly(socket)
                continue
            }
            try {
                Thread({ handleConnection(socket, address) }, "host-http").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
                pending.decrementAndGet()
                closeQuietly(socket)
            }
        }
    }

    private fun handleConnection(socket: Socket, address: String) {
        var pendingReleased = false
        fun releasePending() {
            if (!pendingReleased) {
                pendingReleased = true
                pending.decrementAndGet()
            }
        }
        var upgraded = false
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 4096)
            val output = socket.getOutputStream()
            val deadline = nowMs() + limits.headerDeadlineMs
            val head = try {
                HttpHead.read(input, socket, deadline, limits.maxHeaderBytes, nowMs)
            } catch (_: HeadTooLargeException) {
                writeResponse(output, 431, "Request Header Fields Too Large", "text/plain; charset=utf-8", "Header too large".toByteArray())
                return
            } catch (_: HeadTimeoutException) {
                return
            } ?: return
            val req = HttpHead.parse(head)
            if (req == null) {
                writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request".toByteArray())
                return
            }
            if (req.path == "/favicon.ico") {
                writeResponse(output, 204, "No Content", null, null)
                return
            }
            if (req.method != "GET" || (req.path != "/" && req.path != "/info" && req.path != "/ws")) {
                writeResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
                return
            }
            val given = req.query["k"]
            if (!LinkKey.matches(key, given)) {
                if (!given.isNullOrBlank() && guard.recordWrong(address, given)) {
                    return // just locked: ignore from now on
                }
                writeResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", "Wrong or missing link key".toByteArray())
                return
            }
            when (req.path) {
                "/" -> {
                    val page = pageProvider()
                    if (page == null) {
                        writeResponse(output, 503, "Service Unavailable", "text/plain; charset=utf-8", "Player page missing".toByteArray())
                    } else {
                        writeResponse(output, 200, "OK", "text/html; charset=utf-8", page)
                    }
                }
                "/info" -> writeResponse(output, 200, "OK", "application/json; charset=utf-8", infoJson().toByteArray(Charsets.UTF_8))
                "/ws" -> {
                    val session = upgrade(socket, input, output, req, address) ?: return
                    upgraded = true
                    releasePending()
                    session.runReader()
                }
            }
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        } finally {
            releasePending()
            if (!upgraded) closeQuietly(socket)
        }
    }

    fun infoJson(): String = MiniJson.obj(
        "app" to "DualEarbuds",
        "protocol" to 3,
        "version" to identity.version,
        "host" to identity.host,
        "pc" to identity.pcName,
        "rate" to rate,
        "channels" to 2,
        "format" to "s16le",
        "party" to party,
        "maxClients" to maxClients
    )

    private fun helloJson(): String = MiniJson.obj(
        "type" to "hello",
        "protocol" to 3,
        "pc" to identity.pcName,
        "version" to identity.version,
        "host" to identity.host,
        "rate" to rate,
        "party" to party
    )

    private fun upgrade(socket: Socket, input: InputStream, output: OutputStream, req: HttpRequest, address: String): Session? {
        val wsKey = req.header("sec-websocket-key")
        val okHeaders = req.header("upgrade")?.trim()?.equals("websocket", ignoreCase = true) == true &&
            HttpHead.hasToken(req.header("connection"), "upgrade") &&
            WsCodec.isValidClientKey(wsKey)
        if (!okHeaders) {
            writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "WebSocket upgrade expected".toByteArray())
            return null
        }
        if (req.header("sec-websocket-version")?.trim() != "13") {
            writeResponse(output, 426, "Upgrade Required", "text/plain; charset=utf-8", "WebSocket version 13 only".toByteArray(), "Sec-WebSocket-Version: 13\r\n")
            return null
        }
        val client = when (req.query["client"]?.lowercase()) {
            "android" -> "android"
            "web" -> "web"
            else -> "web"
        }
        val name = sanitizeName(req.query["name"] ?: "")
        val session = Session(nextId.getAndIncrement(), socket, input, output, client, name, address)
        session.send(helloJson())
        synchronized(clientsLock) {
            if (clients.size >= maxClients) {
                writeResponse(output, 503, "Service Unavailable", "text/plain; charset=utf-8", "Too many listeners".toByteArray())
                return null
            }
            val accept = WsCodec.acceptKey(wsKey!!)
            val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            output.write(resp.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            clients = clients + session
        }
        socket.soTimeout = limits.idleTimeoutMs
        session.lastHeard = nowMs()
        session.startWriter()
        onChange?.invoke()
        return session
    }

    private fun sanitizeName(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            if (sb.length >= 48) break
            if (!c.isISOControl()) sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun housekeeping() {
        var nextPing = nowMs() + limits.pingIntervalMs
        while (running.get()) {
            try {
                Thread.sleep(HOUSEKEEPING_TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = nowMs()
            val all = clients
            for (c in all) {
                val started = c.writeStartedAt
                if (started != 0L && now - started > limits.writeStallMs) c.close()
            }
            if (now >= nextPing) {
                nextPing = now + limits.pingIntervalMs
                broadcastText(MiniJson.obj("type" to "ping", "t" to msSinceStart()))
            }
        }
    }

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: String = ""
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (code != 204) sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        if (code == 200 && contentType?.startsWith("application/json") == true) {
            sb.append("Access-Control-Allow-Origin: *\r\n")
        }
        sb.append(extraHeaders)
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null && code != 204) out.write(body)
        out.flush()
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    // ------------------------------------------------------------------ one listener

    private inner class Session(
        val id: Int,
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
        val client: String,
        val name: String,
        val address: String
    ) {
        val outbox = Outbox(maxOf(1, limits.queueMs / AudioPacket.PACKET_MS))
        private val closed = AtomicBoolean(false)

        @Volatile var writeStartedAt = 0L
        @Volatile var lastHeard = 0L
        @Volatile var device = ""
        @Volatile var earbuds = ""
        @Volatile var battery = -1
        @Volatile var volume = 100
        @Volatile var bufferMs = 0
        @Volatile var underruns = 0
        @Volatile var playing = false
        @Volatile var rttMs = -1
        @Volatile var dropped = 0L

        fun info() = ListenerInfo(
            id, name, client, address, device, earbuds, battery, volume, bufferMs, underruns, playing, rttMs, dropped
        )

        fun send(json: String) = sendFrame(WsCodec.textFrame(json))

        fun sendFrame(frame: ByteArray, closeAfter: Boolean = false) {
            if (!outbox.offerControl(frame, closeAfter)) {
                // Control queue overflow means the listener is not reading at all.
                if (!closed.get()) close()
            }
        }

        fun offerAudio(frame: ByteArray) {
            if (outbox.offerAudio(frame)) dropped++
        }

        fun startWriter() {
            Thread({ writerLoop() }, "host-ws-w$id").apply {
                isDaemon = true
                start()
            }
        }

        private fun writerLoop() {
            try {
                while (!closed.get()) {
                    val item = outbox.take(1000) ?: continue
                    val bytes: ByteArray
                    var closeAfter = false
                    if (item is Outbox.Item) {
                        bytes = item.bytes
                        closeAfter = item.closeAfter
                    } else {
                        bytes = item as ByteArray
                    }
                    writeStartedAt = nowMs().coerceAtLeast(1L)
                    output.write(bytes)
                    writeStartedAt = 0L
                    if (closeAfter) {
                        output.flush()
                        close()
                        return
                    }
                }
            } catch (_: IOException) {
                close()
            } catch (_: RuntimeException) {
                close()
            } finally {
                writeStartedAt = 0L
            }
        }

        /** Runs on the handshake thread after the upgrade. */
        fun runReader() {
            val reader = WsFrameReader(input, limits.maxMessageBytes)
            val message = ByteArray(limits.maxMessageBytes)
            var msgLen = 0
            var msgOpcode = -1
            try {
                while (!closed.get()) {
                    val more = try {
                        reader.next()
                    } catch (_: SocketTimeoutException) {
                        sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_GOING_AWAY, "idle"), closeAfter = true)
                        return
                    }
                    if (!more) break
                    lastHeard = nowMs()
                    when (reader.opcode) {
                        WsCodec.OP_CLOSE -> {
                            sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_NORMAL), closeAfter = true)
                            return
                        }
                        WsCodec.OP_PING -> sendFrame(WsCodec.frame(WsCodec.OP_PONG, reader.payload, 0, reader.length))
                        WsCodec.OP_PONG -> Unit
                        WsCodec.OP_TEXT, WsCodec.OP_BINARY -> {
                            if (msgOpcode != -1) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Expected continuation")
                            System.arraycopy(reader.payload, 0, message, 0, reader.length)
                            msgLen = reader.length
                            if (reader.fin) {
                                if (reader.opcode == WsCodec.OP_TEXT) onText(String(message, 0, msgLen, Charsets.UTF_8))
                                msgLen = 0
                            } else {
                                msgOpcode = reader.opcode
                            }
                        }
                        WsCodec.OP_CONT -> {
                            if (msgOpcode == -1) throw WsProtocolException(WsCodec.CLOSE_PROTOCOL, "Unexpected continuation")
                            if (msgLen + reader.length > message.size) throw WsProtocolException(WsCodec.CLOSE_TOO_BIG, "Message too big")
                            System.arraycopy(reader.payload, 0, message, msgLen, reader.length)
                            msgLen += reader.length
                            if (reader.fin) {
                                if (msgOpcode == WsCodec.OP_TEXT) onText(String(message, 0, msgLen, Charsets.UTF_8))
                                msgOpcode = -1
                                msgLen = 0
                            }
                        }
                    }
                }
                close()
            } catch (e: WsProtocolException) {
                sendFrame(WsCodec.closeFrame(e.closeCode, e.message ?: ""), closeAfter = true)
            } catch (_: IOException) {
                close()
            } catch (_: RuntimeException) {
                close()
            }
        }

        private fun onText(text: String) {
            val m = MiniJson.parseObject(text) ?: return
            when (m.str("type")) {
                "pong" -> {
                    val t = m.num("t") ?: return
                    val rtt = msSinceStart() - t.toLong()
                    if (rtt in 0..60_000) {
                        rttMs = rtt.toInt()
                        onChange?.invoke()
                    }
                }
                "status" -> {
                    m.str("device")?.let { device = sanitizeName(it) }
                    m.str("earbuds")?.let { earbuds = sanitizeName(it) }
                    m.num("battery")?.let { battery = it.toInt().coerceIn(-1, 100) }
                    m.num("volume")?.let { volume = it.toInt().coerceIn(0, 150) }
                    m.num("bufferMs")?.let { bufferMs = it.toInt().coerceIn(0, 60_000) }
                    m.num("underruns")?.let { underruns = it.toInt().coerceAtLeast(0) }
                    m.bool("playing")?.let { playing = it }
                    onChange?.invoke()
                }
                else -> Unit // unknown types are ignored
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            outbox.close()
            try {
                socket.close()
            } catch (_: IOException) {
            }
            removeClient(this)
        }
    }

    companion object {
        /** 256 × 10 ms = 2.56 s of frames, > 600 ms queue + 1.2 s write stall + housekeeping tick. */
        private const val POOL_SIZE = 256
        private const val HOUSEKEEPING_TICK_MS = 250L
        private const val STOP_DRAIN_MS = 300L
    }
}
