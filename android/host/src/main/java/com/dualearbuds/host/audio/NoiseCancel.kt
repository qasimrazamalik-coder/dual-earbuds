package com.dualearbuds.host.audio

/** Smart feature #1: mic noise cancelling for talk-over (Off · Light · Strong; default on = [STRONG]). */
enum class NoiseCancel(val label: String, val wet: Float) {
    OFF("Off", 0f),
    /** 50 % dry / 50 % denoised, as smart-features.md specifies. */
    LIGHT("Light", 0.5f),
    STRONG("Strong", 1f);

    companion object {
        /** "On (when talk-over is used)": Strong. */
        val DEFAULT = STRONG

        fun parse(s: String?): NoiseCancel = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}
