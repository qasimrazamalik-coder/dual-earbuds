package com.dualearbuds.companion.dsp

import kotlin.math.max
import kotlin.math.pow

/**
 * What the software EQ should do. [preset] is skipped when [includePreset] is false (the platform
 * `audiofx.Equalizer` handles it). [hearingLeftDb]/[hearingRightDb] are per-ear gains at
 * [HEARING_FREQS] (hearing profile, smart-features #6), or null.
 */
data class EqSpec(
    val preset: EqPreset = EqPreset.FLAT,
    val includePreset: Boolean = true,
    val hearingLeftDb: List<Double>? = null,
    val hearingRightDb: List<Double>? = null
) {
    val isNeutral: Boolean
        get() = (!includePreset || preset.isFlat) &&
            hearingLeftDb.orEmpty().all { it == 0.0 } && hearingRightDb.orEmpty().all { it == 0.0 }

    companion object {
        val HEARING_FREQS = doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0, 6000.0, 8000.0)
    }
}

/** One fixed set of filters for both channels, plus a pre-gain that keeps boosts from clipping. */
internal class FilterBank(spec: EqSpec, rate: Int) {
    private val left: Array<Biquad>
    private val right: Array<Biquad>
    val preGain: Float

    init {
        val shared = ArrayList<Biquad>()
        var presetBoost = 0.0
        var hearingBoost = 0.0
        if (spec.includePreset && !spec.preset.isFlat) {
            val g = spec.preset.gainsDb
            val f = EqCurve.PRESET_FREQS
            shared += Biquad.lowShelf(f[0] * 2, g[0], rate)
            shared += Biquad.peaking(f[1], 1.0, g[1], rate)
            shared += Biquad.peaking(f[2], 1.0, g[2], rate)
            shared += Biquad.peaking(f[3], 1.0, g[3], rate)
            shared += Biquad.highShelf(f[4] / 2, g[4], rate)
            presetBoost = max(0.0, g.maxOrNull() ?: 0.0)
        }
        fun hearing(gains: List<Double>?): List<Biquad> {
            if (gains == null) return emptyList()
            hearingBoost = max(hearingBoost, gains.maxOrNull() ?: 0.0)
            return gains.mapIndexedNotNull { i, g ->
                if (i >= EqSpec.HEARING_FREQS.size || g == 0.0) null
                else Biquad.peaking(EqSpec.HEARING_FREQS[i], 1.4, g, rate)
            }
        }
        val hl = hearing(spec.hearingLeftDb)
        val hr = hearing(spec.hearingRightDb)
        left = (shared.map { it.copy() } + hl).toTypedArray()
        right = (shared.map { it.copy() } + hr).toTypedArray()
        // Half of the largest possible boost (preset + hearing can stack) is taken back up front; the limiter
        // after the EQ catches the rest, so a boost never clips.
        preGain = 10.0.pow(-(presetBoost + hearingBoost) / 2 / 20).toFloat()
    }

    fun processLeft(x: Float): Float {
        var y = x * preGain
        for (f in left) y = f.process(y)
        return y
    }

    fun processRight(x: Float): Float {
        var y = x * preGain
        for (f in right) y = f.process(y)
        return y
    }
}

/**
 * Software EQ on interleaved stereo floats (−1..1). A new [EqSpec] is crossfaded in over [crossfadeMs]
 * (300 ms, smart-features #2): both filter banks run during the fade. Filters are only built when the spec
 * changes, never per frame.
 */
class SoftwareEq(private val crossfadeMs: Int = 300) {
    private var rate = 0
    private var spec: EqSpec? = null
    private var active: FilterBank? = null
    private var next: FilterBank? = null
    private var nextIsBypass = false
    private var fadePos = 0
    private var fadeLen = 1

    /** True when there is nothing to do (flat and not fading). */
    val isBypassed: Boolean
        get() = active == null && next == null && !nextIsBypass

    fun setSpec(newSpec: EqSpec, sampleRate: Int) {
        if (newSpec == spec && sampleRate == rate) return
        val rateChanged = sampleRate != rate
        spec = newSpec
        rate = sampleRate
        val bank = if (newSpec.isNeutral) null else FilterBank(newSpec, sampleRate)
        if (rateChanged) {
            active = bank
            next = null
            nextIsBypass = false
            return
        }
        next = bank
        nextIsBypass = bank == null
        fadePos = 0
        fadeLen = max(1, sampleRate * crossfadeMs / 1000)
    }

    /** In place, [frames] stereo frames of [buf]. */
    fun process(buf: FloatArray, frames: Int) {
        val a = active
        val b = next
        if (b == null && !nextIsBypass) {
            if (a == null) return
            for (f in 0 until frames) {
                buf[f * 2] = a.processLeft(buf[f * 2])
                buf[f * 2 + 1] = a.processRight(buf[f * 2 + 1])
            }
            return
        }
        for (f in 0 until frames) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            val la = a?.processLeft(l) ?: l
            val ra = a?.processRight(r) ?: r
            val lb = b?.processLeft(l) ?: l
            val rb = b?.processRight(r) ?: r
            val t = if (fadePos >= fadeLen) 1f else fadePos.toFloat() / fadeLen
            buf[f * 2] = la + (lb - la) * t
            buf[f * 2 + 1] = ra + (rb - ra) * t
            if (fadePos < fadeLen) fadePos++
        }
        if (fadePos >= fadeLen) {
            active = b
            next = null
            nextIsBypass = false
        }
    }
}
