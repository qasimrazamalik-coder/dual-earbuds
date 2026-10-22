package com.dualearbuds.host.model

import com.dualearbuds.host.server.ListenerInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/** How close one ear is to the common deadline. */
enum class SyncLevel { GOOD, FAIR, OFF, UNKNOWN }

/** Numbers the host screen shows, worked out without Android so they are unit-tested. */
object Readout {
    /** The sync scope spans ±50 ms around the deadline. */
    const val SCOPE_RANGE_MS = 50

    /** Marker position on the scope, −1 (50 ms early) … +1 (50 ms late); clamped. */
    fun scopePosition(offsetMs: Int): Float =
        (offsetMs.toFloat() / SCOPE_RANGE_MS).coerceIn(-1f, 1f)

    /** Within ±5 ms is in sync (protocol target), up to ±20 ms is audible to some, beyond is off. */
    fun level(offsetMs: Int?): SyncLevel = when {
        offsetMs == null -> SyncLevel.UNKNOWN
        abs(offsetMs) <= 5 -> SyncLevel.GOOD
        abs(offsetMs) <= 20 -> SyncLevel.FAIR
        else -> SyncLevel.OFF
    }

    /** "+3 ms", "−12 ms", "0 ms", or "–" when unknown. */
    fun offset(offsetMs: Int?): String = when {
        offsetMs == null -> "–"
        offsetMs > 0 -> "+$offsetMs ms"
        offsetMs < 0 -> "−${-offsetMs} ms"
        else -> "0 ms"
    }

    /** Every ear: the listeners plus this phone's own. */
    fun ears(listeners: List<ListenerInfo>): Int = listeners.size + 1

    /** Largest |offset| among synced listeners that report one, or null. */
    fun worstSync(listeners: List<ListenerInfo>): Int? =
        listeners.filter { it.synced }.mapNotNull { it.syncErrMs }.maxOfOrNull { abs(it) }

    fun kHz(rate: Int): String {
        if (rate <= 0) return "–"
        val k = rate / 1000.0
        return if (k == k.roundToInt().toDouble()) "${k.roundToInt()} kHz" else String.format(java.util.Locale.US, "%.1f kHz", k)
    }

    /**
     * Header bar readout, e.g. `LIVE  48 kHz · P 92 ms · 4 ears · sync ±3 ms`; `OFF` while not sharing.
     * Parts that are unknown are left out.
     */
    fun header(sharing: Boolean, rate: Int, playoutMs: Int, listeners: List<ListenerInfo>): String {
        if (!sharing) return "OFF"
        val parts = ArrayList<String>()
        parts += kHz(rate)
        if (playoutMs > 0) parts += "P $playoutMs ms"
        val ears = ears(listeners)
        parts += if (ears == 1) "1 ear" else "$ears ears"
        worstSync(listeners)?.let { parts += "sync ±$it ms" }
        return "LIVE  " + parts.joinToString(" · ")
    }

    /** State headline: "Sharing to 4 ears", "Waiting for listeners", "Not sharing". */
    fun headline(sharing: Boolean, starting: Boolean, listeners: List<ListenerInfo>): String = when {
        starting -> "Starting"
        !sharing -> "Not sharing"
        listeners.isEmpty() -> "Waiting for listeners"
        else -> "Sharing to ${ears(listeners)} ears"
    }

    /** "UDP" / "WS" / "Browser" for a listener row. */
    fun transport(l: ListenerInfo): String = when {
        l.client == "web" -> "Browser"
        l.transport == "udp" -> "UDP"
        else -> "WS"
    }
}
