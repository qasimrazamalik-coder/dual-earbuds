package com.dualearbuds.companion.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Wire format of v3.2 UDP audio (docs/protocol.md "UDP audio"). Pure JVM, no Android types.
 *
 * - Listener → host keepalive: `DE3U` + type 1 + 16-byte session token (21 bytes).
 * - Host → listener: `DE3A` + uint32 seq + int32 rate + 10 ms of s16le stereo PCM, then the previous packet again
 *   (seq − 1, same layout). All integers little-endian. The second block may repeat the `DE3A` magic or not; both
 *   are accepted. A datagram with one block only is accepted too.
 */
object UdpWire {
    const val TOKEN_BYTES = 16
    const val KEEPALIVE_TYPE: Byte = 1
    private val KEEPALIVE_MAGIC = byteArrayOf('D'.code.toByte(), 'E'.code.toByte(), '3'.code.toByte(), 'U'.code.toByte())
    private val AUDIO_MAGIC = byteArrayOf('D'.code.toByte(), 'E'.code.toByte(), '3'.code.toByte(), 'A'.code.toByte())

    class Block(val seq: Long, val rate: Int, val samples: ShortArray)

    fun keepalive(token: ByteArray): ByteArray {
        require(token.size == TOKEN_BYTES) { "token must be 16 bytes" }
        val out = ByteArray(4 + 1 + TOKEN_BYTES)
        System.arraycopy(KEEPALIVE_MAGIC, 0, out, 0, 4)
        out[4] = KEEPALIVE_TYPE
        System.arraycopy(token, 0, out, 5, TOKEN_BYTES)
        return out
    }

