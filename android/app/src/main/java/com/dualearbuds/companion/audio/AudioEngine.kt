package com.dualearbuds.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.dualearbuds.companion.audio.sync.ClockSync
import com.dualearbuds.companion.audio.sync.DeadlineNeed
import com.dualearbuds.companion.audio.sync.LatencySmoother
import com.dualearbuds.companion.audio.sync.OutputLatency
import com.dualearbuds.companion.audio.sync.RateTrimmer
import com.dualearbuds.companion.audio.sync.SyncAction
import com.dualearbuds.companion.audio.sync.SyncPlanner
import com.dualearbuds.companion.diag.AppLog
import com.dualearbuds.companion.dsp.DialogueMode
import com.dualearbuds.companion.dsp.EqPreset
import com.dualearbuds.companion.dsp.EqSpec
import com.dualearbuds.companion.dsp.SoundClass
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class EngineStats(
    /** Sound waiting in the jitter queue. */
    val queuedMs: Int,
    /** The buffer the queue fills to (smart/steady/manual, plus delay). */
    val targetMs: Int,
    /** The AudioTrack's own device buffer. */
    val trackMs: Int,
    val underruns: Long,
    val dropped: Long,
    val concealed: Long,
    val rate: Int,
    val playing: Boolean,
    /** True while packets are scheduled by the host clock (v3.1 clock sync). */
    val synced: Boolean,
    val syncErrorMs: Double,
    val offsetMs: Double?,
    val rttMs: Double?,
    val jitterP99Ms: Double,
    val soundClass: SoundClass?,
    /** v3.2: p99 arrival jitter over the last 30 s (NaN before enough packets). */
    val jitter30sMs: Double = Double.NaN,
    /** v3.2: output latency used for needMs, and where it came from. */
    val outputLatency: OutputLatency? = null,
    /** v3.2: what this listener needs the host's playout P to be, or null before enough data. */
    val needMs: Int? = null,
    /** v3.2: the host's current playout P. */
    val playoutMs: Double = 0.0
)

/**
 * Jitter buffer + lowest-latency AudioTrack player on its own urgent-audio thread.
 *
 * - Packets arrive via [push] (network thread). Sequence gaps are filled by [PacketConcealer] (≤ 30 ms fade).
 * - Without a host clock the audio thread fills to the adaptive/smart/manual target and plays in order.
 * - With a host clock (v3.1 `sync`/`clock`, see [ClockSync]) every packet is scheduled to be heard at
 *   h(seq) + playoutMs − outputLatency − delay. [AudioTrack.getTimestamp] tells when the next written frame
 *   will play; [SyncPlanner] trims the rate by ≤ ±0.5 % or skips/inserts beyond ±40 ms.
 * - Sound goes through [DspChain] (dialogue boost, EQ, hearing profile, limiter, volume). A preset EQ runs on
 *   the platform `audiofx.Equalizer` when available ([PlatformEq]), else in software.
 * - The track uses PERFORMANCE_MODE_LOW_LATENCY and the smallest safe buffer; it is recreated on a rate change.
 */
class AudioEngine {
    @Volatile
    var gain: Float = 1f

    @Volatile
    var muted: Boolean = false

    /**
     * Calibrated latency of the current earbuds (ms) — the part the output timestamps don't report — or null when
     * not calibrated. Synced playback plays that much earlier; needMs adds it to the timestamp latency.
     */
    @Volatile
    var calibratedLatencyMs: Double? = null

    /** Fallback for "is the output Bluetooth" when the track can't say (earbuds connected per EarbudsMonitor). */
    @Volatile
    var assumeBluetooth: Boolean = false

    /** Peak level 0..1 of what was just played (after gain), with a short decay. For the UI meter only. */
    @Volatile
    var level: Float = 0f
        private set

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val jitter = JitterBuffer()
    private val clock = ClockSync()
    private val concealer = PacketConcealer()
    private val chain = DspChain()

    @Volatile
    private var delayMs = 0

    @Volatile
    private var eqPreset: EqPreset = EqPreset.FLAT

    @Volatile
    private var hearing: Pair<List<Double>?, List<Double>?> = null to null

    @Volatile
    private var trackRate = 0

    @Volatile
    private var trackMs = 0

    @Volatile
    private var syncedNow = false

    @Volatile
    private var syncErrorMs = 0.0

    /** Smoothed "written frame → played" latency from AudioTrack.getTimestamp (audio thread writes it). */
    private val tsLatency = LatencySmoother()

    @Volatile
    private var tsLatencyMs = Double.NaN

    /** The track's routed output is Bluetooth (true/false), or null when unknown. */
    @Volatile
    private var routedBluetooth: Boolean? = null

