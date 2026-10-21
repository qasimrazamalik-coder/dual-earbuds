package com.dualearbuds.companion.audio

import com.dualearbuds.companion.dsp.ClassHysteresis
import com.dualearbuds.companion.dsp.DialogueBoost
import com.dualearbuds.companion.dsp.DialogueMode
import com.dualearbuds.companion.dsp.EqSpec
import com.dualearbuds.companion.dsp.FeatureExtractor
import com.dualearbuds.companion.dsp.Limiter
import com.dualearbuds.companion.dsp.SoftwareEq
import com.dualearbuds.companion.dsp.SoundClass
import com.dualearbuds.companion.dsp.SoundModel
import kotlin.math.abs

/**
 * The listener's sound path on the audio thread (pure Kotlin, unit-tested): s16 in → floats → dialogue boost →
 * software EQ (preset fallback + hearing profile) → limiter → gain → s16 out.
 *
 * When every stage is idle the samples are only scaled by the gain, exactly like the plain path, so features
 * that are off cost nothing. Settings ([eqSpec], [dialogue], [classify]) may change from any thread; the audio
 * thread picks them up at the next packet. No allocation per packet once the buffers have grown.
 */
class DspChain {
    @Volatile
    var eqSpec: EqSpec = EqSpec()

    @Volatile
    var dialogue: DialogueMode = DialogueMode.OFF

    /** Feed the Smart EQ "By sound" classifier. */
    @Volatile
    var classify: Boolean = false

    /** Latest decided sound class (after 5 s hysteresis), or null. Read from any thread. */
    @Volatile
    var soundClass: SoundClass? = null
        private set

    /** Extra attenuation (linear) taken by the platform EQ's boosts, applied before them. */
    @Volatile
    var platformPreGain: Float = 1f

    private var rate = 0
    private val eq = SoftwareEq()
    private val boost = DialogueBoost()
    private val limiter = Limiter()
    private var features = FeatureExtractor()
    private val hysteresis = ClassHysteresis()
    private var buf = FloatArray(0)

    fun reset() {
        boost.reset()
        limiter.reset()
        features.reset()
        hysteresis.reset()
        soundClass = null
    }

    /**
     * Processes [n] interleaved stereo samples from [src] into [dst] with [gain] (0 = silence).
     * Returns the peak absolute output sample (sampled every 4th) for the level meter.
     */
    fun process(src: ShortArray, dst: ShortArray, n: Int, sampleRate: Int, gain: Float): Int {
        if (sampleRate != rate) {
            rate = sampleRate
            boost.setRate(sampleRate)
            limiter.setRate(sampleRate)
            features.setRate(sampleRate)
        }
        val spec = eqSpec
        eq.setSpec(spec, sampleRate)
        boost.mode = dialogue
        val frames = n / 2
        val wantClassify = classify
        val active = !eq.isBypassed || !boost.isIdle || platformPreGain != 1f
        if (!active && !wantClassify) return plain(src, dst, n, gain)

        if (buf.size < n) buf = FloatArray(n)
        for (i in 0 until n) buf[i] = src[i] / 32768f
        if (wantClassify) {
            for (f in 0 until frames) {
                val out = features.feed(((buf[f * 2] + buf[f * 2 + 1]) * 0.5f).toDouble())
                if (out != null) soundClass = hysteresis.update(SoundModel.classify(out))
            }
        }
        if (!active) return plain(src, dst, n, gain)
        if (!boost.isIdle) boost.process(buf, frames)
        if (platformPreGain != 1f) {
            val g = platformPreGain
            for (i in 0 until n) buf[i] *= g
        }
        if (!eq.isBypassed) eq.process(buf, frames)
        limiter.process(buf, frames)
        var peak = 0
        val g = gain * 32767f
        for (i in 0 until n) {
            var v = (buf[i] * g).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            dst[i] = v.toShort()
            if ((i and 3) == 0) {
                val a = abs(v)
                if (a > peak) peak = a
            }
        }
        return peak
    }

    /** Gain only (the path used when no smart feature is on). */
    private fun plain(src: ShortArray, dst: ShortArray, n: Int, gain: Float): Int {
        if (gain <= 0f) {
            java.util.Arrays.fill(dst, 0, n, 0.toShort())
            return 0
        }
        var peak = 0
        if (gain == 1f) {
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
            var v = (src[i] * gain).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            dst[i] = v.toShort()
            if ((i and 3) == 0) {
                val a = abs(v)
                if (a > peak) peak = a
            }
        }
        return peak
    }
}
