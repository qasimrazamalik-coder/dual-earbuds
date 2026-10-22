package com.dualearbuds.companion.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UdpAudioTest {

    private val token = ByteArray(16) { (it * 7 + 1).toByte() }

    /** 10 ms of 48 kHz stereo whose first sample carries the sequence number, so content can be checked. */
    private fun pcm(seq: Long) = ShortArray(960) { if (it == 0) (seq % 30_000).toShort() else (it % 100).toShort() }

    // ------------------------------------------------------------------ wire format

    @Test
    fun keepaliveLayout() {
        val ka = UdpWire.keepalive(token)
        assertEquals(21, ka.size)
        assertEquals("DE3U", String(ka, 0, 4, Charsets.US_ASCII))
        assertEquals(1, ka[4].toInt())
        assertArrayEquals(token, ka.copyOfRange(5, 21))
    }

    @Test
    fun tokenFromHexOrBase64() {
        val hex = token.joinToString("") { "%02x".format(it) }
        assertArrayEquals(token, UdpWire.parseToken(hex))
        assertArrayEquals(token, UdpWire.parseToken(hex.uppercase()))
        val b64 = java.util.Base64.getEncoder().encodeToString(token)
        assertArrayEquals(token, UdpWire.parseToken(b64))
        assertArrayEquals(token, UdpWire.parseToken(b64.trimEnd('=')))
        assertNull(UdpWire.parseToken("short"))
        assertNull(UdpWire.parseToken(null))
    }

    @Test
    fun parsesPacketAndItsRedundantCopy() {
        for (repeatMagic in listOf(false, true)) {
            val d = UdpWire.audioDatagram(41, 48_000, pcm(41), 40L to pcm(40), repeatMagic)
            val blocks = UdpWire.parseAudio(d, d.size)
            assertEquals(2, blocks.size)
            assertEquals(41L, blocks[0].seq)
            assertEquals(40L, blocks[1].seq)
            assertEquals(48_000, blocks[1].rate)
            assertArrayEquals(pcm(41), blocks[0].samples)
            assertArrayEquals(pcm(40), blocks[1].samples)
        }
        val single = UdpWire.audioDatagram(0, 44_100, ShortArray(882), null)
        assertEquals(1, UdpWire.parseAudio(single, single.size).size)
        assertTrue(UdpWire.parseAudio(ByteArray(30), 30).isEmpty())
        assertTrue(UdpWire.parseAudio("DE3U".toByteArray() + ByteArray(20), 24).isEmpty())
    }

    @Test
    fun wrapsSequenceAsUnsigned() {
        val d = UdpWire.audioDatagram(0xFFFFFFFFL, 48_000, pcm(1), 0xFFFFFFFEL to pcm(2))
        val b = UdpWire.parseAudio(d, d.size)
        assertEquals(0xFFFFFFFFL, b[0].seq)
        assertEquals(0xFFFFFFFEL, b[1].seq)
    }

    // ------------------------------------------------------------------ gate

    @Test
    fun gateDedupesAndKeepsOrder() {
        val g = SeqGate()
        val out = mutableListOf<Long>()
        for (s in listOf(5L, 4L, 6L, 5L, 6L, 8L, 7L, 9L)) g.offer(s) { out += s }
        assertEquals(listOf(5L, 6L, 8L, 9L), out)
    }

    @Test
    fun gateAcceptsWrapAndStreamRestart() {
        val g = SeqGate()
        val out = mutableListOf<Long>()
        for (s in listOf(0xFFFFFFFEL, 0xFFFFFFFFL, 0L, 1L)) g.offer(s) { out += s }
        assertEquals(4, out.size)
        assertTrue(g.offer(5_000) {})
        assertTrue(g.offer(3) {}) // host restarted at 0..: far behind = new stream
    }

    // ------------------------------------------------------------------ policy

    @Test
    fun fallsBackWhenNothingArrivesWithinTwoSeconds() {
        val p = UdpPolicy()
        p.onHello(0)
        assertEquals(UdpSignal.NONE, p.tick(1_500, mirroring = true))
        assertEquals(UdpSignal.SEND_OK_FALSE, p.tick(2_000, mirroring = true))
        assertFalse(p.usingUdp)
        assertEquals(UdpSignal.NONE, p.tick(2_250, mirroring = true))
    }

    @Test
    fun noFallbackWhileTheHostIsNotSharing() {
        val p = UdpPolicy()
        p.onHello(0)
        for (t in 0L..10_000L step 250) assertEquals(UdpSignal.NONE, p.tick(t, mirroring = false))
        p.onDatagram(10_500)
        assertTrue(p.usingUdp)
        // Host pauses sharing: silence is fine.
        for (t in 10_500L..20_000L step 250) assertEquals(UdpSignal.NONE, p.tick(t, mirroring = false))
        assertTrue(p.usingUdp)
    }

    @Test
    fun activeUdpThatStopsFallsBackThenRetriesLater() {
        val p = UdpPolicy(retryDelaysMs = listOf(60_000))
        p.onHello(0)
        p.onDatagram(100)
        assertTrue(p.usingUdp)
        for (t in 100L..1_000L step 100) {
            p.onDatagram(t)
            assertEquals(UdpSignal.NONE, p.tick(t, true))
        }
        assertEquals(UdpSignal.SEND_OK_FALSE, p.tick(2_000, true))
        p.onDatagram(2_100) // a late datagram doesn't switch back by itself
        assertFalse(p.usingUdp)
        assertEquals(UdpSignal.NONE, p.tick(30_000, true))
        assertEquals(UdpSignal.SEND_OK_TRUE, p.tick(62_000, true))
        p.onDatagram(62_100)
        assertTrue(p.usingUdp)
        // Fails again: no more retries left.
        assertEquals(UdpSignal.SEND_OK_FALSE, p.tick(63_200, true))
        assertEquals(UdpSignal.NONE, p.tick(10_000_000, true))
        assertEquals(2, p.fallbacks)
    }

    // ------------------------------------------------------------------ real sockets on localhost

    private class FakeHost {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 3_000 }
        val port: Int get() = socket.localPort
        val keepalives = Collections.synchronizedList(mutableListOf<ByteArray>())

        /** Waits for a keepalive and returns the listener's address. */
        fun awaitListener(): SocketAddress {
            val buf = ByteArray(64)
            val p = DatagramPacket(buf, buf.size)
            socket.receive(p)
            keepalives += buf.copyOf(p.length)
            return p.socketAddress
        }

        fun send(to: SocketAddress, d: ByteArray) = socket.send(DatagramPacket(d, d.size, to))
    }

    private class Collector(val expected: Int) : UdpAudioReceiver.Listener {
        val gate = SeqGate()
        val delivered = Collections.synchronizedList(mutableListOf<Long>())
        val firstSamples = Collections.synchronizedList(mutableListOf<Short>())
        val done = CountDownLatch(1)

        override fun onBlock(block: UdpWire.Block) {
            gate.offer(block.seq) {
                delivered += block.seq
                firstSamples += block.samples[0]
            }
            if (block.seq.toInt() == expected - 1) done.countDown()
        }
    }

    private fun stream(host: FakeHost, to: SocketAddress, count: Int, drop: (Int) -> Boolean) {
        for (seq in 0 until count) {
            if (drop(seq)) continue
            val prev = if (seq > 0) (seq - 1).toLong() to pcm(seq - 1L) else null
            host.send(to, UdpWire.audioDatagram(seq.toLong(), 48_000, pcm(seq.toLong()), prev))
            if (seq % 5 == 0) Thread.sleep(1) // don't overrun the loopback socket buffer
        }
    }

    @Test
    fun keepaliveCarriesTokenAndSingleLossesCostNothing() {
        val host = FakeHost()
        val c = Collector(expected = 500)
        val rx = UdpAudioReceiver("127.0.0.1", host.port, token, c)
        rx.start()
        try {
            val to = host.awaitListener()
            assertArrayEquals(UdpWire.keepalive(token), host.keepalives[0])
            // Every 4th datagram lost, plus ~10 % random single losses (never two in a row).
            val r = Random(11)
            var lastDropped = -10
            stream(host, to, 500) { seq ->
                val d = seq != 499 && seq - lastDropped > 1 && (seq % 4 == 3 || r.nextInt(10) == 0)
                if (d) lastDropped = seq
                d
            }
            assertTrue("timed out", c.done.await(3, TimeUnit.SECONDS))
            assertEquals((0L until 500L).toList(), c.delivered.toList())
            assertEquals((0 until 500).map { (it % 30_000).toShort() }, c.firstSamples.toList())
        } finally {
            rx.close()
            host.socket.close()
        }
    }

    @Test
    fun twoLossesInARowLeaveOneGapAndDuplicatesAreDropped() {
        val host = FakeHost()
        val c = Collector(expected = 100)
        val rx = UdpAudioReceiver("127.0.0.1", host.port, token, c)
        rx.start()
        try {
            val to = host.awaitListener()
            stream(host, to, 60) { it == 30 || it == 31 }
            // The host repeats a few datagrams (e.g. retransmit during a switch): all duplicates.
            for (seq in 55 until 60) {
                host.send(to, UdpWire.audioDatagram(seq.toLong(), 48_000, pcm(seq.toLong()), (seq - 1L) to pcm(seq - 1L)))
            }
            for (seq in 60 until 100) {
                host.send(to, UdpWire.audioDatagram(seq.toLong(), 48_000, pcm(seq.toLong()), (seq - 1L) to pcm(seq - 1L)))
            }
            assertTrue("timed out", c.done.await(3, TimeUnit.SECONDS))
            val expected = (0L until 100L).filter { it != 30L }
            assertEquals(expected, c.delivered.toList())
            assertEquals(98L + 5L, rx.datagrams.get())
        } finally {
            rx.close()
            host.socket.close()
        }
    }

    @Test
    fun sendsKeepalivesEverySecond() {
        val host = FakeHost()
        val rx = UdpAudioReceiver("127.0.0.1", host.port, token, Collector(1), keepaliveMs = 200)
        rx.start()
        try {
            repeat(4) { assertNotNull(host.awaitListener()) }
            val newToken = ByteArray(16) { 9 }
            rx.updateToken(newToken)
            var sawNew = false
            repeat(3) {
                host.awaitListener()
                if (host.keepalives.last().copyOfRange(5, 21).contentEquals(newToken)) sawNew = true
            }
            assertTrue(sawNew)
            assertTrue(rx.keepalivesSent.get() >= 6)
            assertTrue(rx.localPort > 0)
        } finally {
            rx.close()
            host.socket.close()
        }
    }

    @Test
    fun badHostReportsError() {
        val latch = CountDownLatch(1)
        val rx = UdpAudioReceiver("no-such-host.invalid", 9, token, object : UdpAudioReceiver.Listener {
            override fun onBlock(block: UdpWire.Block) = Unit
            override fun onError(reason: String) = latch.countDown()
        })
        rx.start()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        rx.close()
    }
}
