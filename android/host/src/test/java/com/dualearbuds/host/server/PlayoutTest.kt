package com.dualearbuds.host.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.2 common deadline: P = clamp(max(needs, local need), 40, 400), slow decreases. */
class PlayoutTest {
    private val servers = ArrayList<HostServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    @Test
    fun targetIsTheSlowestEarClamped() {
        assertEquals(180, Playout.target(listOf(90, 120), 180))
        assertEquals(250, Playout.target(listOf(90, 250), 180))
        assertEquals(40, Playout.target(emptyList(), 20)) // wired host, nobody synced
        assertEquals(400, Playout.target(listOf(900), 20)) // a listener can't pull P above 400
        assertEquals(40, Playout.target(listOf(5), 0))
    }

    @Test
    fun increasesApplyAtOnce() {
        val p = Playout(initialMs = 100)
        assertTrue(p.step(220, 0, anyoneSynced = true))
        assertEquals(220, p.currentMs)
        assertFalse(p.step(220, 100, anyoneSynced = true))
    }

    @Test
    fun decreasesAreAtMostFiveMsPerSecond() {
        val p = Playout(initialMs = 200)
        p.step(200, 0, anyoneSynced = true)
        var t = 0L
        val seen = ArrayList<Int>()
        // 10 s of 100 ms ticks asking for 100 ms.
        repeat(100) {
            t += 100
            if (p.step(100, t, anyoneSynced = true)) seen += p.currentMs
        }
        assertEquals(150, p.currentMs) // 10 s × 5 ms
        for (i in 1 until seen.size) assertEquals(5, seen[i - 1] - seen[i])
        assertEquals(10, seen.size)
    }

    @Test
    fun decreaseStopsAtTheTargetAndRisesAgainInstantly() {
        val p = Playout(initialMs = 112)
        p.step(112, 0, true)
        p.step(110, 1000, true)
        assertEquals(110, p.currentMs) // a 2 ms step, not past the target
        assertFalse(p.step(110, 2000, true))
        assertTrue(p.step(300, 2100, true))
        assertEquals(300, p.currentMs)
    }

    @Test
    fun withNobodySyncedPFollowsTheTargetAtOnce() {
        val p = Playout(initialMs = 300)
        assertTrue(p.step(60, 0, anyoneSynced = false))
        assertEquals(60, p.currentMs)
    }

    @Test
    fun localOutputNeed() {
        assertEquals(180, LocalOutput(LocalOutputKind.BLUETOOTH, "Buds").needMs)
        assertEquals(20, LocalOutput(LocalOutputKind.WIRED).needMs)
        assertEquals(20, LocalOutput(LocalOutputKind.SPEAKER).needMs)
        val cal = LocalOutput(LocalOutputKind.BLUETOOTH, "Buds", 143.6)
        assertTrue(cal.isCalibrated)
        assertEquals(144, cal.needMs)
        // Nonsense calibrations fall back to the defaults.
        assertEquals(180, LocalOutput(LocalOutputKind.BLUETOOTH, "Buds", -3.0).needMs)
        assertEquals(180, LocalOutput(LocalOutputKind.BLUETOOTH, "Buds", Double.NaN).needMs)
        assertFalse(LocalOutput(LocalOutputKind.BLUETOOTH, "Buds", 9000.0).isCalibrated)
    }

    @Test
    fun calibrationTableDecodeAndLookup() {
        val t = LocalOutput.decodeCalibration("Galaxy Buds2\t151.25\nbroken line\nEvo Z-Buds \t98.00\nx\tNaN\n\t12")
        assertEquals(2, t.size)
        assertEquals(151.25, LocalOutput.lookup(t, "Galaxy Buds2")!!, 1e-9)
        assertEquals(98.0, LocalOutput.lookup(t, "evo z-buds")!!, 1e-9)
        assertNull(LocalOutput.lookup(t, "Other"))
        assertNull(LocalOutput.lookup(t, ""))
        assertTrue(LocalOutput.decodeCalibration(null).isEmpty())
    }

    @Test
    fun statusNeedAndSyncErrorAreParsedAndClamped() {
        val st = HostMessages.parseStatus(MiniJson.parseObject("""{"type":"status","needMs":950,"syncErrMs":-7.6}""")!!)
        assertEquals(400, st.needMs)
        assertEquals(-7, st.syncErrMs)
        val none = HostMessages.parseStatus(MiniJson.parseObject("""{"type":"status"}""")!!)
        assertNull(none.needMs)
        assertNull(none.syncErrMs)
    }

    @Test
    fun serverAnnouncesPlayoutFromTheSlowestSyncedEar() {
        val s = HostServer(HostIdentity(pcName = "Test"), "ABCD2345", { null })
        servers += s
        s.start(0)
        s.setLocalNeed(180) // host's Bluetooth earbuds, not calibrated
        assertTrue(waitUntil { s.playoutMs == 180 }) // nobody synced: follows at once
        WsTestClient(s.port, "k=ABCD2345&client=android").use { c ->
            assertEquals(180.0, c.readJson("hello")!!["playoutMs"])
            c.sendText("""{"type":"status","synced":true,"needMs":260,"syncErrMs":3}""")
            val m = c.readJson("playout")
            assertNotNull(m)
            assertEquals(260.0, m!!["ms"])
            assertEquals(260, s.playoutMs)
            assertTrue(waitUntil { s.listeners().firstOrNull()?.needMs == 260 })
            assertEquals(3, s.listeners().first().syncErrMs)
            // A listener in buffer mode doesn't count: with nobody synced P returns to the host's own need.
            c.sendText("""{"type":"status","synced":false,"needMs":390}""")
            assertEquals(180.0, c.readJson("playout")!!["ms"])
        }
    }

    @Test
    fun syncedListenerWithoutNeedCountsAsTheOldFixedDeadline() {
        val s = HostServer(HostIdentity(pcName = "Test"), "ABCD2345", { null })
        servers += s
        s.start(0)
        s.setLocalNeed(20)
        assertTrue(waitUntil { s.playoutMs == 40 })
        WsTestClient(s.port, "k=ABCD2345").use { c ->
            c.readJson("hello")
            c.sendText("""{"type":"status","synced":true}""")
            assertEquals(120.0, c.readJson("playout")!!["ms"])
        }
    }
}
