package com.dualearbuds.host.setup

import com.dualearbuds.host.server.ListenerInfo
import com.dualearbuds.host.server.LocalOutput
import com.dualearbuds.host.server.LocalOutputKind

/** What the phone reports about itself, gathered by `DeviceProbe` (pure data, JVM-testable). */
data class DeviceFacts(
    val sdk: Int,
    /** Build.MANUFACTURER / BRAND, any case. */
    val brand: String,
    val powerSave: Boolean = false,
    /** ActivityManager.isBackgroundRestricted (Android 9+). */
    val backgroundRestricted: Boolean = false,
    /** PowerManager.isIgnoringBatteryOptimizations for this app. */
    val ignoringBatteryOptimizations: Boolean = true,
    /** POST_NOTIFICATIONS granted (always true below Android 13). */
    val notificationsAllowed: Boolean = true,
    val wifiOn: Boolean = false,
    /** Joined a Wi-Fi network (not this phone's hotspot). */
    val wifiConnected: Boolean = false,
    /** WifiInfo frequency of the joined network in MHz, 0 when unknown. */
    val wifiFrequencyMhz: Int = 0,
    val hotspotOn: Boolean = false,
    /** null = unknown. */
    val bluetoothOn: Boolean? = null,
    val localOutput: LocalOutput? = null,
    /** True when the app gave HostScreen a way to open its calibration screen. */
    val canCalibrate: Boolean = false
)

enum class CheckLevel { OK, WARN, INFO }

/** What a fix button does. The screen maps each to an Android intent or permission request. */
enum class Fix {
    BATTERY_EXEMPTION,
    APP_SETTINGS,
    BATTERY_SAVER_SETTINGS,
    NOTIFICATIONS,
    WIFI_SETTINGS,
    HOTSPOT_SETTINGS,
    BLUETOOTH_SETTINGS,
    CALIBRATE
}

data class CheckItem(
    val id: String,
    val level: CheckLevel,
    val title: String,
    /** One line: what it means for the sound, or what to do. */
    val detail: String,
    val fix: Fix? = null,
    val fixLabel: String? = null
)

/**
 * "Setup check": everything on this phone that makes sharing work better or worse, each with a plain
 * one-line reason and a fix button where Android has one. Order: problems first, then the good news.
 */
object SetupCheck {
    /** A listener whose needMs is this high is waiting on its Wi-Fi (or very slow earbuds). */
    const val POOR_NEED_MS = 280
    const val POOR_RTT_MS = 150

