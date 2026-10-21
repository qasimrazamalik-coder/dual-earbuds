package com.dualearbuds.companion.calib

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import com.dualearbuds.companion.diag.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Plays the click train through the current output (the earbuds) while recording the microphone, then hands the
 * recording to [ClickCalibration.analyse]. The clicks' *expected* positions in the recording come from the
 * AudioTrack and AudioRecord timestamps (both CLOCK_MONOTONIC), so the result is the latency those timestamps
 * don't report — exactly what synced playback must subtract.
 *
 * Needs RECORD_AUDIO (asked on the calibration screen only). Call from a coroutine; blocks Dispatchers.IO ~6 s.
 */
object CalibrationRunner {
    private const val RATE = 48_000

    @SuppressLint("MissingPermission") // the screen checks RECORD_AUDIO before calling
    suspend fun run(): CalibrationResult = withContext(Dispatchers.IO) {
        val play = ClickCalibration.playbackSignal(RATE)
        val pcm = ShortArray(play.size * 2) { (play[it / 2] * 32767).toInt().toShort() }
        val starts = ClickCalibration.clickStarts(RATE)
        val recFrames = play.size + RATE // one extra second for the output latency
        val rec = FloatArray(recFrames)

        val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRec <= 0) return@withContext CalibrationResult.Failed("This phone's microphone can't record at 48 kHz.", 0, starts.size)
        val record = try {
            openRecorder(MediaRecorder.AudioSource.UNPROCESSED, minRec) ?: openRecorder(MediaRecorder.AudioSource.MIC, minRec)
                ?: return@withContext CalibrationResult.Failed("Couldn't open the microphone.", 0, starts.size)
        } catch (t: Throwable) {
            AppLog.w("Calibration: AudioRecord failed", t)
            return@withContext CalibrationResult.Failed("Couldn't open the microphone.", 0, starts.size)
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), RATE * 4 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            record.release()
            return@withContext CalibrationResult.Failed("Couldn't play the clicks.", 0, starts.size)
        }

        val trackTs = AudioTimestamp()
        val recTs = AudioTimestamp()
        var trackMapped = false
        var recMapped = false
        var trackTsNanos = 0L
        var trackTsFrame = 0L
        var recTsNanos = 0L
        var recTsFrame = 0L
        try {
            record.startRecording()
            track.play()
            val writer = Thread {
                var off = 0
                while (off < pcm.size) {
                    val n = track.write(pcm, off, minOf(1920, pcm.size - off))
                    if (n <= 0) break
                    off += n
                }
            }
            writer.start()
            val buf = ShortArray(960)
            var pos = 0
            while (pos < recFrames) {
                val n = record.read(buf, 0, minOf(buf.size, recFrames - pos))
                if (n <= 0) break
                for (i in 0 until n) rec[pos + i] = buf[i] / 32768f
                pos += n
                // Take one timestamp pair once the streams are running steadily.
                if (!trackMapped && pos > RATE / 2 && track.getTimestamp(trackTs) && trackTs.framePosition > 0) {
                    trackMapped = true
                    trackTsNanos = trackTs.nanoTime
                    trackTsFrame = trackTs.framePosition
                }
                if (!recMapped && pos > RATE / 2 &&
                    record.getTimestamp(recTs, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                ) {
                    recMapped = true
                    recTsNanos = recTs.nanoTime
                    recTsFrame = recTs.framePosition
                }
            }
            writer.join(2_000)
        } catch (t: Throwable) {
            AppLog.w("Calibration run failed", t)
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { record.stop() } catch (_: Throwable) {}
            track.release()
            record.release()
        }
        if (!trackMapped || !recMapped) {
            return@withContext CalibrationResult.Failed(
                "This phone doesn't report audio timing, so calibration can't measure the earbuds. Use the Delay slider instead.",
                0, starts.size
            )
        }
        // Click k leaves the output at trackTsNanos + (start − trackTsFrame)/rate; that moment is this recording index:
        val expected = IntArray(starts.size) { k ->
            val playNanos = trackTsNanos + (starts[k] - trackTsFrame) * 1_000_000_000L / RATE
            (recTsFrame + (playNanos - recTsNanos) * RATE / 1_000_000_000L).toInt()
        }
        val result = ClickCalibration.analyse(rec, RATE, expected)
        AppLog.i("Calibration: $result")
        result
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(source: Int, minBytes: Int): AudioRecord? = try {
        val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBytes, RATE / 5))
        if (r.state == AudioRecord.STATE_INITIALIZED) r else {
            r.release()
            null
        }
    } catch (_: Throwable) {
        null
    }
}