    private val concealed = AtomicLong()
    private var lastPushSeq = -1L
    private var lastPushRate = 0

    @Volatile
    private var runFlag: AtomicBoolean? = null

    /** Applies the buffer settings (safe from any thread, takes effect on the next packet). */
    fun configure(mode: BufferMode, manualMs: Int, delayMs: Int) {
        this.delayMs = delayMs
        lock.withLock {
            jitter.mode = mode
            jitter.manualMs = manualMs
            jitter.delayMs = delayMs
        }
    }

    /** Sound settings; any thread. [preset] is the resolved EQ preset (manual / smart / host / flat). */
    fun setSound(preset: EqPreset, hearingLeft: List<Double>?, hearingRight: List<Double>?, dialogue: DialogueMode, classify: Boolean) {
        eqPreset = preset
        hearing = hearingLeft to hearingRight
        chain.dialogue = dialogue
        chain.classify = classify
    }

    // ------------------------------------------------------------------ clock sync (network thread)

    fun nowMs(): Double = System.nanoTime() / 1_000_000.0

    fun onSyncReply(c: Double, h: Double) = lock.withLock { clock.onSyncReply(c, h, nowMs()) }

    fun onClock(seq: Long, h: Double) = lock.withLock { clock.onClock(seq, h, nowMs()) }

    fun setPlayoutMs(ms: Double) = lock.withLock { clock.playoutMs = ms }

    /** New connection: forget the old host clock (offset and reference). */
    fun resetClock() = lock.withLock { clock.reset() }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the audio thread if it is not already running; the adaptive buffer starts again at 60 ms. */
    @Synchronized
    fun start() {
        if (runFlag?.get() == true) return
        val flag = AtomicBoolean(true)
        runFlag = flag
        lock.withLock {
            jitter.reset(SystemClock.elapsedRealtime())
            concealer.reset()
            lastPushSeq = -1L
        }
        concealed.set(0)
        level = 0f
        val t = Thread({ loop(flag) }, "DualEarbuds-Audio")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        t.start()
    }

    /** Stops the audio thread without blocking the caller; the thread releases its AudioTrack itself. */
    @Synchronized
    fun stop() {
        runFlag?.set(false)
        runFlag = null
        lock.withLock {
            jitter.flush()
            dataAvailable.signalAll()
        }
        level = 0f
    }

