package com.dualearbuds.companion.net

/** What kind of device is sharing its sound (`host` in /info and `hello`, protocol v3). */
enum class HostKind(val label: String) {
    LAPTOP("Laptop"),
    PHONE("Phone"),
    UNKNOWN("Host");

    /** Lower-case noun for sentences: "the laptop", "the phone", "the host". */
    val noun: String
        get() = label.lowercase()

    companion object {
        /** "windows" → LAPTOP, "android" → PHONE, anything else (v2 hosts send nothing) → UNKNOWN. */
        fun fromWire(value: String?): HostKind = when (value?.trim()?.lowercase()) {
            "windows" -> LAPTOP
            "android" -> PHONE
            else -> UNKNOWN
        }
    }
}
