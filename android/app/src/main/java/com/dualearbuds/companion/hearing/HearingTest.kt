package com.dualearbuds.companion.hearing

import com.dualearbuds.companion.dsp.EqSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Ear { LEFT, RIGHT }

/**
 * Simple up-down staircase for one tone (smart-features #6): start clearly audible, go [stepDownDb] quieter
 * after each "I hear it", [stepUpDb] louder after "can't hear". Finished after [reversalsNeeded] direction
 * changes; the threshold is the mean level at the reversals. Levels are dBFS of the test tone.
 * Not a medical hearing test.
 */
class Staircase(
    startDb: Double = -30.0,
    private val stepDownDb: Double = 6.0,
    private val stepUpDb: Double = 3.0,
    private val minDb: Double = -90.0,
    private val maxDb: Double = -12.0,
    private val reversalsNeeded: Int = 4,
    private val maxTrials: Int = 24
) {
    var levelDb: Double = startDb
        private set
    private var lastHeard: Boolean? = null
    private val reversals = ArrayList<Double>()
    private var trials = 0

    val isDone: Boolean
        get() = reversals.size >= reversalsNeeded || trials >= maxTrials

    /** Threshold estimate (dBFS); before the end, the best guess so far. */
    val thresholdDb: Double
        get() = if (reversals.isNotEmpty()) reversals.takeLast(reversalsNeeded).average() else levelDb

    fun answer(heard: Boolean) {
        if (isDone) return
        trials++
        val prev = lastHeard
        if (prev != null && prev != heard) reversals += levelDb
        lastHeard = heard
        if (!heard && levelDb >= maxDb) {
            // Can't hear even at the loudest allowed level: stop; the threshold is "at least" that.
            while (reversals.size < reversalsNeeded) reversals += maxDb
            return
        }
        levelDb = if (heard) max(minDb, levelDb - stepDownDb) else min(maxDb, levelDb + stepUpDb)
    }
}

/** A person's thresholds per ear at [EqSpec.HEARING_FREQS] (dBFS of the test tone at that frequency). */
data class HearingProfile(
    val id: String,
    val name: String,
    val leftDb: List<Double>,
    val rightDb: List<Double>,
    val createdAt: Long
) {
    init {
        require(leftDb.size == EqSpec.HEARING_FREQS.size && rightDb.size == EqSpec.HEARING_FREQS.size)
    }
}

/**
 * Turns thresholds into a gentle EQ: relative to the person's best band (across both ears), each band gets
 * half of its threshold elevation ("half-gain rule"), capped at [maxBoostDb] (+9 dB), never a cut.
 * The EQ stage takes half of the largest boost back as pre-gain and a limiter follows, so the output never
 * rises above a safe level.
 */
object HearingEq {
    const val MAX_BOOST_DB = 9.0

    fun gains(profile: HearingProfile, ear: Ear, maxBoostDb: Double = MAX_BOOST_DB): List<Double> {
        val best = (profile.leftDb + profile.rightDb).minOrNull() ?: return List(EqSpec.HEARING_FREQS.size) { 0.0 }
        val t = if (ear == Ear.LEFT) profile.leftDb else profile.rightDb
        return t.map { thr ->
            val g = (thr - best) / 2.0
            ((g.coerceIn(0.0, maxBoostDb)) * 2).roundToInt() / 2.0 // 0.5 dB steps
        }
    }
}

/**
 * Runs the whole test: every frequency for the left ear, then the right. [current] tells the UI what to
 * play; [answer] records the response. Pure.
 */
class HearingTestSession(private val startDb: Double = -30.0) {
    private val order: List<Pair<Ear, Int>> =
        Ear.entries.flatMap { ear -> EqSpec.HEARING_FREQS.indices.map { ear to it } }
    private var index = 0
    private var stair = Staircase(startDb)
    private val left = DoubleArray(EqSpec.HEARING_FREQS.size)
    private val right = DoubleArray(EqSpec.HEARING_FREQS.size)

    val isDone: Boolean
        get() = index >= order.size

    val step: Int
        get() = index

    val totalSteps: Int
        get() = order.size

    /** (ear, frequency Hz, level dBFS) to play now, or null when finished. */
    val current: Triple<Ear, Double, Double>?
        get() = order.getOrNull(index)?.let { (ear, f) -> Triple(ear, EqSpec.HEARING_FREQS[f], stair.levelDb) }

    fun answer(heard: Boolean) {
        if (isDone) return
        stair.answer(heard)
        if (stair.isDone) {
            val (ear, f) = order[index]
            if (ear == Ear.LEFT) left[f] = stair.thresholdDb else right[f] = stair.thresholdDb
            index++
            stair = Staircase(startDb)
        }
    }

    fun result(id: String, name: String, now: Long): HearingProfile? =
        if (!isDone) null else HearingProfile(id, name, left.toList(), right.toList(), now)
}
