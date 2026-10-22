package com.dualearbuds.host.model

import com.dualearbuds.host.server.ListenerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadoutTest {
    private fun l(id: Int, synced: Boolean = true, err: Int? = null, client: String = "android", transport: String = "udp") = ListenerInfo(
        id = id, name = "L$id", client = client, address = "", device = "", earbuds = "", battery = -1, volume = 100,
        bufferMs = 0, underruns = 0, playing = true, rttMs = 5, dropped = 0, synced = synced, syncErrMs = err, transport = transport
    )

    @Test
    fun scope() {
        assertEquals(0f, Readout.scopePosition(0), 0f)
        assertEquals(0.5f, Readout.scopePosition(25), 1e-6f)
        assertEquals(-1f, Readout.scopePosition(-80), 0f)
        assertEquals(1f, Readout.scopePosition(51), 0f)
        assertEquals(SyncLevel.GOOD, Readout.level(-5))
        assertEquals(SyncLevel.FAIR, Readout.level(12))
        assertEquals(SyncLevel.OFF, Readout.level(-21))
        assertEquals(SyncLevel.UNKNOWN, Readout.level(null))
        assertEquals("+3 ms", Readout.offset(3))
        assertEquals("−12 ms", Readout.offset(-12))
        assertEquals("–", Readout.offset(null))
    }

    @Test
    fun header() {
        assertEquals("OFF", Readout.header(false, 48_000, 120, emptyList()))
        assertEquals("LIVE  48 kHz · P 92 ms · 1 ear", Readout.header(true, 48_000, 92, emptyList()))
        val ls = listOf(l(1, err = 3), l(2, err = -7), l(3, synced = false, err = 40))
        assertEquals("LIVE  48 kHz · P 92 ms · 4 ears · sync ±7 ms", Readout.header(true, 48_000, 92, ls))
        assertEquals("44.1 kHz", Readout.kHz(44_100))
        assertNull(Readout.worstSync(listOf(l(1))))
    }

    @Test
    fun headlineAndTransport() {
        assertEquals("Not sharing", Readout.headline(false, false, emptyList()))
        assertEquals("Starting", Readout.headline(false, true, emptyList()))
        assertEquals("Waiting for listeners", Readout.headline(true, false, emptyList()))
        assertEquals("Sharing to 3 ears", Readout.headline(true, false, listOf(l(1), l(2))))
        assertEquals("UDP", Readout.transport(l(1)))
        assertEquals("WS", Readout.transport(l(1, transport = "ws")))
        assertEquals("Browser", Readout.transport(l(1, client = "web", transport = "ws")))
    }
}
