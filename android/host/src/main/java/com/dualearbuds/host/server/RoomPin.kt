package com.dualearbuds.host.server

import java.security.MessageDigest

/** Party-mode room PIN (protocol v3.1): 4–6 digits, off by default, never put in the link or QR code. */
object RoomPin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** True for 4–6 ASCII digits. */
    fun isValid(pin: String?): Boolean =
        pin != null && pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** Keeps only digits and cuts to [MAX_LENGTH]: what a PIN text field should hold. */
    fun sanitizeInput(raw: String): String = raw.filter { it in '0'..'9' }.take(MAX_LENGTH)

    /**
     * Result of checking a listener's `p` against the room PIN ([expected] null = no PIN set).
     * [Check.MISSING] does not count toward the lockout (a page that has not asked yet);
     * [Check.WRONG] does, exactly like a wrong link key.
     */
    enum class Check { OK, MISSING, WRONG }

    fun check(expected: String?, given: String?): Check {
        if (expected == null) return Check.OK
        val g = given?.trim().orEmpty()
        if (g.isEmpty()) return Check.MISSING
        val ok = MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), g.toByteArray(Charsets.UTF_8))
        return if (ok) Check.OK else Check.WRONG
    }
}
