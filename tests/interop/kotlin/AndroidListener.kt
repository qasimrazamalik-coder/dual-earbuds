// Interop harness: the Android listener on a plain JVM.
//
// Runs UNCHANGED: net/StreamConnection (WebSocket session, message parsing), net/UdpAudio (UdpWire, SeqGate,
// UdpPolicy, UdpAudioReceiver), net/InfoClient, net/LinkParser (LinkTarget), audio/JitterBuffer + controllers,
// audio/PacketConcealer, audio/sync/* (ClockSync, DeadlineNeed, SyncPlanner, RateTrimmer, LatencySmoother).
// OkHttp, org.json and SystemClock are thin shims (tests/interop/kotlin/shims).
//
// REPRODUCED (they need Android services / AudioTrack): PlaybackService's session glue (runSession's /info and PIN
// handling, connectionCallbacks, deliver, the UDP listener + policy loop, the sync loop, sendStatus and its
// triggers) and AudioEngine (push/stats/clock calls verbatim; its audio loop with a simulated AudioTrack whose
// timestamp reports "written frames ahead + 20 ms"). Each reproduced function names its original.
package interop

import com.dualearbuds.companion.audio.AudioPacket
import com.dualearbuds.companion.audio.BufferMode
import com.dualearbuds.companion.audio.JitterBuffer
import com.dualearbuds.companion.audio.PacketConcealer
import com.dualearbuds.companion.audio.sync.ClockSync
import com.dualearbuds.companion.audio.sync.DeadlineNeed
import com.dualearbuds.companion.audio.sync.LatencySmoother
import com.dualearbuds.companion.audio.sync.RateTrimmer
import com.dualearbuds.companion.audio.sync.SyncAction
import com.dualearbuds.companion.audio.sync.SyncPlanner
import com.dualearbuds.companion.net.HostKind
import com.dualearbuds.companion.net.InfoClient
import com.dualearbuds.companion.net.InfoResult
import com.dualearbuds.companion.net.LinkTarget
import com.dualearbuds.companion.net.SeqGate
import com.dualearbuds.companion.net.StreamConnection
import com.dualearbuds.companion.net.UdpAudioReceiver
import com.dualearbuds.companion.net.UdpPolicy
import com.dualearbuds.companion.net.UdpSignal
import com.dualearbuds.companion.net.UdpWire
import com.dualearbuds.companion.net.WsEnd
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.Locale
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** AudioEngine (audio/AudioEngine.kt) without DSP, with a simulated AudioTrack. */
class SimEngine {
    @Volatile var calibratedLatencyMs: Double? = null
    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val jitter = JitterBuffer()
    private val clock = ClockSync()
    private val concealer = PacketConcealer()
    @Volatile private var delayMs = 0
    @Volatile private var trackRate = 0
    @Volatile private var syncedNow = false
    @Volatile var syncErrorMs = 0.0
    private val tsLatency = LatencySmoother()
    @Volatile private var tsLatencyMs = Double.NaN
    private val concealed = AtomicLong()
    private var lastPushSeq = -1L
    private var lastPushRate = 0
    private val run = AtomicBoolean(false)

    fun configure(mode: BufferMode, manualMs: Int, delayMs: Int) {
        this.delayMs = delayMs
        lock.withLock { jitter.mode = mode; jitter.manualMs = manualMs; jitter.delayMs = delayMs }
    }

    fun nowMs(): Double = System.nanoTime() / 1_000_000.0
    fun onSyncReply(c: Double, h: Double) = lock.withLock { clock.onSyncReply(c, h, nowMs()) }
    fun onClock(seq: Long, h: Double) = lock.withLock { clock.onClock(seq, h, nowMs()) }
    fun setPlayoutMs(ms: Double) = lock.withLock { clock.playoutMs = ms }
    fun resetClock() = lock.withLock { clock.reset() }
    /** Harness only: the host time this listener believes it is now (local + offset), or null. */
    fun hostNowEstimate(): Double? = lock.withLock { clock.offsetMs?.let { nowMs() + it } }
    val playoutMs: Double get() = lock.withLock { clock.playoutMs }

