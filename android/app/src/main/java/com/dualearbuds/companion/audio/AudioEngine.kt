package com.dualearbuds.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.roundToInt

class AudioPacket(val rate: Int, val samples: ShortArray) {
    val durationMs: Double = (samples.size / 2) * 1000.0 / rate
}

data class EngineStats(
    val bufferMs: Int,
    val trackMs: Int,
    val underruns: Long,
    val dropped: Long,
    val rate: Int,
    val playing: Boolean
)

/**
 * Jitter buffer + AudioTrack player on its own high-priority thread.
 *
 * - Packets go into a queue ([push], called from the network thread).
 * - The audio thread waits until the queue holds [targetMs] of audio, then plays it with blocking writes
 *   (the AudioTrack paces the thread).
 * - If the queue grows beyond target + 250 ms the oldest packets are dropped back down to the target.
 * - When the queue runs dry (underrun) it refills to the target before resuming.
 * - The AudioTrack is recreated when the sample rate changes.
 */
class AudioEngine {
    @Volatile
    var gain: Float = 1f

    @Volatile
    var targetMs: Int = 180

    @Volatile
    var muted: Boolean = false

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val queue = ArrayDeque<AudioPacket>()
    private var queuedMs = 0.0
    private var buffering = true
    private var underruns = 0L
    private var dropped = 0L

    @Volatile
    private var trackRate = 0

    @Volatile
    private var trackMs = 0

    @Volatile
    private var runFlag: AtomicBoolean? = null

    /** Starts the audio thread if it is not already running. Safe to call from any thread. */
    @Synchronized
    fun start() {
        if (runFlag?.get() == true) return
        val flag = AtomicBoolean(true)
        runFlag = flag
        lock.withLock {
            queue.clear()
            queuedMs = 0.0
            buffering = true
            underruns = 0
            dropped = 0
        }
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
            queue.clear()
            queuedMs = 0.0
            buffering = true
            dataAvailable.signalAll()
        }
    }

    fun push(packet: AudioPacket) {
        lock.withLock {
            queue.addLast(packet)
            queuedMs += packet.durationMs
            val target = targetMs
            if (queuedMs > target + 250) {
                while (queue.size > 1 && queuedMs > target) {
                    val old = queue.removeFirst()
                    queuedMs -= old.durationMs
                    dropped++
                }
            }
            dataAvailable.signalAll()
        }
    }

    /** Clears queued audio (laptop stopped mirroring or link dropped) and re-enters buffering. */
    fun flush() {
        lock.withLock {
            queue.clear()
            queuedMs = 0.0
            buffering = true
        }
    }

    fun stats(): EngineStats = lock.withLock {
        EngineStats(
            bufferMs = max(0, queuedMs.roundToInt()),
            trackMs = trackMs,
            underruns = underruns,
            dropped = dropped,
            rate = trackRate,
            playing = !buffering && runFlag?.get() == true
        )
    }

    private fun nextPacket(): AudioPacket? = lock.withLock {
        if (buffering) {
            if (queue.isNotEmpty() && queuedMs >= targetMs) {
                buffering = false
            } else {
                try {
                    dataAvailable.await(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
                return@withLock null
            }
        }
        val p = queue.removeFirstOrNull()
        if (p == null) {
            underruns++
            buffering = true
            null
        } else {
            queuedMs -= p.durationMs
            if (queue.isEmpty() || queuedMs < 0.0) queuedMs = 0.0
            p
        }
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
                val packet = nextPacket() ?: continue
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
                applyGain(packet.samples, scratch, n)
                val written = try {
                    track.write(scratch, 0, n)
                } catch (t: Throwable) {
                    -1
                }
                if (written < 0) {
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
        }
    }

    private fun applyGain(src: ShortArray, dst: ShortArray, n: Int) {
        val g = if (muted) 0f else gain
        if (g == 1f) {
            System.arraycopy(src, 0, dst, 0, n)
            return
        }
        if (g <= 0f) {
            java.util.Arrays.fill(dst, 0, n, 0.toShort())
            return
        }
        for (i in 0 until n) {
            var v = (src[i] * g).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            dst[i] = v.toShort()
        }
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
            // ~60 ms of device buffer: small enough for low delay, large enough to survive scheduling hiccups.
            val bytes = max(minBuf * 2, rate * 4 * 60 / 1000)
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
