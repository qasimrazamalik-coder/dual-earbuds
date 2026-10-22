package com.dualearbuds.host.server

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
    val clockIntervalMs: Long = 1_000,
    val writeStallMs: Long = 1_200,
    val guessMaxDistinct: Int = 10,
    val guessWindowMs: Long = 60_000,
    val guessLockMs: Long = 5 * 60_000,
    val remotePerSecond: Double = 5.0
)

/** Who this host is, as reported in `/info` and `hello`. */
data class HostIdentity(
    val pcName: String,
    val version: String = "3.1.0",
    val host: String = "android"
)

/**
 * Host side of Dual Earbuds link protocol v3 (+ v3.1 additions) on plain sockets: HTTP/1.1 and
 * RFC 6455 WebSocket on one port. Free of Android APIs so it runs in JVM unit tests.
 * One accept thread, one short-lived thread per HTTP request, a reader + writer thread per listener
 * (max 16), and one housekeeping thread for ping (2 s), clock (1 s), the common deadline P (v3.2)
 * and stalled writers.
 *
 * [sendAudio] must be called from a single thread (the capture thread).
 */
class HostServer(
    private val identity: HostIdentity,
    initialKey: String,
    private val pageProvider: () -> ByteArray?,
    private val limits: HostLimits = HostLimits(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** Called (from any thread) when the listener list or a listener's stats change. */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Called on a listener's reader thread for an allowed, rate-limited `remote` press. */
    @Volatile
    var onRemote: ((RemoteAction) -> Unit)? = null

    @Volatile
    var key: String = LinkKey.normalize(initialKey)
        private set

    @Volatile
    var party: Boolean = false
        private set

    /** Room PIN, or null when off. */
    @Volatile
    var pin: String? = null
        private set

    @Volatile
    var remoteAllowed: Boolean = false
        private set

    @Volatile
    var talking: Boolean = false
        private set

    @Volatile
    private var source: String = "all"

    @Volatile
    private var sourceApp: String? = null

    /** The common deadline P (v3.2) now in force, announced with `playout`. */
    @Volatile
    var playoutMs: Int = HostMessages.DEFAULT_PLAYOUT_MS
        private set

    /**
     * Host local need (v3.2): how late this phone's own ear hears the captured app (its earbuds'
     * calibrated latency, or 180 ms Bluetooth / 20 ms wired). P never goes below it.
     */
    @Volatile
    var localNeedMs: Int = HostMessages.DEFAULT_PLAYOUT_MS
        private set

    /** Current sample rate, 0 while not sharing. */
    @Volatile
    var rate: Int = 0
        private set

    @Volatile
    var port: Int = 0
        private set

    val maxClients: Int get() = if (party) limits.maxClientsParty else limits.maxClients

    private val startedAt = clock()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var houseThread: Thread? = null
    private val pending = AtomicInteger(0)
    private val nextId = AtomicInteger(1)
    private val guard = GuessGuard(clock, limits.guessMaxDistinct, limits.guessWindowMs, limits.guessLockMs)
    private val remoteLimiter = RateLimiter(limits.remotePerSecond, limits.remotePerSecond, clock)
    private val clockTrack = ClockTrack()
    private val playout = Playout()
    private val clientsLock = Any()

    @Volatile
    private var clients: Array<ListenerSession> = emptyArray()

    // Audio frame pool: frames are shared by all listeners and reused round-robin. The pool covers
    // well over queue (600 ms) + write-stall limit, so a frame is never rewritten while queued or sending.
    private var pool: Array<ByteArray> = emptyArray()
    private var poolIndex = 0
    private var poolFrameLen = 0
    private val seq = AtomicInteger(0)

    private val owner = object : SessionOwner {
        override val limits: HostLimits get() = this@HostServer.limits
        override fun nowMs(): Long = clock()
        override fun onText(session: ListenerSession, text: String) = handleText(session, text)
        override fun onClosed(session: ListenerSession) = removeClient(session)
    }

    /** Host time used on the wire (`ping.t`, `sync.h`, `clock.h`): ms since this server was made. */
    fun hostMs(): Long = clock() - startedAt

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
        // Polite close (listeners may auto-reconnect when sharing resumes); force after a short wait.
        for (c in clients) c.sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_GOING_AWAY, "Host stopped"), closeAfter = true)
        val until = clock() + STOP_DRAIN_MS
        while (clients.isNotEmpty() && clock() < until) {
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
        if (on && this.rate == 0) {
            seq.set(0)
            clockTrack.reset()
        }
        this.rate = if (on) rate else 0
        broadcastText(HostMessages.state(on, this.rate))
    }

    fun setParty(on: Boolean) {
        if (party == on) return
        party = on
        broadcastText(HostMessages.party(on))
        onChange?.invoke()
    }

    /** Sets the room PIN (4–6 digits) or turns it off with null. Joined listeners stay. */
    fun setPin(newPin: String?) {
        require(newPin == null || RoomPin.isValid(newPin)) { "PIN must be 4-6 digits" }
        pin = newPin
    }

    /** Sets the host local need (ms, 0–400); P follows within one housekeeping tick. */
    fun setLocalNeed(ms: Int) {
        localNeedMs = ms.coerceIn(0, Playout.MAX_MS)
    }

    fun setRemoteAllowed(on: Boolean) {
        remoteAllowed = on
    }

    /** Talk-over on/off: listeners get `{"type":"talk","on":…}`. */
    fun setTalking(on: Boolean) {
        if (talking == on) return
        talking = on
        broadcastText(HostMessages.talk(on))
    }

    /** Sound source shown in `/info` and `hello`: everything, or one app ([appName]). */
    fun setSource(appName: String?) {
        if (appName.isNullOrBlank()) {
            source = "all"
            sourceApp = null
        } else {
            source = "app"
            sourceApp = appName
        }
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
        c.send(HostMessages.volume(v))
        onChange?.invoke()
        return true
    }

    fun kick(id: Int, reason: String = "Removed by the host"): Boolean {
        val c = clients.firstOrNull { it.id == id } ?: return false
        kickSession(c, reason)
        return true
    }

    fun listeners(): List<ListenerInfo> {
        val now = clock()
        return clients.map { it.info(now) }
    }

    fun listenerCount(): Int = clients.size

    fun facts(): HostFacts = HostFacts(
        identity = identity,
        rate = rate,
        party = party,
        maxClients = maxClients,
        pin = pin != null,
        remote = remoteAllowed,
        source = source,
        app = sourceApp,
        playoutMs = playoutMs
    )

    fun infoJson(): String = HostMessages.info(facts())

    /**
     * Broadcasts one packet of s16le stereo PCM ([len] bytes, normally 10 ms). Single caller thread.
     * [capturedAtHostMs] is when the packet was captured on the [hostMs] clock (for `clock`).
     * No allocation once the frame pool exists.
     */
    fun sendAudio(pcm: ByteArray, off: Int, len: Int, capturedAtHostMs: Long = hostMs()) {
        val r = rate
        val s = seq.getAndIncrement()
        if (r <= 0) return
        clockTrack.record(s, capturedAtHostMs)
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

    // ------------------------------------------------------------------ listener messages

    private fun handleText(session: ListenerSession, text: String) {
        val m = MiniJson.parseObject(text) ?: return
        when (m.str("type")) {
            "pong" -> {
                val t = m.num("t") ?: return
                val rtt = hostMs() - t.toLong()
                if (rtt in 0..60_000) {
                    session.rttMs = rtt.toInt()
                    onChange?.invoke()
                }
            }
            "status" -> {
                val st = HostMessages.parseStatus(m)
                st.device?.let { session.device = sanitizeName(it) }
                st.earbuds?.let { session.earbuds = sanitizeName(it) }
                st.battery?.let { session.battery = it }
                st.volume?.let { session.volume = it }
                st.bufferMs?.let { session.bufferMs = it }
                st.underruns?.let { session.underruns = it }
                st.playing?.let { session.playing = it }
                st.synced?.let { session.syncedHint = it }
                st.needMs?.let { session.needMs = it }
                st.syncErrMs?.let { session.syncErrMs = it }
                onChange?.invoke()
            }
            "sync" -> {
                val reply = ClockTrack.syncReply(m.num("c"), hostMs()) ?: return
                session.send(reply)
                val first = session.lastSyncAt == 0L
                session.lastSyncAt = clock()
                if (first) onChange?.invoke()
            }
            "remote" -> {
                if (!remoteAllowed) return
                val action = RemoteAction.parse(m.str("action")) ?: return
                if (!remoteLimiter.tryAcquire()) return
                try {
                    onRemote?.invoke(action)
                } catch (_: RuntimeException) {
                }
            }
            else -> Unit // unknown types are ignored
        }
    }

    // ------------------------------------------------------------------ internals

    private fun broadcastText(json: String) {
        val all = clients
        if (all.isEmpty()) return
        val frame = WsCodec.textFrame(json)
        for (c in all) c.sendFrame(frame)
    }

    private fun kickSession(c: ListenerSession, reason: String) {
        c.send(HostMessages.kick(reason))
        c.sendFrame(WsCodec.closeFrame(WsCodec.CLOSE_NORMAL, "kicked"), closeAfter = true)
        removeClient(c)
    }

    private fun removeClient(c: ListenerSession) {
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
                    writeResponse(socket.getOutputStream(), 503, "Service Unavailable", TEXT, "Busy".toByteArray())
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
            val deadline = clock() + limits.headerDeadlineMs
            val head = try {
                HttpHead.read(input, socket, deadline, limits.maxHeaderBytes, clock)
            } catch (_: HeadTooLargeException) {
                writeResponse(output, 431, "Request Header Fields Too Large", TEXT, "Header too large".toByteArray())
                return
            } catch (_: HeadTimeoutException) {
                return
            } ?: return
            val req = HttpHead.parse(head)
            if (req == null) {
                writeResponse(output, 400, "Bad Request", TEXT, "Bad request".toByteArray())
                return
            }
            if (req.path == "/favicon.ico") {
                writeResponse(output, 204, "No Content", null, null)
                return
            }
            if (req.method != "GET" || (req.path != "/" && req.path != "/info" && req.path != "/ws")) {
                writeResponse(output, 404, "Not Found", TEXT, "Not found".toByteArray())
                return
            }
            val given = req.query["k"]
            if (!LinkKey.matches(key, given)) {
                if (!given.isNullOrBlank() && guard.recordWrong(address, given)) {
                    return // just locked: ignore from now on
                }
                writeResponse(output, 403, "Forbidden", TEXT, "Wrong or missing link key".toByteArray())
                return
            }
            // Room PIN: required on /ws. The page and /info stay open so the page can see "pin":true
            // and ask for it, but a wrong `p` on the page is refused and counted too.
            if (req.path != "/info") {
                val pinCheck = RoomPin.check(pin, req.query["p"])
                val refuse = pinCheck == RoomPin.Check.WRONG ||
                    (pinCheck == RoomPin.Check.MISSING && req.path == "/ws")
                if (refuse) {
                    if (pinCheck == RoomPin.Check.WRONG && guard.recordWrong(address, "PIN:" + req.query["p"])) return
                    writeResponse(output, 403, "Forbidden", TEXT, "Wrong or missing room PIN".toByteArray())
                    return
                }
            }
            when (req.path) {
                "/" -> {
                    val page = pageProvider()
                    if (page == null) {
                        writeResponse(output, 503, "Service Unavailable", TEXT, "Player page missing".toByteArray())
                    } else {
                        writeResponse(output, 200, "OK", "text/html; charset=utf-8", page)
                    }
                }
                "/info" -> writeResponse(output, 200, "OK", JSON, infoJson().toByteArray(Charsets.UTF_8))
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

    private fun upgrade(socket: Socket, input: InputStream, output: OutputStream, req: HttpRequest, address: String): ListenerSession? {
        val wsKey = req.header("sec-websocket-key")
        val okHeaders = req.header("upgrade")?.trim()?.equals("websocket", ignoreCase = true) == true &&
            HttpHead.hasToken(req.header("connection"), "upgrade") &&
            WsCodec.isValidClientKey(wsKey)
        if (!okHeaders) {
            writeResponse(output, 400, "Bad Request", TEXT, "WebSocket upgrade expected".toByteArray())
            return null
        }
        if (req.header("sec-websocket-version")?.trim() != "13") {
            writeResponse(output, 426, "Upgrade Required", TEXT, "WebSocket version 13 only".toByteArray(), "Sec-WebSocket-Version: 13\r\n")
            return null
        }
        val client = when (req.query["client"]?.lowercase()) {
            "android" -> "android"
            "windows" -> "windows"
            else -> "web"
        }
        val name = sanitizeName(req.query["name"] ?: "")
        val session = ListenerSession(nextId.getAndIncrement(), socket, input, output, client, name, address, owner)
        session.send(HostMessages.hello(facts()))
        if (talking) session.send(HostMessages.talk(true))
        synchronized(clientsLock) {
            if (clients.size >= maxClients) {
                writeResponse(output, 503, "Service Unavailable", TEXT, "Too many listeners".toByteArray())
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
        session.lastHeard = clock()
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
        var nextPing = clock() + limits.pingIntervalMs
        var nextClock = clock() + limits.clockIntervalMs
        while (running.get()) {
            try {
                Thread.sleep(HOUSEKEEPING_TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = clock()
            for (c in clients) {
                val started = c.writeStartedAt
                if (started != 0L && now - started > limits.writeStallMs) c.close()
            }
            if (now >= nextPing) {
                nextPing = now + limits.pingIntervalMs
                broadcastText(HostMessages.ping(hostMs()))
            }
            if (now >= nextClock) {
                nextClock = now + limits.clockIntervalMs
                if (rate > 0) clockTrack.clockMessage()?.let { broadcastText(it) }
            }
            updatePlayout(now)
        }
    }

    /** Recomputes P from the synced listeners' needs and the local need; announces a change. */
    private fun updatePlayout(now: Long) {
        val all = clients
        var anyoneSynced = false
        val needs = IntArray(all.size)
        var n = 0
        for (c in all) {
            val info = c.info(now)
            if (!info.synced) continue
            anyoneSynced = true
            needs[n++] = c.needMs ?: HostMessages.DEFAULT_PLAYOUT_MS
        }
        val target = Playout.target(needs.asIterable().take(n), localNeedMs)
        if (playout.step(target, now, anyoneSynced)) {
            playoutMs = playout.currentMs
            broadcastText(HostMessages.playout(playoutMs))
            onChange?.invoke()
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

    companion object {
        /** 256 × 10 ms = 2.56 s of frames, > 600 ms queue + 1.2 s write stall + housekeeping tick. */
        private const val POOL_SIZE = 256
        private const val HOUSEKEEPING_TICK_MS = 100L
        private const val STOP_DRAIN_MS = 300L
        private const val TEXT = "text/plain; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"
    }
}