    fun start() {
        if (!run.compareAndSet(false, true)) return
        lock.withLock { jitter.reset(SystemClock.elapsedRealtime()); concealer.reset(); lastPushSeq = -1L }
        Thread({ loop() }, "sim-audio").apply { isDaemon = true; start() }
    }

    fun stop() { run.set(false) }

    /** AudioEngine.push, verbatim. */
    fun push(rate: Int, seq: Long, samples: ShortArray) {
        lock.withLock {
            val now = SystemClock.elapsedRealtime()
            val prev = lastPushSeq
            if (prev >= 0 && rate == lastPushRate) {
                val gap = ClockSync.seqDelta(seq, (prev + 1) and 0xFFFFFFFFL)
                if (gap in 1..MAX_FILL_PACKETS) {
                    val fill = concealer.conceal(gap.toInt(), rate)
                    fill.forEachIndexed { i, s -> jitter.push(AudioPacket(rate, s, (prev + 1 + i) and 0xFFFFFFFFL, concealed = true), now) }
                    concealed.addAndGet(fill.size.toLong())
                } else if (gap != 0L) {
                    concealer.reset()
                }
            }
            concealer.onReal(samples, rate)
            lastPushSeq = seq and 0xFFFFFFFFL
            lastPushRate = rate
            jitter.push(AudioPacket(rate, samples, seq and 0xFFFFFFFFL), now)
            dataAvailable.signalAll()
        }
    }

    fun flush() = lock.withLock { jitter.flush(); concealer.reset(); lastPushSeq = -1L }
    fun resetCounters() { lock.withLock { jitter.resetCounters() }; concealed.set(0) }

    class Stats(val targetMs: Int, val underruns: Long, val playing: Boolean, val synced: Boolean, val syncErrorMs: Double, val needMs: Int?)

    /** AudioEngine.stats (the fields sendStatus uses); output not Bluetooth. */
    fun stats(): Stats = lock.withLock {
        val hasTrack = trackRate > 0
        val out = DeadlineNeed.outputLatency(calibratedLatencyMs, if (hasTrack) tsLatencyMs.takeIf { !it.isNaN() } else null, false)
        val j30 = jitter.jitterP99Over30sMs
        Stats(jitter.targetMs(), jitter.underruns, !jitter.buffering && run.get(), syncedNow, syncErrorMs,
            if (j30.isNaN()) null else DeadlineNeed.needMs(j30, out.ms))
    }

    /** A simulated AudioTrack: 20 ms hardware latency after the written frames; plays in real time. */
    private class SimTrack(val rate: Int, val capFrames: Int = rate / 50) {
        var framesWritten = 0L
        private var played = 0.0
        private var at = System.nanoTime()
        fun advance() {
            val now = System.nanoTime()
            played = minOf(framesWritten.toDouble(), played + (now - at) / 1e9 * rate) // dry = silence, time goes on
            at = now
        }
        fun presentationMs(nowMs: Double): Double { advance(); return nowMs + (framesWritten - played) * 1000.0 / rate + HW_MS }
        /** Blocking write, like AudioTrack.write in blocking mode: waits for room, piece by piece. */
        fun write(n: Int) {
            var left = n
            while (left > 0) {
                advance()
                val room = (capFrames - (framesWritten - played)).toInt()
                if (room <= 0) { Thread.sleep(1); continue }
                val w = minOf(room, left)
                framesWritten += w
                left -= w
            }
        }
    }

