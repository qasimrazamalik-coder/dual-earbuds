package com.dualearbuds.host.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolLogicTest {
    @Test
    fun pinValidation() {
        assertTrue(RoomPin.isValid("1234"))
        assertTrue(RoomPin.isValid("123456"))
        assertFalse(RoomPin.isValid("123"))
        assertFalse(RoomPin.isValid("1234567"))
        assertFalse(RoomPin.isValid("12a4"))
        assertFalse(RoomPin.isValid(null))
        assertEquals("123456", RoomPin.sanitizeInput(" 12-34 5678"))
    }

    @Test
    fun pinCheck() {
        assertEquals(RoomPin.Check.OK, RoomPin.check(null, null))
        assertEquals(RoomPin.Check.OK, RoomPin.check(null, "9999"))
        assertEquals(RoomPin.Check.MISSING, RoomPin.check("1234", null))
        assertEquals(RoomPin.Check.MISSING, RoomPin.check("1234", "  "))
        assertEquals(RoomPin.Check.WRONG, RoomPin.check("1234", "1235"))
        assertEquals(RoomPin.Check.WRONG, RoomPin.check("1234", "12345"))
        assertEquals(RoomPin.Check.OK, RoomPin.check("1234", " 1234 "))
    }

    @Test
    fun rateLimiterAllowsFivePerSecond() {
        var now = 0L
        val r = RateLimiter(5.0, 5.0) { now }
        assertEquals(5, (0 until 20).count { r.tryAcquire() })
        now += 200
        assertTrue(r.tryAcquire())
        assertFalse(r.tryAcquire())
        now += 10_000
        assertEquals(5, (0 until 20).count { r.tryAcquire() })
    }

    @Test
    fun syncReplyEchoesCAndAddsHostTime() {
        val m = MiniJson.parseObject(ClockTrack.syncReply(42.0, 1234)!!)!!
        assertEquals("sync", m["type"])
        assertEquals(42.0, m["c"])
        assertEquals(1234.0, m["h"])
        assertNull(ClockTrack.syncReply(null, 1))
        assertNull(ClockTrack.syncReply(Double.NaN, 1))
    }

    @Test
    fun clockTrackKeepsNewestPacketAndUnsignedSeq() {
        val t = ClockTrack()
        assertNull(t.clockMessage())
        t.record(-1, 77) // uint32 0xFFFFFFFF
        val m = MiniJson.parseObject(t.clockMessage()!!)!!
        assertEquals(4294967295.0, m["seq"])
        assertEquals(77.0, m["h"])
        t.reset()
        assertNull(t.clockMessage())
    }

    @Test
    fun remoteActionsParse() {
        assertEquals(RemoteAction.PLAY_PAUSE, RemoteAction.parse("playpause"))
        assertEquals(RemoteAction.VOL_DOWN, RemoteAction.parse("voldown"))
        assertNull(RemoteAction.parse("shutdown"))
        assertNull(RemoteAction.parse(null))
    }

    @Test
    fun statusIsClamped() {
        val st = HostMessages.parseStatus(MiniJson.parseObject("""{"battery":400,"volume":-3,"underruns":-1,"synced":true}""")!!)
        assertEquals(100, st.battery)
        assertEquals(0, st.volume)
        assertEquals(0, st.underruns)
        assertEquals(true, st.synced)
        assertNull(st.device)
    }
}