    /** A packet from the host. Fills sequence gaps (≤ 30 ms) with concealment first. */
    fun push(rate: Int, seq: Long, samples: ShortArray) {
        lock.withLock {
            val now = SystemClock.elapsedRealtime()
            val prev = lastPushSeq
            if (prev >= 0 && rate == lastPushRate) {
                val gap = ClockSync.seqDelta(seq, (prev + 1) and 0xFFFFFFFFL)
                if (gap in 1..1000) {
                    val fill = concealer.conceal(gap.toInt(), rate)
                    fill.forEachIndexed { i, s ->
                        jitter.push(AudioPacket(rate, s, (prev + 1 + i) and 0xFFFFFFFFL, concealed = true), now)
                    }
                    concealed.addAndGet(fill.size.toLong())
                } else if (gap != 0L) {
                    // The stream jumped (host restarted capture, or a gap too long to fill): start fresh.
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

    /** Clears queued audio (host stopped sharing or link dropped) and re-enters buffering. Not an underrun. */
    fun flush() {
        lock.withLock {
            jitter.flush()
            concealer.reset()
            lastPushSeq = -1L
        }
        level = 0f
    }

    /** Zeroes the counters (protocol: `underruns` counts since connect). Keeps the adaptive value. */
    fun resetCounters() {
        lock.withLock { jitter.resetCounters() }
        concealed.set(0)
    }

    fun stats(): EngineStats = lock.withLock {
        val hasTrack = trackRate > 0
        val out = DeadlineNeed.outputLatency(
            calibratedLatencyMs,
            if (hasTrack) tsLatencyMs.takeIf { !it.isNaN() } else null,
            routedBluetooth ?: assumeBluetooth
        )
        val j30 = jitter.jitterP99Over30sMs
        EngineStats(
            queuedMs = max(0, jitter.queuedMs.roundToInt()),
            targetMs = jitter.targetMs(),
            trackMs = trackMs,
            underruns = jitter.underruns,
            dropped = jitter.dropped,
            concealed = concealed.get(),
            rate = trackRate,
            playing = !jitter.buffering && runFlag?.get() == true,
            synced = syncedNow,
            syncErrorMs = syncErrorMs,
            offsetMs = clock.offsetMs,
            rttMs = clock.bestRttMs,
            jitterP99Ms = jitter.jitterP99Ms,
            soundClass = chain.soundClass,
            jitter30sMs = j30,
            outputLatency = out,
            needMs = if (j30.isNaN()) null else DeadlineNeed.needMs(j30, out.ms),
            playoutMs = clock.playoutMs
        )
    }

    // ------------------------------------------------------------------ audio thread

    private fun nextPacket(synced: Boolean): AudioPacket? = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        val p = if (synced) jitter.pollSynced(now) else jitter.poll(now)
        if (p == null) {
            try {
                dataAvailable.await(5, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        p
    }

    private class Track(val track: AudioTrack, val rate: Int) {
        var framesWritten = 0L
        val ts = AudioTimestamp()
    }

    private fun loop(flag: AtomicBoolean) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        var t: Track? = null
        val platformEq = PlatformEq()
        val planner = SyncPlanner()
        val trimmer = RateTrimmer()
        var processed = ShortArray(0)
        var trimmed = ShortArray(0)
        var tail = ShortArray(0)
        var appliedPreset: EqPreset? = null
        var appliedSession = -1
        var wasSynced = false
        var nextRouteCheck = 0L
        try {
            while (flag.get()) {
                // Output latency from the timestamp (for needMs) and whether the output is Bluetooth, ~1/s.
                val pres = t?.let { presentationMs(it) }
                if (pres != null && t != null && t.framesWritten > 0) {
                    tsLatency.add(pres - nowMs())
                    tsLatencyMs = tsLatency.valueMs
                }
                val nowEr = SystemClock.elapsedRealtime()
                if (t != null && nowEr >= nextRouteCheck) {
                    nextRouteCheck = nowEr + 1_000
                    routedBluetooth = isBluetoothRoute(t.track)
                }
                // Synced playback needs the host clock and a working output timestamp.
                val synced = t != null && lock.withLock { clock.isReady(nowMs()) } && pres != null
                if (synced != wasSynced) {
                    lock.withLock { jitter.synced = synced }
                    planner.reset()
                    trimmer.reset()
                    wasSynced = synced
                    syncedNow = synced
                    AppLog.i(if (synced) "Clock sync on" else "Clock sync off: adaptive buffer")
                }
                val packet = nextPacket(synced)
                if (packet == null) {
                    level *= 0.8f
                    continue
                }
                if (!flag.get()) break
                if (t == null || packet.rate != t.rate) {
                    t?.let { releaseTrack(it.track) }
                    platformEq.release()
                    appliedPreset = null
                    t = null
                    trackRate = 0
                    val created = createTrack(packet.rate)
                    if (created == null) {
                        sleepQuietly(100)
                        continue
                    }
                    t = Track(created, packet.rate)
                    tsLatency.reset()
                    tsLatencyMs = Double.NaN
                    nextRouteCheck = 0L
                }
                val track = t ?: continue

                // EQ: platform equalizer when possible, else the software fallback in the chain.
                val preset = eqPreset
                val session = track.track.audioSessionId
                if (preset != appliedPreset || session != appliedSession) {
                    val platformOk = platformEq.attach(session, preset, SystemClock.elapsedRealtime())
                    appliedPreset = preset
                    appliedSession = session
                    chain.platformPreGain = if (platformOk) PlatformEq.preGainFor(platformEq.maxBoostDb) else 1f
                    val (hl, hr) = hearing
                    chain.eqSpec = EqSpec(preset, includePreset = !platformOk, hearingLeftDb = hl, hearingRightDb = hr)
                } else {
                    val (hl, hr) = hearing
                    val spec = chain.eqSpec
                    if (spec.hearingLeftDb != hl || spec.hearingRightDb != hr) {
                        chain.eqSpec = spec.copy(hearingLeftDb = hl, hearingRightDb = hr)
                    }
                }
                platformEq.tick(SystemClock.elapsedRealtime())

                var src = packet.samples
                var n = src.size
                var ratio = 1.0
                if (synced && packet.seq >= 0) {
                    val target = lock.withLock { clock.targetLocalMs(packet.seq, calibratedLatencyMs ?: 0.0, delayMs.toDouble()) }
                    val heardAt = presentationMs(track)
                    if (target != null && heardAt != null) {
                        val err = heardAt - target
                        syncErrorMs = err
                        if (abs(err) < MAX_SANE_ERROR_MS) {
                            when (val action = planner.plan(err, track.rate)) {
                                is SyncAction.Skip -> {
                                    val skipSamples = action.frames * 2
                                    if (skipSamples >= n) continue // drop this whole packet
                                    val rest = n - skipSamples
                                    if (tail.size < rest) tail = ShortArray(rest)
                                    System.arraycopy(src, skipSamples, tail, 0, rest)
                                    src = tail
                                    n = rest
                                }
                                is SyncAction.Insert -> {
                                    val frames = minOf(action.frames, track.rate / 5) // ≤ 200 ms per step
                                    if (!writeSilence(track, frames)) {
                                        releaseTrack(track.track)
                                        platformEq.release()
                                        t = null
                                        trackRate = 0
                                        continue
                                    }
                                }
                                is SyncAction.Play -> ratio = action.ratio
                            }
                        }
                    }
                }

                if (processed.size < n) processed = ShortArray(n)
                val g = if (muted) 0f else gain
                val peak = chain.process(src, processed, n, track.rate, g)
                level = max(peak / 32768f, level * 0.85f)
                var out = processed
                var outN = n
                if (ratio != 1.0 || synced) {
                    val need = trimmer.maxOutputSamples(n, ratio)
                    if (trimmed.size < need) trimmed = ShortArray(need)
                    outN = trimmer.process(processed, n, ratio, trimmed)
                    out = trimmed
                }
                if (!writeChunked(track, out, outN)) {
                    releaseTrack(track.track)
                    platformEq.release()
                    t = null
                    trackRate = 0
                }
            }
        } catch (e: Throwable) {
            AppLog.w("Audio thread stopped", e)
        } finally {
            t?.let { releaseTrack(it.track) }
            platformEq.release()
            if (runFlag == null || runFlag === flag) {
                trackRate = 0
                trackMs = 0
                syncedNow = false
                routedBluetooth = null
                tsLatencyMs = Double.NaN
            }
            level = 0f
        }
    }

    /**
     * Local time (ms, [nowMs] clock) at which the next frame written to [t] will be heard, from
     * [AudioTrack.getTimestamp]; null while the track can't report a timestamp yet.
     */
    private fun presentationMs(t: Track): Double? {
        return try {
            if (!t.track.getTimestamp(t.ts) || t.ts.framePosition <= 0) return null
            t.ts.nanoTime / 1_000_000.0 + (t.framesWritten - t.ts.framePosition) * 1000.0 / t.rate
        } catch (_: Throwable) {
            null
        }
    }

    /** True for Bluetooth outputs (A2DP, SCO, LE audio, hearing aids), false for others, null when unknown. */
    private fun isBluetoothRoute(track: AudioTrack): Boolean? = try {
        val type = track.routedDevice?.type
        // AudioDeviceInfo: 7 BLUETOOTH_SCO, 8 BLUETOOTH_A2DP, 23 HEARING_AID, 26 BLE_HEADSET, 27 BLE_SPEAKER, 30 BLE_BROADCAST
        if (type == null) null else type in BLUETOOTH_TYPES
    } catch (_: Throwable) {
        null
    }

    /** Writes in 10 ms pieces so the track never holds more than it needs. Returns false on a track error. */
    private fun writeChunked(t: Track, data: ShortArray, n: Int): Boolean {
        val chunk = max(2, (t.rate / 100) * 2) // 10 ms of stereo samples
        var offset = 0
        while (offset < n) {
            val len = minOf(chunk, n - offset)
            val written = try {
                t.track.write(data, offset, len)
            } catch (_: Throwable) {
                -1
            }
            if (written < 0) return false
            if (written == 0) break
            offset += written
            t.framesWritten += written / 2
        }
        return true
    }

    private val silence = ShortArray(960 * 2)

    private fun writeSilence(t: Track, frames: Int): Boolean {
        var left = frames * 2
        while (left > 0) {
            val len = minOf(left, silence.size)
            val written = try {
                t.track.write(silence, 0, len)
            } catch (_: Throwable) {
                -1
            }
            if (written < 0) return false
            if (written == 0) break
            left -= written
            t.framesWritten += written / 2
        }
        return true
    }

    private fun createTrack(rate: Int): AudioTrack? {
        if (rate !in 8000..192000) return null
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            // Smallest safe device buffer: the device minimum, but at least two 10 ms packets.
            val bytes = max(minBuf, rate * 4 * 20 / 1000)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // API 26 == minSdk
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track.play()
            trackRate = rate
            trackMs = (track.bufferSizeInFrames * 1000L / rate).toInt()
            AppLog.i("AudioTrack $rate Hz, buffer $trackMs ms, session ${track.audioSessionId}")
            track
        } catch (t: Throwable) {
            AppLog.w("AudioTrack creation failed", t)
            null
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
        } catch (_: Throwable) {
        }
        try {
            track.stop()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        /** Errors beyond this mean the clock data is nonsense (host restarted…): play without correcting. */
        private const val MAX_SANE_ERROR_MS = 2_000.0
        private val BLUETOOTH_TYPES = setOf(7, 8, 23, 26, 27, 30)
    }
}