    private fun loop() {
        var t: SimTrack? = null
        val planner = SyncPlanner()
        val trimmer = RateTrimmer()
        var trimmed = ShortArray(0)
        var wasSynced = false
        while (run.get()) {
            val pres = t?.let { it.presentationMs(nowMs()) }
            if (pres != null && t != null && t.framesWritten > 0) {
                tsLatency.add(pres - nowMs())
                tsLatencyMs = tsLatency.valueMs
            }
            val synced = t != null && lock.withLock { clock.isReady(nowMs()) } && pres != null
            if (synced != wasSynced) {
                lock.withLock { jitter.synced = synced }
                planner.reset(); trimmer.reset()
                wasSynced = synced
                syncedNow = synced
            }
            val packet = lock.withLock {
                val now = SystemClock.elapsedRealtime()
                val p = if (synced) jitter.pollSynced(now) else jitter.poll(now)
                if (p == null) dataAvailable.await(5, TimeUnit.MILLISECONDS)
                p
            } ?: continue
            if (t == null || packet.rate != t.rate) {
                t = SimTrack(packet.rate); trackRate = packet.rate; tsLatency.reset(); tsLatencyMs = Double.NaN
            }
            val track = t
            var n = packet.samples.size
            var ratio = 1.0
            if (synced && packet.seq >= 0) {
                val target = lock.withLock { clock.targetLocalMs(packet.seq, calibratedLatencyMs ?: 0.0, delayMs.toDouble()) }
                val heardAt = track.presentationMs(nowMs())
                if (target != null) {
                    val err = heardAt - target
                    syncErrorMs = err
                    if (abs(err) < 2_000.0) {
                        when (val action = planner.plan(err, track.rate)) {
                            is SyncAction.Skip -> { val s = action.frames * 2; if (s >= n) continue; n -= s }
                            is SyncAction.Insert -> track.write(minOf(action.frames, track.rate / 5))
                            is SyncAction.Play -> ratio = action.ratio
                        }
                    }
                }
            }
            var outN = n
            if (ratio != 1.0 || synced) {
                val need = trimmer.maxOutputSamples(n, ratio)
                if (trimmed.size < need) trimmed = ShortArray(need)
                outN = trimmer.process(packet.samples, n, ratio, trimmed)
            }
            // writeChunked: 10 ms pieces
            var left = outN / 2
            val chunk = track.rate / 100
            while (left > 0) { val w = minOf(chunk, left); track.write(w); left -= w }
        }
    }

    companion object {
        private const val MAX_FILL_PACKETS = 50L
        const val HW_MS = 20.0
    }
}

/** PlaybackService's session logic for one host, as far as the wire is concerned. */
class ListenerSim(private val target: LinkTarget, private val r: Report, private val dropRate: Double, private val blockAfterMs: Long) {
    val engine = SimEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()

    @Volatile var connection: StreamConnection? = null
    @Volatile private var mirroring = false
    @Volatile private var kickReason: String? = null
    @Volatile var pinRequired = false
    private var syncJob: Job? = null
    private var udp: UdpAudioReceiver? = null
    val udpPolicy = UdpPolicy()
    private val gate = SeqGate()
    private var udpJob: Job? = null
    @Volatile private var lastSeq = -1L
    private var sentNeedMs: Int? = null
    val packets = AtomicLong()
    val lost = AtomicLong()

