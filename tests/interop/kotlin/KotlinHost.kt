// Interop harness: runs the Android host's pure-JVM server (HostServer + Packetizer, unchanged) with a 1 kHz tone
// and reports what it sees of its listeners (transport, needMs, syncErrMs) and whether P follows needMs.
package interop

import com.dualearbuds.host.audio.Packetizer
import com.dualearbuds.host.server.HostIdentity
import com.dualearbuds.host.server.HostServer
import com.dualearbuds.host.server.Playout

object KotlinHost {
    @JvmStatic
    fun main(argv: Array<String>) {
        val a = Args(argv)
        val r = Report("kotlin-host")
        val port = a.int("port", 8790)
        val seconds = a.dbl("seconds", 8.0)
        val server = HostServer(HostIdentity("Kotlin host", "3.2.0", "android"), a.str("key", "ABCD2345")!!, { "<html>player</html>".toByteArray() })
        a.str("pin")?.let { server.setPin(it) }
        server.setLocalNeed(0) // host ear needs nothing: P must follow the listeners
        server.start(port, 1)
        writeEpoch(a.str("epoch-file"), epochMs() - server.hostMs())
        server.setSharing(48_000)
        a.str("seq-start")?.let { start ->
            // start near the uint32 wrap so it happens mid-run (sequence numbers are unsigned on the wire)
            val f = HostServer::class.java.getDeclaredField("seq").apply { isAccessible = true }
            (f.get(server) as java.util.concurrent.atomic.AtomicInteger).set(start.toLong().toInt())
            r.info("sequence starts at $start")
        }
        r.info("listening on $port udp=${server.udpPort} pin=${server.pin != null}")

        // capture thread: 10 ms reads of a 1 kHz tone through the host's own Packetizer
        val tone = Tone()
        val chunk = ByteArray(1920)
        val pk = Packetizer(48_000) { packet, at -> server.sendAudio(packet, 0, packet.size, at) }
        val t0 = System.nanoTime()
        Thread({
            var n = 0L
            while (true) {
                tone.next10ms(chunk)
                pk.write(chunk, 0, chunk.size, server.hostMs())
                n++
                val wait = t0 / 1_000_000 + n * 10 - System.nanoTime() / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
        }, "tone").apply { isDaemon = true; start() }

        var sawUdp = false
        var sawWsAfterUdp = false
        var udpAt = -1L
        var needSeen: Int? = null
        var maxNeed = 0
        var errSeen: Int? = null
        var pFollowFail: String? = null
        var pAboveFail: String? = null
        var lastNeedChangeAt = 0L
        var lastNeed: Int? = null
        var pWithListener = -1
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        while (System.currentTimeMillis() < end) {
            Thread.sleep(100)
            val now = System.currentTimeMillis()
            val p = server.playoutMs
            for (l in server.listeners()) {
                if (l.client == "web") continue
                pWithListener = p
                if (l.transport == "udp" && !sawUdp) { sawUdp = true; udpAt = now; r.info("listener ${l.displayName} on UDP") }
                if (sawUdp && l.transport == "ws" && !sawWsAfterUdp) { sawWsAfterUdp = true; r.info("listener ${l.displayName} back on WebSocket after ${now - udpAt} ms of UDP") }
                val n = l.needMs
                if (n != null) {
                    if (needSeen == null) r.info("first needMs $n (P was $p)")
                    needSeen = n
                    if (n != lastNeed) { lastNeed = n; lastNeedChangeAt = now }
                    if (l.synced) maxNeed = maxOf(maxNeed, n)
                    // increases apply at once (one housekeeping tick); allow 1.5 s for status + tick
                    val want = n.coerceIn(Playout.MIN_MS, Playout.MAX_MS)
                    if (l.synced && now - lastNeedChangeAt > 1500 && p < want && pFollowFail == null) pFollowFail = "P=$p < need=$n"
                }
                l.syncErrMs?.let { errSeen = it }
            }
            if (maxNeed > 0 && p > maxOf(Playout.MIN_MS, maxNeed) && pAboveFail == null) pAboveFail = "P=$p above every need (max $maxNeed)"
        }
        val p = pWithListener
        r.info("end: P=$p (last seen with a native listener) need=$needSeen maxNeed=$maxNeed syncErrMs=$errSeen udpDropped=${server.udpDropped} keepalivesIgnored=${server.udpIgnored}")
        r.check(sawUdp, "native listener got UDP audio (host side)")
        if (a.flag("expect-fallback")) r.check(sawWsAfterUdp, "listener moved back to WebSocket audio (host side)")
        r.check(needSeen != null, "listener reported needMs", "last $needSeen")
        r.check(errSeen != null, "listener reported syncErrMs", "last $errSeen")
        r.check(needSeen != null && pFollowFail == null && pAboveFail == null && p != 120,
            "P follows needMs (local need 0)", "P=$p need=$needSeen ${pFollowFail ?: ""} ${pAboveFail ?: ""}".trim())
        server.stop()
        r.exit()
    }
}
