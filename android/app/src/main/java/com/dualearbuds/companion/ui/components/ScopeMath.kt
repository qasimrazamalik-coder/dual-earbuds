package com.dualearbuds.companion.ui.components

import java.util.Locale
import kotlin.math.abs

/** How close an ear is to the common deadline. */
enum class SyncGrade { IN_SYNC, CLOSE, OFF, UNKNOWN }

/** Pure helpers for the sync scope (design-system.md "Signature element") and the live mono readouts. */
object ScopeMath {
    const val RANGE_MS = 50.0

    /** 0..1 position of [offsetMs] on a ±[rangeMs] scale (0.5 = on time); clamped at the edges. */
    fun position(offsetMs: Double, rangeMs: Double = RANGE_MS): Float {
        if (offsetMs.isNaN()) return 0.5f
        return ((offsetMs + rangeMs) / (2 * rangeMs)).coerceIn(0.0, 1.0).toFloat()
    }

    fun outOfRange(offsetMs: Double, rangeMs: Double = RANGE_MS): Boolean = !offsetMs.isNaN() && abs(offsetMs) > rangeMs

    /** ±5 ms is the protocol's target window; ±20 ms is still hard to hear. */
    fun grade(offsetMs: Double?): SyncGrade = when {
        offsetMs == null || offsetMs.isNaN() -> SyncGrade.UNKNOWN
        abs(offsetMs) <= 5.0 -> SyncGrade.IN_SYNC
        abs(offsetMs) <= 20.0 -> SyncGrade.CLOSE
        else -> SyncGrade.OFF
    }

    /** "+3 ms", "−12 ms", "0 ms" (true minus sign), "—" when unknown. */
    fun signedMs(offsetMs: Double?): String {
        if (offsetMs == null || offsetMs.isNaN()) return "—"
        val v = Math.round(offsetMs)
        return when {
            v > 0 -> "+$v ms"
            v < 0 -> "−${-v} ms"
            else -> "0 ms"
        }
    }

    /** "late" / "early" / "on time" for an offset (positive = heard after the deadline). */
    fun direction(offsetMs: Double?): String = when {
        offsetMs == null || offsetMs.isNaN() -> ""
        offsetMs > 2.0 -> "late"
        offsetMs < -2.0 -> "early"
        else -> "on time"
    }

    /** Sample rate as "48 kHz" / "44.1 kHz". */
    fun kHz(rate: Int): String {
        if (rate <= 0) return "—"
        val k = rate / 1000.0
        return if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.US, "%.1f kHz", k)
    }

    /**
     * The header's live readout, e.g. `48 kHz · P 120 ms · UDP · sync +3 ms`. Parts that are unknown are left out.
     */
    fun readout(rate: Int, playoutMs: Int, transport: String, synced: Boolean, offsetMs: Double?, bufferMs: Int): String {
        val parts = mutableListOf<String>()
        if (rate > 0) parts += kHz(rate)
        if (synced && playoutMs > 0) parts += "P $playoutMs ms" else if (bufferMs > 0) parts += "buf $bufferMs ms"
        if (transport.isNotBlank()) parts += transport
        if (synced && offsetMs != null && !offsetMs.isNaN()) parts += "sync ${signedMs(offsetMs)}"
        return parts.joinToString("  ·  ")
    }
}