    // harness counters
    val wsPackets = AtomicLong()
    val udpDatagrams = AtomicLong()
    val droppedDatagrams = AtomicLong()
    val badTone = AtomicLong()
    @Volatile var wrapped = false
    @Volatile var helloPc = ""
    @Volatile var helloHost = HostKind.UNKNOWN
    val statusSent = AtomicLong()
    @Volatile var lastStatus = ""
    @Volatile var hostPlayout = -1
    @Volatile var blocked = false
    private val droppedCur = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val rnd = Random(42)
    @Volatile private var dropThis = false
    @Volatile private var dropCur = -1L
    val udpEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** PlaybackService.runSession: one pass of /info + WebSocket (no reconnect loop: the harness runs one session). */
    suspend fun runOnce(maxMs: Long): Pair<String, WsEnd?> {
        val info = withContext(Dispatchers.IO) { InfoClient.fetch(http, target) }
        if (info is InfoResult.Ok) {
            mirroring = info.info.rate > 0
            pinRequired = info.info.pin
            if (info.info.pin && target.pin.isNullOrEmpty()) return "pinNeeded" to null
        } else {
            return "info:$info" to null
        }
        val conn = StreamConnection(http, target, "JVM listener", callbacks)
        connection = conn
        engine.resetClock()
        val end = withTimeoutOrNull(maxMs) { conn.runUntilClosed() }
        if (end == null) conn.close()
        syncJob?.cancel()
        stopUdp()
        connection = null
        if (kickReason != null) return "kicked" to end
        if (end != null && end.httpCode == 403) return (if (pinRequired) "wrongPin" else "wrongKey") to end
        return (if (end == null) "ranFull" else "ended") to end
    }

    /** PlaybackService.connectionCallbacks. */
    private val callbacks = object : StreamConnection.Callbacks {
        override fun onOpened() {
            lastSeq = -1L
            sentNeedMs = null
            gate.reset()
            engine.resetCounters()
            sendStatus()
            startSyncLoop()
        }
        override fun onHello(pc: String, rate: Int, host: HostKind, party: Boolean) { mirroring = rate > 0; helloPc = pc; helloHost = host }
        override fun onMirroring(mirroring: Boolean, rate: Int) { this@ListenerSim.mirroring = mirroring; if (!mirroring) engine.flush() }
        override fun onParty(on: Boolean) = Unit
        override fun onControlVolume(volume: Int) = sendStatus()
        override fun onHelloExtras(playoutMs: Int, remote: Boolean, source: String, app: String) { engine.setPlayoutMs(playoutMs.toDouble()); hostPlayout = playoutMs }
        override fun onPlayout(ms: Int) { engine.setPlayoutMs(ms.toDouble()); hostPlayout = ms }
        override fun onSyncReply(c: Double, h: Double) = engine.onSyncReply(c, h)
        override fun onClock(seq: Long, h: Double) = engine.onClock(seq, h)
        override fun onKick(reason: String) { kickReason = reason; connection?.close() }
        override fun onUdpOffer(port: Int, token: String) = startUdp(port, token)
        override fun onPacket(rate: Int, seq: Long, samples: ShortArray) { wsPackets.incrementAndGet(); deliver(rate, seq, samples) }
    }

    /** PlaybackService.deliver. */
    private fun deliver(rate: Int, seq: Long, samples: ShortArray) {
        if (rate !in 8000..192000) return
        gate.offer(seq) {
            val prev = lastSeq
            if (prev >= 0) {
                val expected = (prev + 1) and 0xFFFFFFFFL
                if (seq != expected) {
                    val gap = (seq - expected) and 0xFFFFFFFFL
                    if (gap in 1L..5000L) lost.addAndGet(gap)
                }
            }
            if (prev > 0xFFFFFF00L && seq < 0x100L) wrapped = true
            lastSeq = seq
            packets.incrementAndGet()
            mirroring = true
            if (rate == 48_000 && zeroCrossings(samples) !in 19..21) badTone.incrementAndGet()
            engine.push(rate, seq, samples)
        }
    }

    /** PlaybackService.udpListener, with the harness's simulated Wi-Fi loss in front of it. */
    private val udpListener = object : UdpAudioReceiver.Listener {
        override fun onDatagram(nowMs: Long) {
            if (dropCur >= 0) { droppedCur.add(dropCur); dropCur = -1L }
            dropThis = blocked || rnd.nextDouble() < dropRate
            if (dropThis) { droppedDatagrams.incrementAndGet(); return }
            udpDatagrams.incrementAndGet()
            synchronized(udpPolicy) { udpPolicy.onDatagram(SystemClock.elapsedRealtime()) }
            connection?.touch()
        }
        override fun onBlock(block: UdpWire.Block) {
            if (dropThis) { dropCur = maxOf(dropCur, block.seq); return } // blocks come oldest first: the last is cur
            deliver(block.rate, block.seq, block.samples)
        }
        override fun onError(reason: String) { r.info("UDP unusable: $reason") }
    }

