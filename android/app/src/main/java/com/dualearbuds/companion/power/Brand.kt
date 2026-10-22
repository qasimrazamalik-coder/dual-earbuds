package com.dualearbuds.companion.power

import java.util.Locale

/** Phone makers whose battery managers need their own steps. Pure Kotlin. */
enum class Brand(val label: String) {
    XIAOMI("Xiaomi / Redmi / POCO"),
    OPPO("Oppo / Realme / OnePlus"),
    VIVO("Vivo / iQOO"),
    SAMSUNG("Samsung"),
    OTHER("Other phones");

    companion object {
        /** From Build.MANUFACTURER and Build.BRAND. */
        fun of(manufacturer: String?, brand: String?): Brand {
            val m = (manufacturer ?: "").lowercase(Locale.ROOT)
            val b = (brand ?: "").lowercase(Locale.ROOT)
            fun matches(vararg names: String) = names.any { m.contains(it) || b.contains(it) }
            return when {
                matches("xiaomi", "redmi", "poco") -> XIAOMI
                matches("oppo", "realme", "oneplus") -> OPPO
                matches("vivo", "iqoo") -> VIVO
                matches("samsung") -> SAMSUNG
                else -> OTHER
            }
        }
    }
}
