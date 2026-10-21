package com.dualearbuds.companion.data

import com.dualearbuds.companion.hearing.HearingProfile
import java.util.Locale

/** Small line-based codecs for preferences. Pure Kotlin (unit-tested). */
object Codecs {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /** `name \t value` lines, e.g. calibrated output latency per Bluetooth device name. */
    fun encodeDoubles(map: Map<String, Double>): String =
        map.entries.joinToString("\n") { "${clean(it.key)}\t${String.format(Locale.US, "%.2f", it.value)}" }

    fun decodeDoubles(text: String?): Map<String, Double> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        text.lineSequence().forEach { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0) return@forEach
            val v = line.substring(i + 1).toDoubleOrNull() ?: return@forEach
            out[line.substring(0, i)] = v
        }
        return out
    }

    fun encodeStrings(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${clean(it.key)}\t${clean(it.value)}" }

    fun decodeStrings(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0 || i == line.length - 1) return@forEach
            out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    /** id \t name \t createdAt \t left (comma list) \t right (comma list). */
    fun encodeProfiles(list: List<HearingProfile>): String = list.joinToString("\n") { p ->
        listOf(
            clean(p.id), clean(p.name), p.createdAt.toString(),
            p.leftDb.joinToString(",") { String.format(Locale.US, "%.1f", it) },
            p.rightDb.joinToString(",") { String.format(Locale.US, "%.1f", it) }
        ).joinToString("\t")
    }

    fun decodeProfiles(text: String?): List<HearingProfile> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 5) return@mapNotNull null
            val l = p[3].split(',').mapNotNull { it.toDoubleOrNull() }
            val r = p[4].split(',').mapNotNull { it.toDoubleOrNull() }
            try {
                HearingProfile(p[0], p[1], l, r, p[2].toLongOrNull() ?: 0L)
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toList()
    }
}
