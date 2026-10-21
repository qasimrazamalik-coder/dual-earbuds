package com.dualearbuds.host

import android.content.Context
import android.content.SharedPreferences
import com.dualearbuds.host.audio.NoiseCancel
import com.dualearbuds.host.net.LinkBuilder
import com.dualearbuds.host.server.LinkKey
import com.dualearbuds.host.server.RoomPin

/** The one app whose sound is shared ("One app only"). */
data class AppChoice(val packageName: String, val label: String)

/** Saved host settings. Talk-over and "Show on this network" are per session and not saved. */
data class HostPrefs(
    val key: String,
    val port: Int = LinkBuilder.DEFAULT_PORT,
    val party: Boolean = false,
    val pinOn: Boolean = false,
    val pin: String = "",
    val remote: Boolean = false,
    val app: AppChoice? = null,
    val noiseCancel: NoiseCancel = NoiseCancel.DEFAULT
) {
    /** The PIN in force: only in Party mode, only when switched on and valid. */
    val activePin: String? get() = if (party && pinOn && RoomPin.isValid(pin)) pin else null
}

internal class HostSettings(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("dualearbuds_host", Context.MODE_PRIVATE)

    fun load(): HostPrefs {
        val saved = p.getString(K_LINK, null)
        val key = if (saved != null && LinkKey.isWellFormed(saved)) LinkKey.normalize(saved) else newKey()
        val pkg = p.getString(K_APP_PKG, null)
        return HostPrefs(
            key = key,
            port = p.getInt(K_PORT, LinkBuilder.DEFAULT_PORT).takeIf { it in 1024..65535 } ?: LinkBuilder.DEFAULT_PORT,
            party = p.getBoolean(K_PARTY, false),
            pinOn = p.getBoolean(K_PIN_ON, false),
            pin = p.getString(K_PIN, "").orEmpty(),
            remote = p.getBoolean(K_REMOTE, false),
            app = pkg?.let { AppChoice(it, p.getString(K_APP_LABEL, null) ?: it) },
            noiseCancel = NoiseCancel.parse(p.getString(K_NC, null))
        )
    }

    fun save(s: HostPrefs) {
        p.edit()
            .putString(K_LINK, s.key)
            .putInt(K_PORT, s.port)
            .putBoolean(K_PARTY, s.party)
            .putBoolean(K_PIN_ON, s.pinOn)
            .putString(K_PIN, s.pin)
            .putBoolean(K_REMOTE, s.remote)
            .putString(K_APP_PKG, s.app?.packageName)
            .putString(K_APP_LABEL, s.app?.label)
            .putString(K_NC, s.noiseCancel.name)
            .apply()
    }

    private fun newKey(): String {
        val k = LinkKey.generate()
        p.edit().putString(K_LINK, k).apply()
        return k
    }

    private companion object {
        const val K_LINK = "link_key"
        const val K_PORT = "port"
        const val K_PARTY = "party"
        const val K_PIN_ON = "pin_on"
        const val K_PIN = "pin"
        const val K_REMOTE = "remote"
        const val K_APP_PKG = "source_app_pkg"
        const val K_APP_LABEL = "source_app_label"
        const val K_NC = "noise_cancel"
    }
}
