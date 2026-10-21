package com.dualearbuds.host.server

import java.security.MessageDigest
import java.security.SecureRandom

/** The link key `k`: 8 characters from A–Z and 2–7 (base32 alphabet), compared case-insensitively. */
object LinkKey {
    const val LENGTH = 8
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val random by lazy { SecureRandom() }

    fun generate(rnd: SecureRandom = random): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Upper-cased, trimmed key, or "" for null/blank. */
    fun normalize(raw: String?): String = raw?.trim()?.uppercase() ?: ""

    fun isWellFormed(raw: String?): Boolean {
        val k = normalize(raw)
        return k.length == LENGTH && k.all { it in ALPHABET }
    }

    /** Constant-time comparison of [given] against [expected] (both normalized). */
    fun matches(expected: String, given: String?): Boolean {
        val g = normalize(given)
        if (g.isEmpty()) return false
        val a = normalize(expected).toByteArray(Charsets.US_ASCII)
        val b = g.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }
}
