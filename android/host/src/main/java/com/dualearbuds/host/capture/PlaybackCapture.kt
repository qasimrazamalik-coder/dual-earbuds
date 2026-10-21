package com.dualearbuds.host.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * AudioPlaybackCapture (Android 10+): copies what other apps play (media, games, unknown usage) as
 * 48 kHz 16-bit stereo. With [uid] only that app is captured (addMatchingUid). Apps that opt out of
 * capture (Netflix, many DRM/banking apps) stay silent; the phone keeps playing normally.
 */
internal object PlaybackCapture {
    const val SAMPLE_RATE = 48_000

    /** Returns an initialized AudioRecord or null. The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun create(projection: MediaProjection, uid: Int?): AudioRecord? {
        return try {
            val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            if (uid != null) builder.addMatchingUid(uid)
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val packet = SAMPLE_RATE / 100 * 4
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, packet * 8))
                .setAudioPlaybackCaptureConfig(builder.build())
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                null
            } else {
                rec
            }
        } catch (_: Throwable) {
            null
        }
    }
}
