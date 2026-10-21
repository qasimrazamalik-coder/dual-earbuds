package com.dualearbuds.companion.data

import android.content.Context
import android.content.SharedPreferences

/** Small persisted settings store. Initialised once from [com.dualearbuds.companion.DualEarbudsApp]. */
object Prefs {
    private const val FILE = "dual_earbuds"
    private const val K_LINK = "last_link"
    private const val K_VOLUME = "volume"
    private const val K_BUFFER = "buffer_ms"
    private const val K_GUIDE_SHOWN = "guide_shown"
    private const val K_WANT_PLAYING = "want_playing"

    const val DEFAULT_VOLUME = 100
    const val DEFAULT_BUFFER_MS = 180
    const val MIN_BUFFER_MS = 60
    const val MAX_BUFFER_MS = 600
    const val MAX_VOLUME = 150

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var lastLink: String
        get() = sp.getString(K_LINK, "") ?: ""
        set(value) = sp.edit().putString(K_LINK, value).apply()

    var volume: Int
        get() = sp.getInt(K_VOLUME, DEFAULT_VOLUME).coerceIn(0, MAX_VOLUME)
        set(value) = sp.edit().putInt(K_VOLUME, value.coerceIn(0, MAX_VOLUME)).apply()

    var bufferMs: Int
        get() = sp.getInt(K_BUFFER, DEFAULT_BUFFER_MS).coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)
        set(value) = sp.edit().putInt(K_BUFFER, value.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)).apply()

    var guideShown: Boolean
        get() = sp.getBoolean(K_GUIDE_SHOWN, false)
        set(value) = sp.edit().putBoolean(K_GUIDE_SHOWN, value).apply()

    /** True while the user wants playback (pressed Start and not Stop). Used for START_STICKY restarts. */
    var wantPlaying: Boolean
        get() = sp.getBoolean(K_WANT_PLAYING, false)
        set(value) = sp.edit().putBoolean(K_WANT_PLAYING, value).apply()
}
