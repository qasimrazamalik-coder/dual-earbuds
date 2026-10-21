package com.dualearbuds.companion.net.discovery

import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.net.PinPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test
    fun parsesTxtRecord() {
        val h = DiscoveredHost.fromTxt(
            "LAPTOP", "/192.168.1.20", 8790,
            mapOf("v" to "3", "name" to "Qasim's laptop", "host" to "windows", "pin" to "1", "k" to "abcd2345")
        )!!
        assertEquals("Qasim's laptop", h.name)
        assertEquals(HostKind.LAPTOP, h.kind)
        assertEquals("192.168.1.20", h.address)
        assertTrue(h.pinRequired)
        assertEquals("ABCD2345", h.key)
        assertTrue(h.canJoinDirectly)
        assertEquals(3, h.protocol)
    }

    @Test
    fun withoutKeyAsksForLink() {
        val h = DiscoveredHost.fromTxt("Phone", "10.0.0.5", 8790, mapOf("host" to "android", "pin" to "0", "k" to "bad!"))!!
        assertNull(h.key)
        assertFalse(h.canJoinDirectly)
        assertFalse(h.pinRequired)
        assertEquals(HostKind.PHONE, h.kind)
        assertEquals("Phone", h.name)
    }

    @Test
    fun rejectsBadAddress() {
        assertNull(DiscoveredHost.fromTxt("x", "", 8790, emptyMap()))
        assertNull(DiscoveredHost.fromTxt("x", "1.2.3.4", 0, emptyMap()))
    }

    @Test
    fun resolveQueueRunsOneAtATime() {
        val q = ResolveQueue()
        assertEquals("a", q.offer("a"))
        assertNull(q.offer("b"))
        assertNull(q.offer("a")) // in flight: ignored
        assertNull(q.offer("b")) // already queued
        assertNull(q.offer("c"))
        q.remove("c") // service lost before its turn
        assertEquals("b", q.done())
        assertNull(q.done())
        assertEquals(0, q.size)
    }

    @Test
    fun pinPolicy() {
        val p = PinPolicy(freeTries = 3, cooldownMs = 30_000)
        assertEquals("1234", p.clean(" 1234 "))
        assertNull(p.clean("12a4"))
        assertNull(p.clean("123"))
        assertNull(p.clean("1234567"))
        p.onWrong(0)
        p.onWrong(0)
        assertEquals(0, p.waitMs(0))
        p.onWrong(1_000)
        assertEquals(30_000, p.waitMs(1_000))
        assertEquals(0, p.waitMs(31_000))
        p.onAccepted()
        assertEquals(0, p.wrongCount)
    }
}