    /** Seqs whose datagram was lost and whose redundant copy (in the next datagram) was lost too. */
    fun unrecoverable(): Int {
        // a dropped datagram carries cur=s and prev=s-1; s is lost for good when the datagram of s+1 was dropped too
        val curs = droppedCur.toSet() + (if (dropCur >= 0) setOf(dropCur) else emptySet())
        return curs.count { (it + 1) in curs }
    }

    /** PlaybackService.startUdp. */
    private fun startUdp(port: Int, tokenText: String) {
        val token = UdpWire.parseToken(tokenText)
        if (token == null) { r.info("UDP offer ignored (token unreadable): $tokenText"); return }
        r.info("hello offers UDP port $port token $tokenText")
        udp?.close()
        val rx = UdpAudioReceiver(host = target.host, port = port, token = token, listener = udpListener)
        udp = rx
        synchronized(udpPolicy) { udpPolicy.onHello(SystemClock.elapsedRealtime()) }
        rx.start()
        udpEvents += "offer@${SystemClock.elapsedRealtime()}"
        udpJob?.cancel()
        udpJob = scope.launch {
            while (isActive) {
                delay(250)
                val conn = connection ?: break
                if (!conn.isOpen) break
                when (synchronized(udpPolicy) { udpPolicy.tick(SystemClock.elapsedRealtime(), mirroring) }) {
                    UdpSignal.SEND_OK_FALSE -> { conn.send("{\"type\":\"udp\",\"ok\":false}"); udpEvents += "okFalse@${SystemClock.elapsedRealtime()}" }
                    UdpSignal.SEND_OK_TRUE -> { conn.send("{\"type\":\"udp\",\"ok\":true}"); udpEvents += "okTrue@${SystemClock.elapsedRealtime()}" }
                    UdpSignal.NONE -> Unit
                }
            }
        }
    }

    private fun stopUdp() {
        udpJob?.cancel(); udpJob = null
        udp?.close(); udp = null
        synchronized(udpPolicy) { udpPolicy.reset() }
    }

    /** PlaybackService.startSyncLoop. */
    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            var n = 0
            while (isActive) {
                val conn = connection ?: break
                if (!conn.isOpen) break
                conn.send("{\"type\":\"sync\",\"c\":" + String.format(Locale.US, "%.3f", engine.nowMs()) + "}")
                n++
                delay(if (n < 5) 250L else 2_000L)
            }
        }
    }

    /** PlaybackService.startObservers: status every 5 s, on changes, and when needMs moves (checked twice a second). */
    fun startObservers() {
        scope.launch { while (isActive) { delay(5000); sendStatus() } }
        scope.launch {
            var key: Pair<Int, Boolean>? = null
            while (isActive) {
                delay(500)
                val s = engine.stats()
                val k = s.targetMs to (s.playing && mirroring)
                if (k != key) { key = k; sendStatus() }
                val need = s.needMs
                if (connection?.isOpen == true && need != null && DeadlineNeed.worthReporting(sentNeedMs, need)) sendStatus()
            }
        }
    }

    /** PlaybackService.sendStatus (no earbuds connected, volume 100). */
    fun sendStatus() {
        val conn = connection ?: return
        if (!conn.isOpen) return
        val s = engine.stats()
        val json = JSONObject()
            .put("type", "status")
            .put("device", "JVM listener")
            .put("earbuds", "")
            .put("battery", -1)
            .put("volume", 100)
            .put("bufferMs", s.targetMs)
            .put("underruns", s.underruns)
            .put("playing", s.playing && mirroring)
        val need = s.needMs
        if (need != null) json.put("needMs", need)
        if (s.synced) json.put("syncErrMs", Math.round(s.syncErrorMs).toInt())
        val text = json.toString()
        if (conn.send(text)) {
            statusSent.incrementAndGet(); lastStatus = text
            if (need != null) sentNeedMs = need
        }
    }
}

