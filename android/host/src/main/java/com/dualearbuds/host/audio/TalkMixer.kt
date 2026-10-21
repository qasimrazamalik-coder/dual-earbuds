package com.dualearbuds.host.audio

import kotlin.math.exp
import kotlin.math.pow

/**
 * Talk-over: mixes the (mono) mic into the captured stereo music and ducks the music by
 * [duckDb] (12 dB) while talking. Gains glide (fast down, slower up) so there are no clicks,
 * and a soft limiter keeps the sum from clipping. Allocation-free.
 */
class TalkMixer(private val rate: Int = 48_000, duckDb: Float = DUCK_DB) {
    private val duckGain = dbToGain(-duckDb)
    private val attack = smoothing(ATTACK_MS)
    private val release = smoothing(RELEASE_MS)

    /** Current music gain (1 = not ducked). */
    var musicGain = 1f
        private set

    /** Current mic gain (0 = mic not in the mix). */
    var micGain = 0f
        private set

    /** True while gains are still moving or the mic is in the mix. */
    val active: Boolean get() = micGain != 0f || musicGain != 1f

    /**
     * Mixes in place: [pcm] is s16le stereo with [frames] frames, [mic] holds [frames] mono samples
     * in −1..1. With [talking] false and gains settled this returns immediately.
     */
    fun process(pcm: ByteArray, frames: Int, mic: FloatArray, talking: Boolean, micLevel: Float = 1f) {
        val musicTarget = if (talking) duckGain else 1f
        val micTarget = if (talking) micLevel else 0f
        if (!talking && !active) return
        var g = musicGain
        var m = micGain
        var i = 0
        for (f in 0 until frames) {
            g += (musicTarget - g) * (if (musicTarget < g) attack else release)
            m += (micTarget - m) * (if (micTarget > m) attack else release)
            val v = mic[f] * m
            for (ch in 0 until 2) {
                val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)) / 32768f
                val out = softClip(s * g + v)
                val q = (out * 32767f).toInt().coerceIn(-32768, 32767)
                pcm[i] = q.toByte()
                pcm[i + 1] = (q shr 8).toByte()
                i += 2
            }
        }
        // Snap once within 0.01 dB / -80 dB of the target so the idle fast path is reached.
        musicGain = if (kotlin.math.abs(musicTarget - g) < 1e-3f) musicTarget else g
        micGain = if (kotlin.math.abs(micTarget - m) < 1e-4f) micTarget else m
    }

    private fun smoothing(ms: Float): Float = (1.0 - exp(-1.0 / (ms / 1000.0 * rate))).toFloat()

    companion object {
        const val DUCK_DB = 12f
        const val ATTACK_MS = 30f
        const val RELEASE_MS = 250f

        fun dbToGain(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        /** Linear up to ±0.8, then a smooth knee that never exceeds ±1. */
        fun softClip(x: Float): Float {
            val a = if (x < 0f) -x else x
            if (a <= KNEE) return x
            val over = (a - KNEE) / (1f - KNEE)
            val shaped = KNEE + (1f - KNEE) * (over / (1f + over))
            return if (x < 0f) -shaped else shaped
        }

        private const val KNEE = 0.8f
    }
}