    /** `hello.token`: 32 hex characters, or base64 / base64url of 16 bytes. Null when it is neither. */
    fun parseToken(text: String?): ByteArray? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.length == 32 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(TOKEN_BYTES) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        return try {
            val normalized = s.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            java.util.Base64.getDecoder().decode(padded).takeIf { it.size == TOKEN_BYTES }
        } catch (_: Throwable) {
            null
        }
    }

    /** Builds a host datagram (used by tests and as the reference layout). [previous] is the redundant copy. */
    fun audioDatagram(seq: Long, rate: Int, pcm: ShortArray, previous: Pair<Long, ShortArray>?, repeatMagic: Boolean = false): ByteArray {
        val firstBytes = 12 + pcm.size * 2
        val secondBytes = if (previous == null) 0 else (if (repeatMagic) 4 else 0) + 8 + previous.second.size * 2
        val out = ByteArray(firstBytes + secondBytes)
        System.arraycopy(AUDIO_MAGIC, 0, out, 0, 4)
        putInt(out, 4, seq.toInt())
        putInt(out, 8, rate)
        putPcm(out, 12, pcm)
        if (previous != null) {
            var o = firstBytes
            if (repeatMagic) {
                System.arraycopy(AUDIO_MAGIC, 0, out, o, 4)
                o += 4
            }
            putInt(out, o, previous.first.toInt())
            putInt(out, o + 4, rate)
            putPcm(out, o + 8, previous.second)
        }
        return out
    }

    /** Parses a host datagram into 0, 1 or 2 blocks (newest first, as sent). Never throws. */
    fun parseAudio(buf: ByteArray, len: Int): List<Block> {
        if (len < 12 || !startsWith(buf, 0, AUDIO_MAGIC)) return emptyList()
        val seq = getInt(buf, 4).toLong() and 0xFFFFFFFFL
        val rate = getInt(buf, 8)
        if (rate !in 8_000..192_000) return emptyList()
        val tenMs = (rate / 100) * 4 // bytes of 10 ms stereo s16
        val rest = len - 12
        // One block only, or a first block of another length: take everything that is left.
        if (rest < tenMs + 8) {
            val samples = readPcm(buf, 12, rest) ?: return emptyList()
            return listOf(Block(seq, rate, samples))
        }
        val first = Block(seq, rate, readPcm(buf, 12, tenMs) ?: return emptyList())
        var o = 12 + tenMs
        if (len - o >= 12 && startsWith(buf, o, AUDIO_MAGIC)) o += 4
        if (len - o < 8) return listOf(first)
        val seq2 = getInt(buf, o).toLong() and 0xFFFFFFFFL
        val rate2 = getInt(buf, o + 4)
        if (rate2 !in 8_000..192_000) return listOf(first)
        val samples2 = readPcm(buf, o + 8, minOf(len - o - 8, (rate2 / 100) * 4)) ?: return listOf(first)
        return listOf(first, Block(seq2, rate2, samples2))
    }

    private fun readPcm(buf: ByteArray, offset: Int, bytes: Int): ShortArray? {
        var n = bytes / 2
        n -= n % 2 // whole stereo frames only
        if (n <= 0) return null
        return ShortArray(n) { i ->
            val lo = buf[offset + i * 2].toInt() and 0xFF
            val hi = buf[offset + i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
    }

    private fun putPcm(out: ByteArray, offset: Int, pcm: ShortArray) {
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[offset + i * 2] = (v and 0xFF).toByte()
            out[offset + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    private fun startsWith(buf: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (buf.size < offset + magic.size) return false
        for (i in magic.indices) if (buf[offset + i] != magic[i]) return false
        return true
    }

    internal fun getInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    internal fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }
}

/**
 * Hands packets to the player exactly once and in order, whichever transport (UDP, its redundant copy, or the
 * WebSocket during a switch) brought them. A packet older than the last one delivered is dropped (dedupe);
 * a jump back of more than [restartBack] packets means the host restarted its stream and is accepted.
 * Thread-safe: [offer] runs [deliver] under the gate's lock so two transports can't reorder packets.
 */
class SeqGate(private val restartBack: Long = 200) {
    private var last = -1L

    @Synchronized
    fun reset() {
        last = -1L
    }

    /** Delivers [seq] via [deliver] when it is new; returns true when delivered. */
    @Synchronized
    fun offer(seq: Long, deliver: () -> Unit): Boolean {
        val s = seq and 0xFFFFFFFFL
        if (last >= 0) {
            var d = (s - last) and 0xFFFFFFFFL
            if (d >= 0x80000000L) d -= 0x100000000L
            if (d <= 0 && d > -restartBack) return false
        }
        last = s
        deliver()
        return true
    }
}

/** What the UDP policy wants sent on the WebSocket. */
enum class UdpSignal { NONE, SEND_OK_FALSE, SEND_OK_TRUE }

/**
 * When to use UDP and when to fall back to WebSocket audio (protocol v3.2). Pure logic with an injected clock (ms).
 *
 * - After `hello` with a UDP port we *probe*: no datagram within [probeMs] (counted only while the host shares
 *   sound) → fall back: send `{"type":"udp","ok":false}`.
 * - While UDP is active, [lostMs] without a datagram while the host shares sound → fall back too.
 * - After a fall back, try UDP again after each of [retryDelaysMs] (`{"type":"udp","ok":true}` + a new probe).
 */
class UdpPolicy(
    private val probeMs: Long = 2_000,
    private val lostMs: Long = 1_000,
    private val retryDelaysMs: List<Long> = listOf(60_000, 300_000)
) {
    enum class State { OFF, PROBING, ACTIVE, FALLBACK }

    var state: State = State.OFF
        private set

    /** Times UDP fell back to the WebSocket in this session. */
    var fallbacks: Int = 0
        private set

    private var deadline = 0L
    private var lastDatagram = 0L
    private var retryAt = Long.MAX_VALUE
    private var retries = 0

    val usingUdp: Boolean
        get() = state == State.ACTIVE

    fun reset() {
        state = State.OFF
        fallbacks = 0
        retries = 0
        retryAt = Long.MAX_VALUE
    }

    /** `hello` offered UDP. */
    fun onHello(now: Long) {
        reset()
        state = State.PROBING
        deadline = now + probeMs
    }

    fun onDatagram(now: Long) {
        lastDatagram = now
        // In FALLBACK the host was told to use the WebSocket; late datagrams don't switch back (only a retry does).
        if (state == State.PROBING) state = State.ACTIVE
    }

    /** Call a few times per second. [mirroring] = the host is sharing sound (otherwise silence is expected). */
    fun tick(now: Long, mirroring: Boolean): UdpSignal {
        when (state) {
            State.OFF -> Unit
            State.PROBING -> {
                if (!mirroring) {
                    deadline = now + probeMs
                } else if (now >= deadline) {
                    return fallBack(now)
                }
            }
            State.ACTIVE -> {
                if (!mirroring) {
                    lastDatagram = now
                } else if (now - lastDatagram >= lostMs) {
                    return fallBack(now)
                }
            }
            State.FALLBACK -> {
                if (now >= retryAt) {
                    retryAt = Long.MAX_VALUE
                    state = State.PROBING
                    deadline = now + probeMs
                    return UdpSignal.SEND_OK_TRUE
                }
            }
        }
        return UdpSignal.NONE
    }

    private fun fallBack(now: Long): UdpSignal {
        state = State.FALLBACK
        fallbacks++
        retryAt = retryDelaysMs.getOrNull(retries)?.let { now + it } ?: Long.MAX_VALUE
        retries++
        return UdpSignal.SEND_OK_FALSE
    }
}

/**
 * Receives v3.2 UDP audio from one host for one WebSocket session. Pure JVM (java.net): one thread binds a
 * DatagramSocket on any local port, sends a keepalive at once and every [keepaliveMs], and hands every audio block
 * to [Listener.onBlock] (newest-first order is fixed up by the caller's [SeqGate]: the redundant older copy is
 * offered first). Works on a hotspot and on normal Wi-Fi: the host learns our address from the keepalive.
 */
class UdpAudioReceiver(
    private val host: String,
    private val port: Int,
    @Volatile private var token: ByteArray,
    private val listener: Listener,
    private val keepaliveMs: Long = 1_000,
    /** Runs first on the receiver thread (Android: raise it to urgent-audio priority). */
    private val threadSetup: () -> Unit = {},
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    interface Listener {
        /** A datagram with valid audio arrived (before its blocks are handed over). */
        fun onDatagram(nowMs: Long) = Unit

        fun onBlock(block: UdpWire.Block)

        /** The socket couldn't be opened or failed; UDP is unusable for this session. */
        fun onError(reason: String) = Unit
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    val datagrams = AtomicLong()
    val keepalivesSent = AtomicLong()

    /** Local UDP port once bound, else 0. */
    @Volatile
    var localPort: Int = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ run() }, "DualEarbuds-UDP")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    /** A new session token (same host): keepalives carry it from the next one on. */
    fun updateToken(newToken: ByteArray) {
        token = newToken
    }

    fun close() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    private fun run() {
        try {
            threadSetup()
        } catch (_: Throwable) {
        }
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                receiveBufferSize = 512 * 1024 // a burst after a Wi-Fi power-save pause must fit
                soTimeout = 100
            }
        } catch (t: Throwable) {
            running.set(false)
            listener.onError("UDP socket: ${t.message ?: t.javaClass.simpleName}")
            return
        }
        socket = s
        localPort = s.localPort
        val addr = try {
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (t: Throwable) {
            running.set(false)
            s.close()
            listener.onError("UDP address: ${t.message ?: t.javaClass.simpleName}")
            return
        }
        val buf = ByteArray(65_535)
        val packet = DatagramPacket(buf, buf.size)
        var nextKeepalive = 0L
        try {
            while (running.get()) {
                val now = clockMs()
                if (now >= nextKeepalive) {
                    nextKeepalive = now + keepaliveMs
                    try {
                        val ka = UdpWire.keepalive(token)
                        s.send(DatagramPacket(ka, ka.size, addr))
                        keepalivesSent.incrementAndGet()
                    } catch (_: Throwable) {
                        // Wi-Fi blip: keep trying every second.
                    }
                }
                try {
                    packet.setLength(buf.size)
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val blocks = UdpWire.parseAudio(buf, packet.length)
                if (blocks.isEmpty()) continue
                datagrams.incrementAndGet()
                listener.onDatagram(clockMs())
                // Older (redundant) copy first so the gate sees sequence numbers in order.
                for (i in blocks.indices.reversed()) listener.onBlock(blocks[i])
            }
        } catch (t: Throwable) {
            if (running.get()) listener.onError("UDP: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            running.set(false)
            try {
                s.close()
            } catch (_: Throwable) {
            }
            socket = null
        }
    }
}