    fun evaluate(f: DeviceFacts, listeners: List<ListenerInfo> = emptyList(), playoutMs: Int = 0): List<CheckItem> {
        val out = ArrayList<CheckItem>()

        // ---- background and battery
        if (!f.ignoringBatteryOptimizations) {
            out += CheckItem(
                "battery_opt", CheckLevel.WARN, "Battery optimisation is on",
                "Android may pause sharing when the screen goes off. Allow this app to run without it.",
                Fix.BATTERY_EXEMPTION, "Allow"
            )
        }
        if (f.backgroundRestricted) {
            out += CheckItem(
                "background", CheckLevel.WARN, "Background use is restricted",
                "Sharing stops when you leave the app. In App info → Battery, choose Unrestricted.",
                Fix.APP_SETTINGS, "App info"
            )
        }
        if (f.powerSave) {
            out += CheckItem(
                "power_save", CheckLevel.WARN, "Battery saver is on",
                "It slows Wi-Fi and timers on many phones. Turn it off while sharing.",
                Fix.BATTERY_SAVER_SETTINGS, "Settings"
            )
        }
        brandNote(f.brand)?.let { note ->
            if (!f.ignoringBatteryOptimizations || f.backgroundRestricted || note.always) {
                out += CheckItem("brand", CheckLevel.INFO, note.title, note.detail, Fix.APP_SETTINGS, "App info")
            }
        }
        if (!f.notificationsAllowed) {
            out += CheckItem(
                "notifications", CheckLevel.INFO, "Notifications are off",
                "Sharing still works, but the Stop button in the notification won't show.",
                Fix.NOTIFICATIONS, "Allow"
            )
        }

        // ---- network
        when {
            f.hotspotOn -> out += CheckItem(
                "net", CheckLevel.OK, "Hotspot on",
                "Best for parties: guests join this phone's hotspot, no router in between."
            )
            f.wifiConnected && isFiveGhz(f.wifiFrequencyMhz) -> out += CheckItem(
                "net", CheckLevel.OK, "Wi-Fi ${bandLabel(f.wifiFrequencyMhz)}",
                "Fast and quiet. Keep guests in the same room as the router."
            )
            f.wifiConnected && isTwoGhz(f.wifiFrequencyMhz) -> out += CheckItem(
                "net", CheckLevel.WARN, "Wi-Fi 2.4 GHz",
                "Crowded and slower, so everyone waits longer. Use 5 GHz or this phone's hotspot.",
                Fix.WIFI_SETTINGS, "Wi-Fi"
            )
            f.wifiConnected -> out += CheckItem("net", CheckLevel.OK, "Wi-Fi connected", "Guests join the same Wi-Fi.")
            else -> out += CheckItem(
                "net", CheckLevel.WARN, "No Wi-Fi or hotspot",
                "Listeners can't reach this phone. Turn on the hotspot or join a Wi-Fi.",
                Fix.HOTSPOT_SETTINGS, "Hotspot"
            )
        }
        val weak = listeners.filter { isPoorLink(it) }
        if (weak.isNotEmpty()) {
            val names = weak.joinToString(", ") { it.displayName }
            out += CheckItem(
                "weak", CheckLevel.WARN,
                if (weak.size == 1) "Weak link: $names" else "Weak links: $names",
                if (playoutMs > 0) "Everyone waits for them (P $playoutMs ms). Move them closer to this phone or the router."
                else "Move them closer to this phone or the router."
            )
        }

        // ---- this phone's own ear
        val o = f.localOutput
        when {
            o != null && o.kind == LocalOutputKind.BLUETOOTH && o.isCalibrated -> out += CheckItem(
                "earbuds", CheckLevel.OK, o.name.ifBlank { "Bluetooth earbuds" },
                "Calibrated: ${o.needMs} ms. Listeners line up with what you hear."
            )
            o != null && o.kind == LocalOutputKind.BLUETOOTH -> out += CheckItem(
                "earbuds", CheckLevel.INFO, "${o.name.ifBlank { "Bluetooth earbuds" }} · not calibrated",
                "Assuming ${o.needMs} ms. Calibrate them for exact sync with your listeners.",
                if (f.canCalibrate) Fix.CALIBRATE else null, if (f.canCalibrate) "Calibrate" else null
            )
            o != null && o.kind == LocalOutputKind.WIRED -> out += CheckItem(
                "earbuds", CheckLevel.OK, o.name.ifBlank { "Wired headphones" }, "About ${o.needMs} ms, nothing to set."
            )
            f.bluetoothOn == false -> out += CheckItem(
                "earbuds", CheckLevel.INFO, "Bluetooth is off",
                "This phone plays on its speaker. Turn Bluetooth on to use your own earbuds.",
                Fix.BLUETOOTH_SETTINGS, "Bluetooth"
            )
            else -> out += CheckItem(
                "earbuds", CheckLevel.INFO, "Speaker",
                "No earbuds connected to this phone. Connect yours to listen along.",
                Fix.BLUETOOTH_SETTINGS, "Bluetooth"
            )
        }

        if (f.ignoringBatteryOptimizations && !f.backgroundRestricted && !f.powerSave) {
            out += CheckItem("battery", CheckLevel.OK, "Runs with the screen off", "No battery limits on this app.")
        }
        // Problems first, then hints, then what is fine (stable within each group).
        return out.sortedBy { if (it.level == CheckLevel.WARN) 0 else if (it.level == CheckLevel.INFO) 1 else 2 }
    }

    fun isPoorLink(l: ListenerInfo): Boolean =
        (l.synced && (l.needMs ?: 0) >= POOR_NEED_MS) || l.rttMs >= POOR_RTT_MS

    fun isFiveGhz(mhz: Int) = mhz in 4_900..7_200
    fun isTwoGhz(mhz: Int) = mhz in 2_400..2_500
    fun bandLabel(mhz: Int) = if (mhz >= 5_925) "6 GHz" else "5 GHz"

    data class BrandNote(val title: String, val detail: String, val always: Boolean)

    /**
     * Phone makers that stop background apps on their own, beyond Android's settings. Shown when the
     * app is limited, and always for the strictest (Xiaomi/Redmi/POCO, Realme/OPPO/OnePlus, vivo).
     */
    fun brandNote(brand: String): BrandNote? {
        val b = brand.trim().lowercase()
        return when {
            b.contains("samsung") -> BrandNote(
                "Samsung: never sleeping apps",
                "Settings → Battery → Background usage limits → Never sleeping apps → add Dual Earbuds.",
                always = false
            )
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> BrandNote(
                "Xiaomi: no restrictions and Autostart",
                "App info → Battery saver → No restrictions, and turn Autostart on.",
                always = true
            )
            b.contains("realme") || b.contains("oppo") || b.contains("oneplus") -> BrandNote(
                "${brandName(b)}: allow background activity",
                "App info → Battery usage → Allow background activity (and Allow auto launch).",
                always = true
            )
            b.contains("vivo") || b.contains("iqoo") -> BrandNote(
                "vivo: allow high background power use",
                "Settings → Battery → Background power consumption → Dual Earbuds → Allow.",
                always = true
            )
            b.contains("huawei") || b.contains("honor") -> BrandNote(
                "${brandName(b)}: manage launch manually",
                "Settings → Battery → App launch → Dual Earbuds → Manage manually, all three on.",
                always = true
            )
            else -> null
        }
    }

    private fun brandName(b: String): String = when {
        b.contains("realme") -> "Realme"
        b.contains("oneplus") -> "OnePlus"
        b.contains("oppo") -> "OPPO"
        b.contains("honor") -> "Honor"
        else -> "Huawei"
    }
}
