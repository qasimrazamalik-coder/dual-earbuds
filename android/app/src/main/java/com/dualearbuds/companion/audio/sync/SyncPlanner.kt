package com.dualearbuds.companion.audio.sync

import kotlin.math.abs

/** What the audio thread does with the next packet in synced mode. */
sealed interface SyncAction {
    /** Play normally at [ratio] (output frames per input frame; 1.0 = no trim, 0.995..1.005). */
    data class Play(val ratio: Double) : SyncAction

    /** We are late by more than the hard limit: drop [frames] input frames (skip ahead). */
    data class Skip(val frames: Int) : SyncAction

    /** We are early by more than the hard limit: write [frames] frames of silence first. */
    data class Insert(val frames: Int) : SyncAction
}

/**
 * Decides how to keep synced playback on time (docs/protocol.md v3.1): within ±[deadbandMs] do nothing,
 * up to ±[hardLimitMs] trim the playback rate by at most ±[maxTrim] (0.5 %), beyond that skip or insert.
 *
 * `errorMs` = (when the next frame will actually be heard) − (when it should be heard).
 * Positive = late (play faster / skip), negative = early (play slower / insert).
 *
 * The error is smoothed (EMA) for rate trimming because timestamps jitter by a millisecond or two; hard
 * corrections use the raw value and reset the smoothing.
 */
class SyncPlanner(
    private val deadbandMs: Double = 2.0,
    private val trimFullAtMs: Double = 5.0,
    private val hardLimitMs: Double = 40.0,
    private val maxTrim: Double = 0.005,
    private val smoothing: Double = 0.2
) {
    private var smoothed = Double.NaN

    val smoothedErrorMs: Double
        get() = if (smoothed.isNaN()) 0.0 else smoothed

    fun reset() {
        smoothed = Double.NaN
    }

    fun plan(errorMs: Double, rate: Int): SyncAction {
        if (abs(errorMs) > hardLimitMs) {
            smoothed = Double.NaN
            val frames = msToFrames(abs(errorMs), rate)
            return if (errorMs > 0) SyncAction.Skip(frames) else SyncAction.Insert(frames)
        }
        smoothed = if (smoothed.isNaN()) errorMs else smoothed + smoothing * (errorMs - smoothed)
        return SyncAction.Play(ratioFor(smoothed))
    }

    /** Output/input frame ratio for a (smoothed) error: late → < 1 (consume faster), early → > 1. */
    fun ratioFor(errorMs: Double): Double {
        val a = abs(errorMs)
        if (a <= deadbandMs) return 1.0
        val strength = ((a - deadbandMs) / (trimFullAtMs - deadbandMs)).coerceIn(0.0, 1.0)
        val trim = maxTrim * strength
        return if (errorMs > 0) 1.0 - trim else 1.0 + trim
    }

    companion object {
        fun msToFrames(ms: Double, rate: Int): Int = (ms * rate / 1000.0 + 0.5).toInt()
    }
}

/**
 * Linear-interpolation resampler for interleaved stereo s16 with a ratio close to 1 (±0.5 %). Keeps its phase
 * between calls so consecutive packets join without clicks. No allocation per call: the caller passes the
 * output buffer (size ≥ [maxOutputSamples]).
 */
class RateTrimmer {
    /** Read position of the next output frame, in the next packet's frame index (−1 = previous packet's last). */
    private var nextPos = 0.0
    private var prevL = 0
    private var prevR = 0
    private var hasPrev = false

    fun reset() {
        nextPos = 0.0
        hasPrev = false
    }

    /** Upper bound for the output size (in samples, stereo interleaved) for [inputSamples] at [ratio]. */
    fun maxOutputSamples(inputSamples: Int, ratio: Double): Int = ((inputSamples / 2) * ratio + 3).toInt() * 2

    /**
     * Resamples [n] samples of [input] into [out] at [ratio] (output frames per input frame) and returns the
     * number of samples written. At ratio 1 on a frame boundary it simply copies.
     */
    fun process(input: ShortArray, n: Int, ratio: Double, out: ShortArray): Int {
        val frames = n / 2
        if (frames == 0) return 0
        val last = (frames - 1) * 2
        if (ratio == 1.0 && nextPos == 0.0) {
            val count = minOf(frames * 2, out.size - out.size % 2)
            System.arraycopy(input, 0, out, 0, count)
            remember(input, last)
            return count
        }
        val step = 1.0 / ratio // input frames consumed per output frame
        var pos = if (hasPrev) nextPos else maxOf(0.0, nextPos)
        var o = 0
        while (pos < frames - 1 && o + 1 < out.size) {
            val i = kotlin.math.floor(pos).toInt()
            val f = pos - i
            val l0: Int
            val r0: Int
            if (i < 0) {
                l0 = prevL
                r0 = prevR
            } else {
                l0 = input[i * 2].toInt()
                r0 = input[i * 2 + 1].toInt()
            }
            val l1 = input[(i + 1) * 2].toInt()
            val r1 = input[(i + 1) * 2 + 1].toInt()
            out[o++] = (l0 + (l1 - l0) * f).toInt().toShort()
            out[o++] = (r0 + (r1 - r0) * f).toInt().toShort()
            pos += step
        }
        nextPos = pos - frames
        if (nextPos < -1.0) nextPos = -1.0
        remember(input, last)
        return o
    }

    private fun remember(input: ShortArray, last: Int) {
        prevL = input[last].toInt()
        prevR = input[last + 1].toInt()
        hasPrev = true
    }
}
