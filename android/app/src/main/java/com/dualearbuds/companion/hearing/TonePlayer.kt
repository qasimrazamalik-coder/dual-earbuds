package com.dualearbuds.companion.hearing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Plays one hearing-test tone (pure sine, 20 ms fades) on one ear. Level is dBFS, capped at −12 dBFS. */
object TonePlayer {
    private const val RATE = 48_000

    suspend fun play(ear: Ear, freqHz: Double, levelDb: Double, durationMs: Int = 900) = withContext(Dispatchers.IO) {
        val frames = RATE * durationMs / 1000
        val fade = RATE / 50
        val amp = 10.0.pow(min(levelDb, -12.0) / 20)
        val pcm = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val env = min(1.0, min(f, frames - 1 - f).toDouble() / fade)
            val v = (amp * env * sin(2 * PI * freqHz * f / RATE) * 32767).toInt().toShort()
            if (ear == Ear.LEFT) pcm[f * 2] = v else pcm[f * 2 + 1] = v
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Throwable) {
            return@withContext
        }
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 50)
        } catch (_: Throwable) {
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            track.release()
        }
    }
}