object AndroidListener {
    @JvmStatic
    fun main(argv: Array<String>) {
        val a = Args(argv)
        val r = Report("android-listener")
        val host = a.str("host", "127.0.0.1")!!
        val port = a.int("port", 8790)
        val key = a.str("key", "ABCD2345")!!
        val pin = a.str("pin")
        val seconds = a.dbl("seconds", 6.0)
        val loss = a.dbl("loss", 0.0)
        val blockAt = a.int("block-at-ms", -1).toLong()
        val epoch = readEpoch(a.str("epoch-file"))

        runBlocking {
            if (pin != null) {
                val badKey = ListenerSim(LinkTarget(host, port, if (key == "ZZZZ2345") "YYYY2345" else "ZZZZ2345", pin), r, 0.0, -1)
                val (res0, _) = badKey.runOnce(4000)
                r.check(res0.contains("WrongKey"), "wrong link key is rejected", res0)
                val wrong = ListenerSim(LinkTarget(host, port, key, if (pin == "1111") "2222" else "1111"), r, 0.0, -1)
                val (res, _) = wrong.runOnce(4000)
                r.check(res == "wrongPin", "wrong PIN is rejected", res)
                val none = ListenerSim(LinkTarget(host, port, key, null), r, 0.0, -1)
                val (res2, _) = none.runOnce(4000)
                r.check(res2 == "pinNeeded", "missing PIN is detected from /info", res2)
            }
            val sim = ListenerSim(LinkTarget(host, port, key, pin), r, loss, blockAt)
            sim.engine.calibratedLatencyMs = 150.0 // "calibrated earbuds": needMs ≈ jitter + 150 + track + 10
            sim.engine.configure(BufferMode.SMART, 180, 0)
            sim.engine.start()
            sim.startObservers()
            val t0 = SystemClock.elapsedRealtime()
            var udpAt = -1L
            var switched = false
            var wsP0 = 0L
            var lost0 = 0L
            var blockedAt = -1L
            var fallbackMs = -1L
            var pktAtFallback = 0L
            var maxErr = 0.0
            var syncErrSamples = 0
            val errs = ArrayList<Double>()
            var clockErr = Double.NaN
            val job = launch(Dispatchers.Default) { sim.runOnce((seconds * 1000).toLong()) }
            while (job.isActive) {
                delay(50)
                val now = SystemClock.elapsedRealtime()
                val usingUdp = synchronized(sim.udpPolicy) { sim.udpPolicy.usingUdp }
                if (usingUdp && udpAt < 0) { udpAt = now; wsP0 = sim.wsPackets.get(); r.info("UDP active after ${now - t0} ms") }
                // the switch is over 300 ms later (WebSocket packets still in flight have arrived)
                if (udpAt >= 0 && !switched && now >= udpAt + 300) { switched = true; wsP0 = sim.wsPackets.get(); lost0 = sim.lost.get() }
                if (blockAt >= 0 && udpAt >= 0 && blockedAt < 0 && now - udpAt >= blockAt) {
                    sim.blocked = true; blockedAt = now
                    r.info("blocking UDP (all datagrams dropped) at ${now - t0} ms")
                }
                if (blockedAt >= 0 && fallbackMs < 0 && !usingUdp && sim.wsPackets.get() > wsP0 + 5) {
                    fallbackMs = now - blockedAt; pktAtFallback = sim.packets.get()
                    r.info("WebSocket audio again ${fallbackMs} ms after the block")
                }
                val st = sim.engine.stats()
                if (System.getenv("INTEROP_TRACE") != null && (now - t0) % 250 < 50) r.info("t=${now - t0} P=${sim.hostPlayout} err=${"%.1f".format(st.syncErrorMs)} need=${st.needMs} synced=${st.synced} target=${st.targetMs}")
                // (the outage and the second after the fallback are a re-buffering, not clock sync)
                val settling = blockedAt >= 0 && (fallbackMs < 0 || now < blockedAt + fallbackMs + 1500)
                if (st.synced && now - t0 > seconds * 1000 - 2000 && !settling) { syncErrSamples++; maxErr = max(maxErr, abs(st.syncErrorMs)); errs += abs(st.syncErrorMs) }
                if (epoch != null) sim.engine.hostNowEstimate()?.let { clockErr = (epochMs() - epoch) - it }
            }
            val st = sim.engine.stats()
            r.info("last status: ${sim.lastStatus}")
            r.info("packets=${sim.packets} ws=${sim.wsPackets} udpDatagrams=${sim.udpDatagrams} dropped=${sim.droppedDatagrams} lost=${sim.lost} P=${sim.hostPlayout} need=${st.needMs} events=${sim.udpEvents}")
            val expectHost = when (a.str("expect-host")) { "android" -> HostKind.PHONE; "windows" -> HostKind.LAPTOP; else -> null }
            r.check(sim.helloPc.isNotEmpty() && (expectHost == null || sim.helloHost == expectHost), "hello parsed (pc, host kind, playoutMs)",
                "${sim.helloPc} / ${sim.helloHost} / P ${sim.hostPlayout}")
            val outage = if (fallbackMs > 0) fallbackMs / 1000.0 else 0.0 // packets sent while UDP was blocked are gone
            r.check(sim.packets.get() > (seconds - outage) * 100 * 0.7, "audio flows", "${sim.packets} packets in ${seconds}s" + if (outage > 0) " (${"%.1f".format(outage)} s blocked)" else "")
            r.check(sim.badTone.get() == 0L, "PCM is the 1 kHz tone (byte order, layout)", "${sim.badTone} odd packets")
            r.check(udpAt >= 0, "UDP negotiated (hello udp+token, keepalive)")
            val udpEnd = if (blockedAt >= 0) blockedAt else SystemClock.elapsedRealtime()
            if (udpAt >= 0 && blockAt < 0) {
                val wsDuringUdp = sim.wsPackets.get() - wsP0
                r.check(wsDuringUdp <= 2, "no WebSocket binary while UDP works", "$wsDuringUdp WS packets in ${udpEnd - udpAt - 300} ms of UDP")
            }
            if (loss == 0.0 && blockAt < 0) {
                r.check(sim.lost.get() - lost0 == 0L, "sequence continuous over UDP", "lost ${sim.lost.get() - lost0}")
                // UDP can overtake WebSocket packets still in flight when audio moves over: those arrive late (dropped)
                r.check(lost0 <= 10, "at most 100 ms concealed at the WebSocket -> UDP switch", "$lost0 packets")
            }
            if (a.flag("expect-wrap")) r.check(sim.wrapped, "uint32 sequence wrap crossed without a gap", "lost ${sim.lost.get() - lost0} after the switch")
            if (loss > 0) {
                val dropped = sim.droppedDatagrams.get()
                val expected = sim.unrecoverable()
                val residual = sim.lost.get() - lost0
                r.info("loss: ${dropped} of ${dropped + sim.udpDatagrams.get()} datagrams dropped, residual gaps $residual, double losses $expected")
                r.check(dropped > 20, "loss was injected", "$dropped datagrams")
                r.check(residual <= expected + 1, "redundancy hides single losses", "residual $residual, unrecoverable double losses $expected, raw drops $dropped")
            }
            errs.sort()
            val median = if (errs.isEmpty()) Double.NaN else errs[errs.size / 2]
            // (a P increase below the 40 ms snap is trimmed away at 0.5 %, i.e. 5 ms per second: allow for that)
            r.check(syncErrSamples > 0 && median < 25.0, "plays towards the common deadline (median |syncErr| < 25 ms, last 2 s)",
                "median ${"%.1f".format(median)} ms, max ${"%.1f".format(maxErr)} ms over $syncErrSamples samples")
            if (epoch != null) r.check(!clockErr.isNaN() && abs(clockErr) < 3.0, "host clock estimate matches the host", "off by ${"%.2f".format(clockErr)} ms")
            r.check(st.needMs != null && sim.lastStatus.contains("\"needMs\"") && sim.lastStatus.contains("\"syncErrMs\""), "status carries needMs + syncErrMs", sim.lastStatus)
            val need = st.needMs ?: 0
            r.check(sim.hostPlayout >= minOf(400, need) - 15 && sim.hostPlayout > 120, "host P follows our needMs", "P=${sim.hostPlayout} need=$need")
            if (blockAt >= 0) {
                r.check(fallbackMs in 0..2600, "WebSocket fallback within ~2 s of losing UDP", "$fallbackMs ms")
                r.check(sim.packets.get() > pktAtFallback + 50, "audio continues after the fallback", "${sim.packets.get() - pktAtFallback} packets after")
            }
        }
        r.exit()
    }
}

