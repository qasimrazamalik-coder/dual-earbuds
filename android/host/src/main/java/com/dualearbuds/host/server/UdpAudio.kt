package com.dualearbuds.host.server

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP audio of protocol v3.2 for native listeners: one socket on the TCP port number, a receiver
 * thread that learns listeners' addresses from token keepalives, and a sender thread that sends every
 * packet with the previous one again (redundancy).
 *
 * The capture thread only calls [offer], which copies the packet into a bounded ring (≈300 ms, oldest
 * dropped and counted) and never touches the network. Allocation-free once running.
 */
internal class UdpAudio(
    private val limits: HostLimits,
    private val clock: () -> Long,
    private val sessions: () -> Array<ListenerSession>,
    private val onTransportChange: () -> Unit,
    private val threadSetup: () -> Unit
) {
    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** Bound UDP port, or 0 when UDP is not available. */
    @Volatile
    var port: Int = 0
        private set

    /** Packets dropped because the sender fell more than ≈300 ms behind. */
    val droppedPackets = AtomicLong(0)

    /** Keepalives ignored: over the per-IP rate, malformed, or with an unknown token. */
    val ignoredKeepalives = AtomicLong(0)

    // ---- bounded queue (capture thread → sender thread)
    private val lock = Object()
    private val capacity = maxOf(2, limits.udpQueueMs / AudioPacket.PACKET_MS)
    private var slots: Array<ByteArray> = emptyArray()
    private val slotSeq = IntArray(capacity)
    private val slotRate = IntArray(capacity)
    private val slotLen = IntArray(capacity)
    private var head = 0
    private var size = 0

    private var sender: Thread? = null
    private var receiver: Thread? = null

    /** Binds UDP [port] on all interfaces. Returns false when it can't (then listeners use WebSocket audio). */
    fun start(port: Int): Boolean {
        if (running) return true
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = 500
                try {
                    sendBufferSize = 256 * 1024
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            return false
        }
        socket = s
        this.port = s.localPort
        running = true
        sender = Thread({ sendLoop(s) }, "host-udp-send").apply {
            isDaemon = true
            start()
        }
        receiver = Thread({ receiveLoop(s) }, "host-udp-recv").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        socket?.close()
        socket = null
        port = 0
        synchronized(lock) {
            size = 0
            lock.notifyAll()
        }
        sender?.interrupt()
        receiver?.interrupt()
        sender = null
        receiver = null
    }

    /** Capture thread: queues one packet. Never blocks on the network. */
    fun offer(seq: Int, rate: Int, pcm: ByteArray, off: Int, len: Int) {
        if (!running) return
        synchronized(lock) {
            if (slots.isEmpty() || slots[0].size < len) slots = Array(capacity) { ByteArray(len) }
            if (size == capacity) {
                head = (head + 1) % capacity
                size--
                droppedPackets.incrementAndGet()
            }
            val i = (head + size) % capacity
            System.arraycopy(pcm, off, slots[i], 0, len)
            slotSeq[i] = seq
            slotRate[i] = rate
            slotLen[i] = len
            size++
            lock.notify()
        }
    }

    private fun sendLoop(s: DatagramSocket) {
        try {
            threadSetup()
        } catch (_: Throwable) {
        }
        var cur = ByteArray(0)
        var prev = ByteArray(0)
        var curSeq = 0
        var curRate = 0
        var curLen = 0
        var prevSeq = 0
        var prevLen = -1
        var datagram = ByteArray(0)
        val packet = DatagramPacket(ByteArray(0), 0)
        while (running) {
            // Take the oldest packet (copied out under the lock so the ring can be reused at once).
            synchronized(lock) {
                while (running && size == 0) {
                    try {
                        lock.wait(500)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!running) return
                val i = head
                curLen = slotLen[i]
                if (cur.size < curLen) cur = ByteArray(curLen)
                System.arraycopy(slots[i], 0, cur, 0, curLen)
                curSeq = slotSeq[i]
                curRate = slotRate[i]
                head = (head + 1) % capacity
                size--
            }
            val withPrev = prevLen == curLen && prevSeq == curSeq - 1
            val n = UdpPacket.audioSize(curLen, if (withPrev) 2 else 1)
            if (datagram.size < UdpPacket.audioSize(curLen, 2)) datagram = ByteArray(UdpPacket.audioSize(curLen, 2))
            UdpPacket.writeMagic(datagram)
            val next = UdpPacket.writeBlock(datagram, 4, curSeq, curRate, cur, 0, curLen)
            if (withPrev) UdpPacket.writeBlock(datagram, next, prevSeq, curRate, prev, 0, prevLen)
            packet.setData(datagram, 0, n)
            val now = clock()
            for (c in sessions()) {
                val addr = c.udpTarget(now) ?: continue
                try {
                    packet.socketAddress = addr
                    s.send(packet)
                } catch (_: IOException) {
                    if (!running) return
                } catch (_: RuntimeException) {
                }
            }
            // The packet just sent becomes the redundant copy in the next datagram.
            val t = prev
            prev = cur
            cur = t
            prevSeq = curSeq
            prevLen = curLen
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(64)
        val packet = DatagramPacket(buf, buf.size)
        val perIp = HashMap<InetAddress, RateLimiter>()
        while (running) {
            try {
                packet.setData(buf, 0, buf.size)
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (!running) return
                continue
            }
            val ip = packet.address ?: continue
            if (perIp.size > MAX_TRACKED_IPS && !perIp.containsKey(ip)) perIp.clear()
            val limiter = perIp.getOrPut(ip) { RateLimiter(limits.keepalivePerSecond, limits.keepaliveBurst, clock) }
            if (!limiter.tryAcquire() || !UdpPacket.isKeepalive(buf, packet.length)) {
                ignoredKeepalives.incrementAndGet()
                continue
            }
            val session = sessions().firstOrNull { it.matchesToken(buf, UdpPacket.TOKEN_OFFSET) }
            if (session == null || !session.udpOffered) {
                ignoredKeepalives.incrementAndGet() // unknown token: dropped silently
                continue
            }
            val now = clock()
            val wasLive = session.usesUdp(now)
            val from = packet.socketAddress as? InetSocketAddress ?: continue
            if (session.udpAddress != from) session.udpAddress = from
            session.udpHeardAt = now
            if (!wasLive && session.usesUdp(now)) onTransportChange()
        }
    }

    private companion object {
        const val MAX_TRACKED_IPS = 256
    }
}
