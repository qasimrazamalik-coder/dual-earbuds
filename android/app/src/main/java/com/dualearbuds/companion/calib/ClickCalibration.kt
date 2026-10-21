package com.dualearbuds.companion.calib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of one calibration run. */
sealed interface CalibrationResult {
    /** [latencyMs] = median delay; [spreadMs] = max − min of the accepted clicks; [found] of [total] clicks. */
    data class Ok(val latencyMs: Double, val spreadMs: Double, val found: Int, val total: Int) : CalibrationResult
    data class Failed(val reason: String, val found: Int, val total: Int) : CalibrationResult
}

/**
 * Calibration DSP (docs/protocol.md v3.1 "Calibration"). Pure Kotlin, unit-tested with synthetic signals.
 *
 * The app plays [clicks] short clicks [spacingMs] apart through the earbuds; the user holds one earbud to the
 * phone's microphone. For every click we know the moment it should have been heard (its *expected* position in
 * the recording, from the AudioTrack/AudioRecord timestamps). Normalised cross-correlation with the click
 * template inside a search window after each expected position finds where it really arrived; the median of
 * those delays is the output latency the timestamps don't account for.
 */
object ClickCalibration {
    const val CLICKS = 8
    const val SPACING_MS = 600
    const val SEARCH_MS = 500

    /** Also look a little *before* the expected position (timestamps can over-report latency). */
    const val PRE_ROLL_MS = 50
    private const val CLICK_MS = 3.0
    private const val MIN_SCORE = 0.35
    private const val MAX_SPREAD_MS = 25.0

    /** A 3 ms Hann-windowed 3 kHz burst: short, bright and easy to find, quiet enough at −6 dBFS. */
    fun template(rate: Int): FloatArray {
        val n = (rate * CLICK_MS / 1000).toInt().coerceAtLeast(8)
        return FloatArray(n) { i ->
            val w = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / (n - 1))
            (0.5 * w * sin(2 * PI * 3000.0 * i / rate)).toFloat()
        }
    }

    /** Positions (in output frames from the start of playback) where each click starts. */
    fun clickStarts(rate: Int, clicks: Int = CLICKS, spacingMs: Int = SPACING_MS, leadInMs: Int = 300): IntArray =
        IntArray(clicks) { (rate.toLong() * (leadInMs + it * spacingMs) / 1000).toInt() }

    /** The mono playback signal: silence with [clicks] clicks at [clickStarts]. */
    fun playbackSignal(rate: Int, clicks: Int = CLICKS, spacingMs: Int = SPACING_MS, tailMs: Int = 700): FloatArray {
        val starts = clickStarts(rate, clicks, spacingMs)
        val t = template(rate)
        val total = starts.last() + t.size + rate * tailMs / 1000
        val out = FloatArray(total)
        for (s in starts) for (i in t.indices) out[s + i] = t[i]
        return out
    }

    /**
     * Finds the clicks. [recording] is mono at [rate]; [expected] are the recording sample indices where each
     * click would be if the output added no latency. Returns the median delay in ms.
     */
    fun analyse(recording: FloatArray, rate: Int, expected: IntArray, searchMs: Int = SEARCH_MS): CalibrationResult {
        val t = template(rate)
        var tEnergy = 0.0
        for (v in t) tEnergy += v * v
        val tNorm = sqrt(tEnergy)
        val search = rate * searchMs / 1000
        val preRoll = rate * PRE_ROLL_MS / 1000
        val delays = ArrayList<Double>()
        for (e in expected) {
            // The loudest match (raw correlation) is the click; its normalised score decides whether it counts,
            // so quiet noise can never win just because normalisation inflates it.
            var bestDot = 0.0
            var bestPos = -1
            val from = maxOf(0, e - preRoll)
            val to = minOf(recording.size - t.size, e + search)
            if (to <= from) continue
            for (pos in from until to) {
                var dot = 0.0
                for (i in t.indices) dot += recording[pos + i] * t[i]
                if (abs(dot) > bestDot) {
                    bestDot = abs(dot)
                    bestPos = pos
                }
            }
            if (bestPos < 0) continue
            var wEnergy = 0.0
            for (i in t.indices) wEnergy += recording[bestPos + i] * recording[bestPos + i]
            val bestScore = bestDot / (tNorm * sqrt(wEnergy) + 1e-9)
            val bestLag = bestPos - e
            if (bestScore >= MIN_SCORE) delays += bestLag * 1000.0 / rate
        }
        val total = expected.size
        if (delays.size < (total + 1) / 2 + 1) {
            return CalibrationResult.Failed(
                "Heard ${delays.size} of $total clicks. Hold the earbud right against the phone's microphone in a quiet room and try again.",
                delays.size, total
            )
        }
        val sorted = delays.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        // Keep clicks close to the median (echoes / noise can produce outliers).
        val kept = sorted.filter { abs(it - median) <= MAX_SPREAD_MS }
        val spread = kept.last() - kept.first()
        if (kept.size < (total + 1) / 2 + 1) {
            return CalibrationResult.Failed("The clicks didn't line up. Try again somewhere quieter.", kept.size, total)
        }
        val keptMedian = kept[kept.size / 2]
        return CalibrationResult.Ok(keptMedian, spread, kept.size, total)
    }
}
