package com.dualearbuds.companion.audio

import android.media.audiofx.Equalizer
import com.dualearbuds.companion.diag.AppLog
import com.dualearbuds.companion.dsp.EqCurve
import com.dualearbuds.companion.dsp.EqPreset
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The preset EQ through Android's `audiofx.Equalizer` on the AudioTrack's session (docs/protocol.md v3.1).
 * Created only when a non-flat preset is in use, so the plain path keeps the lowest latency. If the device has
 * no usable Equalizer, [attach] returns false and [DspChain] runs the preset with its own biquads instead.
 *
 * Preset changes are ramped over ~300 ms in [steps] steps from the audio thread ([tick]). Must be used from the
 * audio thread only.
 */
class PlatformEq(private val steps: Int = 6, private val rampMs: Long = 300) {
    private var eq: Equalizer? = null
    private var session = 0
    private var centersHz = DoubleArray(0)
    private var minMb = -1500
    private var maxMb = 1500
    private var current = IntArray(0)
    private var from = IntArray(0)
    private var target = IntArray(0)
    private var rampStart = 0L
    private var preset: EqPreset = EqPreset.FLAT

    /** True when the platform equalizer is handling the preset. */
    val isActive: Boolean
        get() = eq != null

    /** Largest boost (dB) of the current preset, for the pre-gain that keeps it from clipping. */
    val maxBoostDb: Double
        get() = if (eq == null) 0.0 else max(0.0, preset.gainsDb.maxOrNull() ?: 0.0)

    /** Uses [preset] on [sessionId]. Returns false when the platform EQ can't be used (caller falls back). */
    fun attach(sessionId: Int, preset: EqPreset, now: Long): Boolean {
        if (preset.isFlat) {
            release()
            this.preset = preset
            return true
        }
        if (eq == null || session != sessionId) {
            release()
            try {
                val e = Equalizer(0, sessionId)
                val bands = e.numberOfBands.toInt()
                if (bands <= 0) {
                    e.release()
                    return false
                }
                val range = e.bandLevelRange
                minMb = range[0].toInt()
                maxMb = range[1].toInt()
                centersHz = DoubleArray(bands) { e.getCenterFreq(it.toShort()) / 1000.0 }
                current = IntArray(bands)
                from = IntArray(bands)
                target = IntArray(bands)
                e.enabled = true
                eq = e
                session = sessionId
                AppLog.i("Platform EQ on session $sessionId with $bands bands")
            } catch (t: Throwable) {
                AppLog.w("Platform EQ unavailable, using software EQ", t)
                eq = null
                return false
            }
        }
        if (preset != this.preset || rampStart == 0L) {
            this.preset = preset
            val curve = EqCurve.of(preset)
            for (i in centersHz.indices) {
                from[i] = current[i]
                target[i] = (curve.gainAt(centersHz[i]) * 100).roundToInt().coerceIn(minMb, maxMb)
            }
            rampStart = now
        }
        return true
    }

    /** Advances the ramp; call every audio loop iteration. */
    fun tick(now: Long) {
        val e = eq ?: return
        if (rampStart <= 0L) return
        val t = ((now - rampStart).toDouble() / rampMs).coerceIn(0.0, 1.0)
        val stepT = (t * steps).toInt() / steps.toDouble()
        for (i in current.indices) {
            val v = (from[i] + (target[i] - from[i]) * stepT).roundToInt()
            if (v != current[i]) {
                current[i] = v
                try {
                    e.setBandLevel(i.toShort(), v.toShort())
                } catch (_: Throwable) {
                }
            }
        }
        if (t >= 1.0) rampStart = -1L
    }

    fun release() {
        try {
            eq?.release()
        } catch (_: Throwable) {
        }
        eq = null
        session = 0
        rampStart = 0L
        preset = EqPreset.FLAT
    }

    companion object {
        /** Linear pre-gain taking back half of [boostDb] (the software limiter can't see the platform EQ). */
        fun preGainFor(boostDb: Double): Float = 10.0.pow(-boostDb / 2 / 20).toFloat()
    }
}
