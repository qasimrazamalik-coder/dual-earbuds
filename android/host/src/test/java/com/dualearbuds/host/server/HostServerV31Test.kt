package com.dualearbuds.host.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** v3.1 additions on the wire: room PIN, clock sync, remote control, talk-over, sound source. */
class HostServerV31Test {
    private val key = "ABCD2345"
    private val page = "<!doctype html><title>player</title>".toByteArray()
    private val servers = ArrayList<HostServer>()

    private fun server(limits: HostLimits = HostLimits()): HostServer {
        val s = HostServer(HostIdentity(pcName = "Test Phone"), key, { page }, limits)
        s.start(0)
        servers += s
        return s
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    @Test
    fun infoAndHelloCarryV31Fields() {
        val s = server()
        var m = MiniJson.parseObject(httpGet(s.port, "/info?k=$key")!!.text)!!
        assertEquals(false, m["pin"])
        assertEquals(false, m["remote"])
        assertEquals("all", m["source"])
        assertFalse(m.containsKey("sourceApp"))
        s.setPin("4321")
        s.setRemoteAllowed(true)
        s.setSource("Spotify")
        m = MiniJson.parseObject(httpGet(s.port, "/info?k=$key")!!.text)!!
        assertEquals(true, m["pin"])
        assertEquals(true, m["remote"])
        assertEquals("app", m["source"])
        assertEquals("DualEarbuds", m["app"]) // still identifies the service
        assertEquals("Spotify", m["sourceApp"])
        assertFalse("the PIN itself is never published", httpGet(s.port, "/info?k=$key")!!.text.contains("4321"))
        WsTestClient(s.port, "k=$key&p=4321").use { c ->
            val hello = c.readJson("hello")!!
            assertEquals(120.0, hello["playoutMs"])
            assertEquals(true, hello["remote"])
            assertEquals("app", hello["source"])
            assertEquals("Spotify", hello["app"])
        }
    }

    @Test
    fun pinIsEnforcedOnWebSocketAndWrongPinOnPage() {
        val s = server()
        s.setPin("123456")
        assertEquals(403, WsTestClient(s.port, "k=$key").use { it.status })
        assertEquals(403, WsTestClient(s.port, "k=$key&p=000000").use { it.status })
        assertEquals(101, WsTestClient(s.port, "k=$key&p=123456").use { it.status })
        // The page loads without p (it asks for the PIN), but a wrong p is refused.
        assertEquals(200, httpGet(s.port, "/?k=$key")!!.status)
        assertEquals(403, httpGet(s.port, "/?k=$key&p=999999")!!.status)
        assertEquals(200, httpGet(s.port, "/?k=$key&p=123456")!!.status)
        s.setPin(null)
        assertEquals(101, WsTestClient(s.port, "k=$key").use { it.status })
    }

    @Test
    fun wrongPinsCountTowardTheLockout() {
        val s = server()
        s.setPin("2468")
        for (i in 0 until 9) assertEquals(403, httpGet(s.port, "/ws?k=$key&p=100$i")!!.status)
        // A missing PIN does not count.
        repeat(5) { assertEquals(403, httpGet(s.port, "/ws?k=$key")!!.status) }
        assertNull("10th distinct wrong PIN: ignored", httpGet(s.port, "/ws?k=$key&p=1999"))
        assertNull(httpGet(s.port, "/info?k=$key"))
    }

    @Test
    fun syncIsAnsweredAndClockIsSentEverySecond() {
        val s = server(HostLimits(clockIntervalMs = 200))
        s.setSharing(48_000)
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            c.sendText("""{"type":"sync","c":1712345678901.5}""")
            val r = c.readJson("sync")!!
            assertEquals(1712345678901.5, r["c"])
            assertTrue((r["h"] as Double) >= 0.0)
            assertTrue(waitUntil { s.listeners().firstOrNull()?.synced == true })

            val pcm = ByteArray(1920)
            s.sendAudio(pcm, 0, pcm.size, capturedAtHostMs = 1000)
            s.sendAudio(pcm, 0, pcm.size, capturedAtHostMs = 1010)
            var clock: Map<String, Any?>? = null
            for (i in 0 until 10) {
                clock = c.readJson("clock") ?: break
                if (clock["seq"] == 1.0) break
            }
            assertNotNull(clock)
            assertEquals(1.0, clock!!["seq"])
            assertEquals(1010.0, clock["h"])
        }
    }

    @Test
    fun remoteIsIgnoredWhenOffAndRateLimitedWhenOn() {
        val s = server()
        val got = Collections.synchronizedList(ArrayList<RemoteAction>())
        s.onRemote = { got += it }
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            c.sendText("""{"type":"remote","action":"next"}""")
            Thread.sleep(150)
            assertTrue(got.isEmpty())
            s.setRemoteAllowed(true)
            c.sendText("""{"type":"remote","action":"bogus"}""")
            repeat(20) { c.sendText("""{"type":"remote","action":"playpause"}""") }
            assertTrue(waitUntil { got.size >= 5 })
            Thread.sleep(150)
            assertTrue("at most ~5 per second, got ${got.size}", got.size in 5..6)
            assertTrue(got.all { it == RemoteAction.PLAY_PAUSE })
        }
    }

    @Test
    fun talkIsBroadcastAndRepeatedToLateJoiners() {
        val s = server()
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            s.setTalking(true)
            assertEquals(true, c.readJson("talk")!!["on"])
            WsTestClient(s.port, "k=$key").use { late ->
                late.readJson("hello")!!
                assertEquals(true, late.readJson("talk")!!["on"])
            }
            s.setTalking(false)
            assertEquals(false, c.readJson("talk")!!["on"])
        }
    }

    @Test
    fun windowsClientIsRecognised() {
        val s = server()
        WsTestClient(s.port, "k=$key&client=windows&name=Laptop").use { c ->
            c.readJson("hello")!!
            assertTrue(waitUntil { s.listeners().size == 1 })
            assertEquals("windows", s.listeners()[0].client)
        }
    }
}
