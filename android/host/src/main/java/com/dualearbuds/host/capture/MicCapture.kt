package com.dualearbuds.host.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.dualearbuds.host.audio.MicRing

/**
 * The microphone for talk-over: 48 kHz mono 16-bit into a [MicRing]. Runs only while talk-over is
 * on, so Android's mic indicator shows exactly when the mic is in use.
 */
internal class MicCapture(private val ring: MicRing) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Starts the mic. The caller must hold RECORD_AUDIO. Returns false when the mic is unavailable. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val rate = PlaybackCapture.SAMPLE_RATE
        val rec = try {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 100 * 2 * 8))
        } catch (_: Throwable) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (_: Throwable) {
            rec.release()
            return false
        }
        ring.clear()
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "host-mic").apply { start() }
        return true
    }

    private fun loop(rec: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val buf = ShortArray(PlaybackCapture.SAMPLE_RATE / 100)
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (_: Throwable) {
                -1
            }
            if (n < 0) break
            if (n > 0) ring.write(buf, 0, n)
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        try {
            thread?.join(300)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            rec?.release()
        } catch (_: Throwable) {
        }
        ring.clear()
    }
}