/** A browser-like listener: plain WebSocket with client=web; checks hello has no UDP offer and audio is binary. */
object WebProbe {
    @JvmStatic
    fun main(argv: Array<String>) {
        val a = Args(argv)
        val r = Report("web-probe")
        val port = a.int("port", 8790)
        val q = "k=" + a.str("key", "ABCD2345") + "&client=web&name=Browser" + (a.str("pin")?.let { "&p=$it" } ?: "")
        val sock = java.net.Socket("127.0.0.1", port)
        sock.soTimeout = 3000
        val out = sock.getOutputStream()
        out.write(("GET /ws?$q HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
        val inp = java.io.DataInputStream(java.io.BufferedInputStream(sock.getInputStream()))
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(inp.readUnsignedByte().toChar())
        r.check(head.startsWith("HTTP/1.1 101"), "web client upgraded", head.lineSequence().first())
        var hello = ""
        var binary = 0
        var gaps = 0L
        var last = -1L
        var bad = 0
        val end = System.currentTimeMillis() + (a.dbl("seconds", 4.0) * 1000).toLong()
        try {
            while (System.currentTimeMillis() < end) {
                val b0 = inp.readUnsignedByte()
                var len = (inp.readUnsignedByte() and 0x7F).toLong()
                if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
                val p = ByteArray(len.toInt())
                inp.readFully(p)
                when (b0 and 0x0F) {
                    1 -> {
                        val t = String(p)
                        if (t.contains("\"hello\"")) hello = t
                        if (t.contains("\"ping\"")) { /* a browser answers pings; not needed for 4 s */ }
                    }
                    2 -> {
                        binary++
                        val bb = java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val rate = bb.getInt(0)
                        val seq = bb.getInt(4).toLong() and 0xFFFFFFFFL
                        if (last >= 0 && seq != last + 1) gaps += seq - last - 1
                        last = seq
                        val s = ShortArray((p.size - 8) / 2)
                        bb.position(8); bb.asShortBuffer().get(s)
                        if (rate != 48_000 || zeroCrossings(s) !in 19..21) bad++
                    }
                }
            }
        } catch (e: java.io.IOException) {
            r.info("read ended: $e")
        }
        sock.close()
        r.check(hello.isNotEmpty() && !hello.contains("\"udp\"") && !hello.contains("\"token\""), "browser hello offers no UDP", hello)
        r.check(binary > 250, "browser path gets binary audio over the WebSocket", "$binary packets")
        r.check(bad == 0 && gaps == 0L, "browser audio is the tone, in sequence", "bad=$bad gaps=$gaps")
        r.exit()
    }
}
