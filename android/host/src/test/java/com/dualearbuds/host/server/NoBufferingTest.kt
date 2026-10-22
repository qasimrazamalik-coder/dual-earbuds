package com.dualearbuds.host.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.2 hygiene: the capture thread never waits for a slow listener; backlog stays ≤ 300 ms. */
class NoBufferingTest {
    private val servers = ArrayList<HostServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    @Test
    fun defaultsMatchTheProtocol() {
        val l = HostLimits()
        assertEquals(300, l.queueMs)
        assertEquals(300, l.udpQueueMs)
        assertTrue(l.sendBufferBytes <= 64 * 1024)
    }

    @Test
    fun aListenerThatStopsReadingNeverSlowsTheCaptureThread() {
        val s = HostServer(HostIdentity(pcName = "T"), "ABCD2345", { null }, HostLimits(writeStallMs = 60_000))
        servers += s
        s.start(0)
        s.setSharing(48_000)
        val pcm = ByteArray(AudioPacket.pcmBytes(48_000))
        WsTestClient(s.port, "k=ABCD2345&client=web").use { c ->
            c.readJson("hello")
            // The client reads nothing more: 3 s of audio (≈5.8 MB) piles up against it.
            var worstNs = 0L
            repeat(3_000) {
                val t0 = System.nanoTime()
                s.sendAudio(pcm, 0, pcm.size)
                worstNs = maxOf(worstNs, System.nanoTime() - t0)
            }
            assertTrue("sendAudio took ${worstNs / 1_000_000} ms", worstNs < 50_000_000L)
            assertTrue(waitUntil { s.listeners().first().dropped > 1_000 })
        }
    }

    @Test
    fun outboxKeepsOnlyTheNewestThirtyPackets() {
        val o = Outbox(HostLimits().queueMs / AudioPacket.PACKET_MS)
        var dropped = 0
        repeat(100) { i -> if (o.offerAudio(byteArrayOf(i.toByte()))) dropped++ }
        assertEquals(30, o.audioQueued())
        assertEquals(70, dropped)
        assertEquals(70.toByte(), (o.take(10) as ByteArray)[0]) // oldest kept = newest 30
    }
}
