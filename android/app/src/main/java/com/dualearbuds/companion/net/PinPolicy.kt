package com.dualearbuds.companion.net

/**
 * Room PIN rules (docs/protocol.md v3.1): 4–6 digits, never part of the link or QR. Wrong PINs count toward the
 * host's guessing lockout (10 different wrong keys within 60 s → ignored for 5 min), so the listener never
 * retries a PIN by itself and, after [freeTries] wrong PINs, asks the user to wait [cooldownMs] before the next.
 */
class PinPolicy(private val freeTries: Int = 3, private val cooldownMs: Long = 30_000) {
    private var wrong = 0
    private var blockedUntil = 0L

    /** Digits only, 4–6 long. Returns the cleaned PIN or null. */
    fun clean(input: String): String? {
        val digits = input.trim()
        return if (digits.length in 4..6 && digits.all { it in '0'..'9' }) digits else null
    }

    /** Milliseconds the user still has to wait before trying another PIN (0 = may try). */
    fun waitMs(now: Long): Long = (blockedUntil - now).coerceAtLeast(0)

    fun onWrong(now: Long) {
        wrong++
        if (wrong >= freeTries) blockedUntil = now + cooldownMs
    }

    fun onAccepted() {
        wrong = 0
        blockedUntil = 0
    }

    val wrongCount: Int
        get() = wrong
}
