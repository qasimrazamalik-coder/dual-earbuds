package com.dualearbuds.companion.update

/**
 * Semantic version (MAJOR.MINOR.PATCH[-pre][+build]). Accepts an optional leading "v"/"V" (release tags are
 * `vMAJOR.MINOR.PATCH`) and missing minor/patch parts ("3" = 3.0.0, "3.1" = 3.1.0). Build metadata is ignored.
 * Ordering follows semver.org: a pre-release sorts before its release (3.0.0-beta < 3.0.0).
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList()
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val n = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until n) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val CORE = Regex("^(0|[1-9]\\d{0,8})(?:\\.(0|[1-9]\\d{0,8}))?(?:\\.(0|[1-9]\\d{0,8}))?$")
        private val IDENT = Regex("^[0-9A-Za-z-]+$")

        /** Parses a version or a release tag. Returns null for anything that isn't a version. */
        fun parse(raw: String?): SemVer? {
            var s = raw?.trim() ?: return null
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            if (s.isEmpty()) return null
            s = s.substringBefore('+')
            val core = s.substringBefore('-')
            val pre = if (s.contains('-')) s.substringAfter('-') else null
            val m = CORE.matchEntire(core) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
            val patch = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val preParts = if (pre == null) {
                emptyList()
            } else {
                val parts = pre.split('.')
                if (parts.any { !IDENT.matches(it) }) return null
                parts
            }
            return SemVer(major, minor, patch, preParts)
        }

        /** True when [candidate] is a valid version strictly newer than [current]. */
        fun isNewer(candidate: String?, current: String?): Boolean {
            val c = parse(candidate) ?: return false
            val cur = parse(current) ?: return true
            return c > cur
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all { it.isDigit() }
            val bn = b.all { it.isDigit() }
            return when {
                an && bn -> {
                    // Compare numerically without overflow: longer digit string = larger (no leading zeros in semver).
                    val at = a.trimStart('0')
                    val bt = b.trimStart('0')
                    if (at.length != bt.length) at.length.compareTo(bt.length) else at.compareTo(bt)
                }
                an -> -1 // numeric identifiers sort before alphanumeric ones
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
