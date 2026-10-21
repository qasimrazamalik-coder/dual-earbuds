package com.dualearbuds.companion.data

import android.content.Context
import android.content.SharedPreferences
import com.dualearbuds.companion.audio.BufferMode

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Small persisted settings store. Initialised once from [com.dualearbuds.companion.DualEarbudsApp]. */
object Prefs {
    private const val FILE = "dual_earbuds"
    private const val K_LINK = "last_link"
    private const val K_VOLUME = "volume"
    private const val K_BUFFER = "buffer_ms" // manual buffer (kept from v1 so the old setting carries over)
    private const val K_BUFFER_MODE = "buffer_mode"
    private const val K_DELAY = "delay_ms"
    private const val K_WANT_PLAYING = "want_playing"
    private const val K_THEME = "theme"
    private const val K_AUTO_UPDATE = "auto_update"
    private const val K_LAST_UPDATE_CHECK = "last_update_check"
    private const val K_ASKED_NOTIFICATIONS = "asked_notifications"
    private const val K_ASKED_BLUETOOTH = "asked_bluetooth"
    private const val K_ASKED_CAMERA = "asked_camera"
    private const val K_BATTERY_OFFERED = "battery_offered"
    private const val K_SAMSUNG_TIP_HIDDEN = "samsung_tip_hidden"
    private const val K_RECENT_HOSTS = "recent_hosts"

    const val DEFAULT_VOLUME = 100
    const val MAX_VOLUME = 150
    const val DEFAULT_MANUAL_BUFFER_MS = 180
    const val MIN_MANUAL_BUFFER_MS = 30
    const val MAX_MANUAL_BUFFER_MS = 600
    const val MAX_DELAY_MS = 300

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

    var manualBufferMs: Int
        get() = sp.getInt(K_BUFFER, DEFAULT_MANUAL_BUFFER_MS).coerceIn(MIN_MANUAL_BUFFER_MS, MAX_MANUAL_BUFFER_MS)
        set(value) = sp.edit().putInt(K_BUFFER, value.coerceIn(MIN_MANUAL_BUFFER_MS, MAX_MANUAL_BUFFER_MS)).apply()

    var bufferMode: BufferMode
        get() = enumOr(sp.getString(K_BUFFER_MODE, null), BufferMode.AUTO)
        set(value) = sp.edit().putString(K_BUFFER_MODE, value.name).apply()

    var delayMs: Int
        get() = sp.getInt(K_DELAY, 0).coerceIn(0, MAX_DELAY_MS)
        set(value) = sp.edit().putInt(K_DELAY, value.coerceIn(0, MAX_DELAY_MS)).apply()

    /** True while the user wants playback (pressed Start and not Stop). Used for START_STICKY restarts. */
    var wantPlaying: Boolean
        get() = sp.getBoolean(K_WANT_PLAYING, false)
        set(value) = sp.edit().putBoolean(K_WANT_PLAYING, value).apply()

    var theme: ThemeMode
        get() = enumOr(sp.getString(K_THEME, null), ThemeMode.SYSTEM)
        set(value) = sp.edit().putString(K_THEME, value.name).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean(K_AUTO_UPDATE, true)
        set(value) = sp.edit().putBoolean(K_AUTO_UPDATE, value).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(K_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(K_LAST_UPDATE_CHECK, value).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean(K_ASKED_NOTIFICATIONS, false)
        set(value) = sp.edit().putBoolean(K_ASKED_NOTIFICATIONS, value).apply()

    var askedBluetooth: Boolean
        get() = sp.getBoolean(K_ASKED_BLUETOOTH, false)
        set(value) = sp.edit().putBoolean(K_ASKED_BLUETOOTH, value).apply()

    var askedCamera: Boolean
        get() = sp.getBoolean(K_ASKED_CAMERA, false)
        set(value) = sp.edit().putBoolean(K_ASKED_CAMERA, value).apply()

    /** The battery-optimisation offer was shown once (after the first successful playback). */
    var batteryOffered: Boolean
        get() = sp.getBoolean(K_BATTERY_OFFERED, false)
        set(value) = sp.edit().putBoolean(K_BATTERY_OFFERED, value).apply()

    var samsungTipHidden: Boolean
        get() = sp.getBoolean(K_SAMSUNG_TIP_HIDDEN, false)
        set(value) = sp.edit().putBoolean(K_SAMSUNG_TIP_HIDDEN, value).apply()

    /** Encoded with [RecentHosts.encode]. */
    var recentHosts: String
        get() = sp.getString(K_RECENT_HOSTS, "") ?: ""
        set(value) = sp.edit().putString(K_RECENT_HOSTS, value).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
