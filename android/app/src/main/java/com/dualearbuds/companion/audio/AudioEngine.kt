package com.dualearbuds.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class EngineStats(
    /** Sound waiting in the jitter queue. */
    val queuedMs: Int,
    /** The buffer the queue fills to (adaptive or manual, plus delay). */
    val targetMs: Int,
    /** The AudioTrack's own device buffer. */
    val trackMs: Int,
    val underruns: Long,
    val dropped: Long,
    val rate: Int,
    val playing: Boolean
)

/**
 * Jitter buffer + lowest-latency AudioTrack player on its own urgent-audio thread.
 *
 * - Packets go into a [JitterBuffer] ([push], called from the network thread).
 * - The audio thread polls it and writes 10 ms chunks with blocking writes (the AudioTrack paces the thread).
 * - The AudioTrack uses PERFORMANCE_MODE_LOW_LATENCY (API 26+, i.e. always on this app's minSdk) and the
 *   smallest buffer that is still safe: max(device minimum, 2 × 10 ms).
 * - The track is recreated when the sample rate changes.
 */
class AudioEngine {
    @Volatile
    var gain: Float = 1f

    @Volatile
    var muted: Boolean = false

    /** Peak level 0..1 of what was just played (after gain), with a short decay. For the UI meter only. */
    @Volatile
    var level: Float = 0f
        private set

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val jitter = JitterBuffer()

    @Volatile
    private var trackRate = 0

    @Volatile
    private var trackMs = 0

    @Volatile
    private var runFlag: AtomicBoolean? = null

    /** Applies the buffer settings (safe from any thread, takes effect on the next packet). */
    fun configure(mode: BufferMode, manualMs: Int, delayMs: Int) {
        lock.withLock {
            jitter.mode = mode
            jitter.manualMs = manualMs
            jitter.delayMs = delayMs
        }
    }

    /** Starts the audio thread if it is not already running; the adaptive buffer starts again at 60 ms. */
    @Synchronized
    fun start() {
        if (runFlag?.get() == true) return
        val flag = AtomicBoolean(true)
        runFlag = flag
        lock.withLock { jitter.reset(SystemClock.elapsedRealtime()) }
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

    fun push(packet: AudioPacket) {
        lock.withLock {
            jitter.push(packet, SystemClock.elapsedRealtime())
            dataAvailable.signalAll()
        }
    }

    /** Clears queued audio (host stopped sharing or link dropped) and re-enters buffering. Not an underrun. */
    fun flush() {
        lock.withLock { jitter.flush() }
        level = 0f
    }

    /** Zeroes the underrun/drop counters (protocol: `underruns` counts since connect). Keeps the adaptive value. */
    fun resetCounters() {
        lock.withLock { jitter.resetCounters() }
    }

    fun stats(): EngineStats = lock.withLock {
        EngineStats(
            queuedMs = max(0, jitter.queuedMs.roundToInt()),
            targetMs = jitter.targetMs(),
            trackMs = trackMs,
            underruns = jitter.underruns,
            dropped = jitter.dropped,
            rate = trackRate,
            playing = !jitter.buffering && runFlag?.get() == true
        )
    }

    private fun nextPacket(): AudioPacket? = lock.withLock {
        val p = jitter.poll(SystemClock.elapsedRealtime())
        if (p == null) {
            try {
                dataAvailable.await(5, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        p
    }

    private fun loop(flag: AtomicBoolean) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        var track: AudioTrack? = null
        var currentRate = 0
        var scratch = ShortArray(0)
        try {
            while (flag.get()) {
                val packet = nextPacket()
                if (packet == null) {
                    level *= 0.8f
                    continue
                }
                if (!flag.get()) break
                if (track == null || packet.rate != currentRate) {
                    if (track != null) releaseTrack(track)
                    track = null
                    currentRate = 0
                    trackRate = 0
                    val created = createTrack(packet.rate)
                    if (created == null) {
                        sleepQuietly(100)
                        continue
                    }
                    track = created
                    currentRate = packet.rate
                }
                val n = packet.samples.size
                if (scratch.size < n) scratch = ShortArray(n)
                val peak = applyGain(packet.samples, scratch, n)
                level = max(peak / 32768f, level * 0.85f)
                if (!writeChunked(track, scratch, n, currentRate)) {
                    releaseTrack(track)
                    track = null
                    currentRate = 0
                    trackRate = 0
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (track != null) releaseTrack(track)
            if (runFlag == null || runFlag === flag) {
                trackRate = 0
                trackMs = 0
            }
            level = 0f
        }
    }

    /** Writes in 10 ms pieces so the track never holds more than it needs. Returns false on a track error. */
    private fun writeChunked(track: AudioTrack, data: ShortArray, n: Int, rate: Int): Boolean {
        val chunk = max(2, (rate / 100) * 2) // 10 ms of stereo samples
        var offset = 0
        while (offset < n) {
            val len = minOf(chunk, n - offset)
            val written = try {
                track.write(data, offset, len)
            } catch (_: Throwable) {
                -1
            }
            if (written < 0) return false
            if (written == 0) break
            offset += written
        }
        return true
    }

    /** Copies [src] to [dst] with the gain applied; returns the peak absolute sample (sampled every 4th). */
    private fun applyGain(src: ShortArray, dst: ShortArray, n: Int): Int {
        val g = if (muted) 0f else gain
        if (g <= 0f) {
            java.util.Arrays.fill(dst, 0, n, 0.toShort())
            return 0
        }
        var peak = 0
        if (g == 1f) {
            System.arraycopy(src, 0, dst, 0, n)
            var i = 0
            while (i < n) {
                val a = abs(src[i].toInt())
                if (a > peak) peak = a
                i += 4
            }
            return peak
        }
        for (i in 0 until n) {
            var v = (src[i] * g).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            dst[i] = v.toShort()
            if ((i and 3) == 0) {
                val a = abs(v)
                if (a > peak) peak = a
            }
        }
        return peak
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
            track
        } catch (t: Throwable) {
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
}
