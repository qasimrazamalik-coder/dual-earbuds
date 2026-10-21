package com.dualearbuds.host.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class HostServerTest {
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

    // ------------------------------------------------------------ HTTP + key

    @Test
    fun keyIsRequiredAndCheckedCaseInsensitively() {
        val s = server()
        assertEquals(403, httpGet(s.port, "/")!!.status)
        assertEquals(403, httpGet(s.port, "/?k=WRONGKEY")!!.status)
        assertEquals(403, httpGet(s.port, "/info")!!.status)
        assertEquals(403, httpGet(s.port, "/ws?k=")!!.status)
        val ok = httpGet(s.port, "/?k=abcd2345")!!
        assertEquals(200, ok.status)
        assertTrue(ok.headers["content-type"]!!.startsWith("text/html"))
        assertEquals("<!doctype html><title>player</title>", ok.text)
        assertEquals(page.size.toString(), ok.headers["content-length"])
    }

    @Test
    fun faviconNeedsNoKeyAndUnknownPathsAre404() {
        val s = server()
        assertEquals(204, httpGet(s.port, "/favicon.ico")!!.status)
        assertEquals(404, httpGet(s.port, "/nope?k=$key")!!.status)
        assertEquals(404, httpGet(s.port, "/index.html")!!.status)
    }

    @Test
    fun infoJsonDescribesAnAndroidHost() {
        val s = server()
        var m = MiniJson.parseObject(httpGet(s.port, "/info?k=$key")!!.text)!!
        assertEquals("DualEarbuds", m["app"])
        assertEquals(3.0, m["protocol"])
        assertEquals("android", m["host"])
        assertEquals("Test Phone", m["pc"])
        assertEquals(0.0, m["rate"])
        assertEquals(2.0, m["channels"])
        assertEquals("s16le", m["format"])
        assertEquals(false, m["party"])
        assertEquals(8.0, m["maxClients"])
        s.setSharing(48_000)
        s.setParty(true)
        m = MiniJson.parseObject(httpGet(s.port, "/info?k=$key")!!.text)!!
        assertEquals(48_000.0, m["rate"])
        assertEquals(true, m["party"])
        assertEquals(16.0, m["maxClients"])
    }

    @Test
    fun fallsBackToTheNextFreePort() {
        ServerSocket().use { blocker ->
            blocker.reuseAddress = true
            blocker.bind(InetSocketAddress(0))
            val busy = blocker.localPort
            if (busy >= 65535) return
            val s = HostServer(HostIdentity("P"), key, { page })
            servers += s
            val got = s.start(busy, attempts = 10)
            assertTrue(got > busy)
        }
    }

    // ------------------------------------------------------------ WebSocket

    @Test
    fun websocketHandshakeHelloStatusPongAndAudio() {
        val s = server()
        s.setSharing(48_000)
        WsTestClient(s.port, "k=$key&client=web&name=My%20Phone").use { c ->
            assertEquals(101, c.status)
            assertEquals(WsCodec.acceptKey(c.sentKey), c.responseHeaders["sec-websocket-accept"])
            val hello = c.readJson("hello")!!
            assertEquals(3.0, hello["protocol"])
            assertEquals("android", hello["host"])
            assertEquals("Test Phone", hello["pc"])
            assertEquals(48_000.0, hello["rate"])
            assertTrue(waitUntil { s.listeners().size == 1 })
            assertEquals("My Phone", s.listeners()[0].name)
            assertEquals("web", s.listeners()[0].client)

            c.sendText("""{"type":"status","device":"Redmi Note 12 Pro","earbuds":"Evo Z-Buds","battery":80,"volume":90,"bufferMs":60,"underruns":2,"playing":true,"future":1}""")
            assertTrue(waitUntil { s.listeners()[0].battery == 80 })
            val l = s.listeners()[0]
            assertEquals("Redmi Note 12 Pro", l.displayName)
            assertEquals("Evo Z-Buds", l.earbuds)
            assertEquals(90, l.volume)
            assertEquals(2, l.underruns)
            assertTrue(l.playing)

            c.sendText("""{"type":"something-new"}""") // ignored
            c.sendText("""{"type":"pong","t":0}""")

            val pcm = ByteArray(1920) { (it and 0x7F).toByte() }
            s.sendAudio(pcm, 0, pcm.size)
            s.sendAudio(pcm, 0, pcm.size)
            var first: WsTestClient.Frame? = null
            while (first == null) {
                val f = c.readFrame()!!
                if (f.opcode == WsCodec.OP_BINARY) first = f
            }
            val a = first!!
            assertEquals(8 + 1920, a.payload.size)
            assertEquals(48_000, AudioPacket.readRate(a.payload, 0))
            assertEquals(0L, AudioPacket.readSeq(a.payload, 0))
            assertEquals(pcm[100], a.payload[8 + 100])
        }
        assertTrue(waitUntil { s.listeners().isEmpty() })
    }

    @Test
    fun pingIsSentAndPongGivesRoundTripTime() {
        val s = server(HostLimits(pingIntervalMs = 300))
        WsTestClient(s.port, "k=$key&client=android&name=A").use { c ->
            val ping = c.readJson("ping")!!
            c.sendText(MiniJson.obj("type" to "pong", "t" to (ping["t"] as Double)))
            assertTrue(waitUntil { s.listeners().firstOrNull()?.rttMs?.let { it >= 0 } == true })
        }
    }

    @Test
    fun controlKickPartyAndState() {
        val s = server()
        WsTestClient(s.port, "k=$key&client=android&name=A").use { c ->
            c.readJson("hello")!!
            assertTrue(waitUntil { s.listeners().size == 1 })
            val id = s.listeners()[0].id
            s.setParty(true)
            assertEquals(true, c.readJson("party")!!["on"])
            s.setSharing(48_000)
            val st = c.readJson("state")!!
            assertEquals(true, st["mirroring"])
            assertEquals(48_000.0, st["rate"])
            assertTrue(s.setVolume(id, 999))
            assertEquals(150.0, c.readJson("control")!!["volume"])
            assertTrue(s.kick(id))
            assertEquals("Removed by the host", c.readJson("kick")!!["reason"])
            assertEquals(1000, c.awaitClosed())
            assertTrue(waitUntil { s.listeners().isEmpty() })
        }
    }

    @Test
    fun stoppingTellsListenersAndClosesWithGoingAway() {
        val s = server()
        s.setSharing(48_000)
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            assertTrue(waitUntil { s.listeners().size == 1 })
            s.setSharing(0)
            s.stop()
            assertEquals(false, c.readJson("state")!!["mirroring"])
            assertEquals(WsCodec.CLOSE_GOING_AWAY, c.awaitClosed())
        }
    }

    @Test
    fun newLinkKicksListenersAndOldKeyIsRejected() {
        val s = server()
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            s.setKey("ZZZZ2222")
            assertNotNull(c.readJson("kick"))
        }
        assertEquals(403, httpGet(s.port, "/info?k=$key")!!.status)
        assertEquals(200, httpGet(s.port, "/info?k=zzzz2222")!!.status)
    }

    @Test
    fun unmaskedClientFrameClosesWithProtocolError() {
        val s = server()
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            c.sendRaw(WsCodec.textFrame("""{"type":"pong","t":1}"""))
            assertEquals(WsCodec.CLOSE_PROTOCOL, c.awaitClosed())
        }
    }

    @Test
    fun oversizedTextMessageCloses() {
        val s = server()
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            c.sendRaw(WsCodec.maskedFrame(WsCodec.OP_TEXT, ByteArray(5000) { 'a'.code.toByte() }, byteArrayOf(1, 2, 3, 4)))
            assertEquals(WsCodec.CLOSE_TOO_BIG, c.awaitClosed())
        }
    }

    @Test
    fun fragmentedTextOverLimitCloses() {
        val s = server()
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            val m = byteArrayOf(5, 6, 7, 8)
            c.sendRaw(WsCodec.maskedFrame(WsCodec.OP_TEXT, ByteArray(3000) { 'a'.code.toByte() }, m, fin = false))
            c.sendRaw(WsCodec.maskedFrame(WsCodec.OP_CONT, ByteArray(3000) { 'a'.code.toByte() }, m, fin = true))
            assertEquals(WsCodec.CLOSE_TOO_BIG, c.awaitClosed())
        }
    }

    @Test
    fun clientPingGetsPongAndCloseIsEchoed() {
        val s = server()
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            c.sendRaw(WsCodec.maskedFrame(WsCodec.OP_PING, "abc".toByteArray(), byteArrayOf(1, 2, 3, 4)))
            var pong: WsTestClient.Frame? = null
            repeat(10) { if (pong == null) c.readFrame()?.let { if (it.opcode == WsCodec.OP_PONG) pong = it } }
            assertEquals("abc", String(pong!!.payload))
            c.sendRaw(WsCodec.maskedFrame(WsCodec.OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte()), byteArrayOf(1, 2, 3, 4)))
            assertEquals(1000, c.awaitClosed())
        }
    }

    // ------------------------------------------------------------ limits

    @Test
    fun maxListenersThen503AndPartyRaisesTheLimit() {
        val s = server(HostLimits(maxClients = 2, maxClientsParty = 3))
        val a = WsTestClient(s.port, "k=$key")
        val b = WsTestClient(s.port, "k=$key")
        val c = WsTestClient(s.port, "k=$key")
        try {
            assertEquals(101, a.status)
            assertEquals(101, b.status)
            assertEquals(503, c.status)
            s.setParty(true)
            WsTestClient(s.port, "k=$key").use { d -> assertEquals(101, d.status) }
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun pendingHandshakesAreLimited() {
        val s = server(HostLimits(maxPending = 3, headerDeadlineMs = 4_000))
        val idle = List(3) { Socket("127.0.0.1", s.port) } // connect, send nothing
        try {
            Thread.sleep(200)
            val r = httpGet(s.port, "/info?k=$key")
            assertEquals(503, r!!.status)
        } finally {
            idle.forEach { it.close() }
        }
        assertTrue(waitUntil { httpGet(s.port, "/info?k=$key")?.status == 200 })
    }

    @Test
    fun slowHeadersAreDroppedAfterTheDeadline() {
        val s = server(HostLimits(headerDeadlineMs = 400))
        Socket("127.0.0.1", s.port).use { sock ->
            sock.soTimeout = 3000
            sock.getOutputStream().write("GET /info?k=$key HTTP/1.1\r\n".toByteArray())
            val start = System.currentTimeMillis()
            // Keep trickling header bytes; the server must still give up at the deadline.
            val out = sock.getOutputStream()
            var closed = false
            try {
                while (System.currentTimeMillis() - start < 2500) {
                    out.write("X: y\r\n".toByteArray())
                    out.flush()
                    Thread.sleep(50)
                }
            } catch (_: java.io.IOException) {
                closed = true
            }
            if (!closed) closed = try { sock.getInputStream().read() < 0 } catch (_: java.io.IOException) { true }
            assertTrue(closed)
            assertTrue(System.currentTimeMillis() - start < 2500)
        }
    }

    @Test
    fun headerBlockOver8KbGets431() {
        val s = server()
        Socket("127.0.0.1", s.port).use { sock ->
            sock.soTimeout = 3000
            val sb = StringBuilder("GET /info?k=$key HTTP/1.1\r\n")
            while (sb.length < 9000) sb.append("X-Pad: ").append("a".repeat(100)).append("\r\n")
            sb.append("\r\n")
            try {
                sock.getOutputStream().write(sb.toString().toByteArray())
            } catch (_: java.io.IOException) {
            }
            val resp = parseResponse(try { sock.getInputStream().readBytes() } catch (_: java.io.IOException) { ByteArray(0) })
            assertEquals(431, resp?.status)
        }
    }

    @Test
    fun idleListenersAreClosed() {
        val s = server(HostLimits(idleTimeoutMs = 400, pingIntervalMs = 60_000))
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            assertEquals(WsCodec.CLOSE_GOING_AWAY, c.awaitClosed())
        }
        assertTrue(waitUntil { s.listeners().isEmpty() })
    }

    @Test
    fun slowListenerQueueDropsOldestAudio() {
        val s = server(HostLimits(queueMs = 50)) // 5 packets
        s.setSharing(48_000)
        WsTestClient(s.port, "k=$key").use { c ->
            c.readJson("hello")!!
            assertTrue(waitUntil { s.listeners().size == 1 })
            val pcm = ByteArray(1920)
            // Fill the socket buffers and the queue while the client reads nothing.
            repeat(3000) { s.sendAudio(pcm, 0, pcm.size) }
            assertTrue(waitUntil { s.listeners().firstOrNull()?.dropped?.let { it > 0 } ?: true })
        }
    }

    // ------------------------------------------------------------ guessing protection

    @Test
    fun distinctWrongKeysLockTheDeviceButOneStaleKeyDoesNot() {
        val s = server()
        repeat(15) { assertEquals(403, httpGet(s.port, "/info?k=OLDKEY22")!!.status) }
        assertEquals(200, httpGet(s.port, "/info?k=$key")!!.status)
        // The stale key counted once; 8 more distinct wrong keys make 9: still answered.
        for (i in 0 until 8) assertEquals(403, httpGet(s.port, "/info?k=WRONG${'A' + i}AA")!!.status)
        assertNull("10th distinct wrong key: ignored", httpGet(s.port, "/info?k=WRONGZAA"))
        assertNull("locked device is ignored even with the right key", httpGet(s.port, "/info?k=$key"))
    }
}
