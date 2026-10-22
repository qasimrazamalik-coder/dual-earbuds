package com.dualearbuds.host.server

import org.junit.After
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
import java.net.SocketTimeoutException

/** v3.2 UDP audio over real localhost sockets: token keepalives, redundancy, fallback, limits. */
class UdpAudioTest {
    private val key = "ABCD2345"
    private val servers = ArrayList<HostServer>()
    private val sockets = ArrayList<DatagramSocket>()
    private val pcmLen = AudioPacket.pcmBytes(48_000)

    private fun server(limits: HostLimits = HostLimits()): HostServer {
        val s = HostServer(HostIdentity(pcName = "Test Phone"), key, { null }, limits)
        s.start(0)
        servers += s
        s.setSharing(48_000)
        return s
    }

    private fun udpClient(): DatagramSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also {
        it.soTimeout = 300
        sockets += it
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        sockets.forEach { it.close() }
    }

    private fun pcm(seq: Int) = ByteArray(pcmLen) { (seq * 7 + it).toByte() }

    private fun keepalive(sock: DatagramSocket, port: Int, token: ByteArray) {
        val k = UdpPacket.keepalive(token)
        sock.send(DatagramPacket(k, k.size, InetAddress.getLoopbackAddress(), port))
    }

    private fun receive(sock: DatagramSocket): List<UdpPacket.Block>? {
        val buf = ByteArray(8192)
        val p = DatagramPacket(buf, buf.size)
        return try {
            sock.receive(p)
            UdpPacket.decodeAudio(buf, p.length, pcmLen)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** Connects a native listener, learns its token and starts UDP. Returns the token. */
    private fun joinUdp(s: HostServer, c: WsTestClient, sock: DatagramSocket): ByteArray {
        val hello = c.readJson("hello")!!
        assertEquals(s.port.toDouble(), hello["udp"])
        val token = UdpPacket.parseTokenHex(hello["token"] as String)!!
        keepalive(sock, s.port, token)
        assertTrue(waitUntil { s.listeners().firstOrNull()?.transport == "udp" })
        return token
    }

    @Test
    fun codecRoundTrip() {
        val t = UdpPacket.newToken()
        assertEquals(32, UdpPacket.tokenHex(t).length)
        assertArrayEquals(t, UdpPacket.parseTokenHex(UdpPacket.tokenHex(t)))
        assertNull(UdpPacket.parseTokenHex("zz"))
        val k = UdpPacket.keepalive(t)
        assertEquals(21, k.size)
        assertTrue(UdpPacket.isKeepalive(k, k.size))
        assertFalse(UdpPacket.isKeepalive(k, 20))
        val dg = ByteArray(UdpPacket.audioSize(pcmLen, 2))
        UdpPacket.writeMagic(dg)
        val next = UdpPacket.writeBlock(dg, 4, -1, 48_000, pcm(9), 0, pcmLen) // seq 0xFFFFFFFF
        UdpPacket.writeBlock(dg, next, -2, 48_000, pcm(8), 0, pcmLen)
        assertEquals(3_860, dg.size) // ≈3.9 KB as the protocol says
        val blocks = UdpPacket.decodeAudio(dg, dg.size, pcmLen)!!
        assertEquals(0xFFFFFFFFL, blocks[0].seq)
        assertEquals(0xFFFFFFFEL, blocks[1].seq)
        assertArrayEquals(pcm(8), blocks[1].pcm)
        assertNull(UdpPacket.decodeAudio(dg, dg.size - 1, pcmLen))
    }

    @Test
    fun helloOffersUdpOnlyToNativeListeners() {
        val s = server()
        assertEquals(s.port, s.udpPort)
        WsTestClient(s.port, "k=$key&client=web").use { c ->
            val hello = c.readJson("hello")!!
            assertFalse(hello.containsKey("udp"))
            assertFalse(hello.containsKey("token"))
        }
        WsTestClient(s.port, "k=$key&client=windows").use { c ->
            val hello = c.readJson("hello")!!
            assertEquals(s.port.toDouble(), hello["udp"])
            assertNotNull(UdpPacket.parseTokenHex(hello["token"] as String))
        }
    }

    @Test
    fun audioGoesOverUdpWithRedundancyAndNotOverTheWebSocket() {
        val s = server()
        val sock = udpClient()
        WsTestClient(s.port, "k=$key&client=android", timeoutMs = 400).use { c ->
            joinUdp(s, c, sock)
            for (i in 0 until 10) s.sendAudio(pcm(i), 0, pcmLen)
            val got = ArrayList<List<UdpPacket.Block>>()
            while (got.size < 10) got += receive(sock) ?: break
            assertEquals(10, got.size)
            assertEquals(1, got[0].size) // the first packet has no previous one
            for (i in 1 until 10) {
                assertEquals(i.toLong(), got[i][0].seq)
                assertEquals((i - 1).toLong(), got[i][1].seq)
                assertArrayEquals(pcm(i - 1), got[i][1].pcm)
                assertEquals(48_000, got[i][0].rate)
            }
            // No binary audio on the WebSocket while UDP works.
            var binary = 0
            while (true) {
                val f = c.readFrame() ?: break
                if (f.opcode == WsCodec.OP_BINARY) binary++
            }
            assertEquals(0, binary)
        }
    }

    @Test
    fun singleLossesAreRecoveredFromTheRedundantCopy() {
        val s = server()
        val sock = udpClient()
        WsTestClient(s.port, "k=$key&client=android").use { c ->
            joinUdp(s, c, sock)
            val n = 60
            // Feed in real time so the receive buffer never overflows.
            Thread {
                for (i in 0 until n) {
                    s.sendAudio(pcm(i), 0, pcmLen)
                    Thread.sleep(2)
                }
            }.start()
            val heard = HashMap<Long, ByteArray>()
            var index = 0
            while (index < n) {
                val blocks = receive(sock) ?: break
                val lost = index % 3 == 1 || index == 40 || index == 41 // every third, plus a burst of two
                index++
                if (lost) continue
                for (b in blocks) heard.putIfAbsent(b.seq, b.pcm)
            }
            assertEquals(n, index)
            val missing = (0 until n).filter { !heard.containsKey(it.toLong()) }
            assertEquals("only the first of two lost-in-a-row datagrams is gone", listOf(40), missing)
            for ((seq, data) in heard) assertArrayEquals(pcm(seq.toInt()), data)
        }
    }

    @Test
    fun unknownTokensAreDroppedSilently() {
        val s = server()
        val sock = udpClient()
        WsTestClient(s.port, "k=$key&client=android").use { c ->
            c.readJson("hello")
            keepalive(sock, s.port, UdpPacket.newToken())
            assertTrue(waitUntil { s.udpIgnored >= 1 })
            s.sendAudio(pcm(0), 0, pcmLen)
            assertNull(receive(sock))
            assertEquals("ws", s.listeners().first().transport)
        }
    }

    @Test
    fun keepalivesAreRateLimitedPerIp() {
        val s = server()
        val sock = udpClient()
        WsTestClient(s.port, "k=$key&client=android").use { c ->
            val token = joinUdp(s, c, sock)
            repeat(40) { keepalive(sock, s.port, token) }
            assertTrue(waitUntil { s.udpIgnored >= 25 })
            assertEquals("udp", s.listeners().first().transport) // the listener itself keeps working
        }
    }

    @Test
    fun listenerCanRefuseUdpAndSwitchBack() {
        val s = server()
        val sock = udpClient()
        WsTestClient(s.port, "k=$key&client=android").use { c ->
            joinUdp(s, c, sock)
            c.sendText("""{"type":"udp","ok":false}""")
            assertTrue(waitUntil { s.listeners().first().transport == "ws" })
            s.sendAudio(pcm(1), 0, pcmLen)
            var f = c.readFrame()
            while (f != null && f.opcode != WsCodec.OP_BINARY) f = c.readFrame()
            assertNotNull("audio comes over the WebSocket again", f)
            c.sendText("""{"type":"udp","ok":true}""")
            assertTrue(waitUntil { s.listeners().first().transport == "udp" })
            s.sendAudio(pcm(2), 0, pcmLen)
            assertArrayEquals(pcm(2), receive(sock)!![0].pcm) // (the server numbers packets itself)
        }
    }

    @Test
    fun withoutKeepalivesUdpExpiresAndAudioFallsBackToTheWebSocket() {
        val s = server(HostLimits(udpExpireMs = 400))
        val sock = udpClient()
        WsTestClient(s.port, "k=$key&client=android").use { c ->
            joinUdp(s, c, sock)
            assertTrue(waitUntil(3000) { s.listeners().first().transport == "ws" })
            s.sendAudio(pcm(5), 0, pcmLen)
            var f = c.readFrame()
            while (f != null && f.opcode != WsCodec.OP_BINARY) f = c.readFrame()
            assertNotNull(f)
            assertArrayEquals(pcm(5), f!!.payload.copyOfRange(AudioPacket.HEADER_SIZE, f.payload.size))
        }
    }

    @Test
    fun udpCanBeTurnedOff() {
        val s = server(HostLimits(udp = false))
        assertEquals(0, s.udpPort)
        WsTestClient(s.port, "k=$key&client=android").use { c ->
            assertFalse(c.readJson("hello")!!.containsKey("udp"))
        }
    }
}
