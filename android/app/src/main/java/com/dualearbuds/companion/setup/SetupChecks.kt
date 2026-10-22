package com.dualearbuds.companion.setup

import com.dualearbuds.companion.power.Brand
import java.util.Locale

/** OK = nothing to do; ATTENTION = fix it for better playback; TODO = a manual step not marked done; INFO = optional. */
enum class CheckStatus { OK, ATTENTION, TODO, INFO }

/** What tapping the item's button does (the screen maps it to a permission request or a settings page). */
enum class FixAction {
    NONE,
    REQUEST_NOTIFICATIONS,
    OPEN_NOTIFICATION_SETTINGS,
    REQUEST_BATTERY_EXEMPTION,
    OPEN_APP_INFO,
    OPEN_BATTERY_SAVER,
    OPEN_BRAND_STEPS,
    REQUEST_BLUETOOTH,
    OPEN_BLUETOOTH,
    CALIBRATE,
    REQUEST_LOCATION_FOR_BAND,
    OPEN_WIFI
}

enum class CheckId { NOTIFICATIONS, BATTERY_OPTIMIZATION, BACKGROUND, BATTERY_SAVER, BRAND, BLUETOOTH, CALIBRATION, WIFI_BAND, SAME_NETWORK }

enum class CheckGroup(val label: String) { BACKGROUND("Background playback"), EARBUDS("Earbuds"), NETWORK("Network") }

/**
 * One line of the Setup check: [title], a short [state] word next to the status dot, the one-line [reason] it
 * matters, and the fix.
 */
data class CheckItem(
    val id: CheckId,
    val group: CheckGroup,
    val title: String,
    val status: CheckStatus,
    val state: String,
    val reason: String,
    val action: FixAction = FixAction.NONE,
    val actionLabel: String? = null
) {
    val needsAttention: Boolean
        get() = status == CheckStatus.ATTENTION || status == CheckStatus.TODO
}

/** Everything the checks look at, read on the device by SetupFactsReader. Pure data. */
data class SetupFacts(
    val sdk: Int,
    val notificationsGranted: Boolean,
    /** Android won't show the notification prompt again (refused before). */
    val notificationsBlocked: Boolean = false,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val powerSave: Boolean,
    val brand: Brand,
    val brandStepsDone: Boolean,
    /** BLUETOOTH_CONNECT granted (always true below Android 12). */
    val bluetoothPermission: Boolean,
    val bluetoothOn: Boolean,
    val noBluetooth: Boolean = false,
    val earbudsName: String?,
    val calibratedMs: Double?,
    val onWifi: Boolean,
    /** WifiInfo.frequency in MHz; ≤ 0 when Android didn't tell. */
    val wifiFrequencyMhz: Int,
    val locationGranted: Boolean,
    /** The host's address from the current or last link, null when there is none. */
    val hostIp: String?,
    /** This phone's IPv4 addresses with their prefix length, from every interface that is up (Wi-Fi, hotspot). */
    val localAddresses: List<Pair<String, Int>>
)

/** The Setup check (pure logic, unit-tested). */
object SetupChecks {

    fun evaluate(f: SetupFacts): List<CheckItem> = buildList {
        add(notifications(f))
        add(batteryOptimization(f))
        if (f.sdk >= 28) add(background(f))
        add(batterySaver(f))
        brand(f)?.let { add(it) }
        add(bluetooth(f))
        add(calibration(f))
        add(wifiBand(f))
        add(sameNetwork(f))
    }

    fun attentionCount(items: List<CheckItem>): Int = items.count { it.needsAttention }

    /** Home chip text, or null when nothing needs attention. */
    fun chipText(items: List<CheckItem>): String? {
        val n = attentionCount(items)
        return if (n == 0) null else "Setup: $n to check"
    }

    private fun notifications(f: SetupFacts): CheckItem {
        val reason = "Shows the player with Stop and volume, and keeps Android from closing it."
        return when {
            f.sdk < 33 || f.notificationsGranted -> CheckItem(CheckId.NOTIFICATIONS, CheckGroup.BACKGROUND, "Playing notification", CheckStatus.OK, "Allowed", reason)
            f.notificationsBlocked -> CheckItem(
                CheckId.NOTIFICATIONS, CheckGroup.BACKGROUND, "Playing notification", CheckStatus.ATTENTION, "Off", reason,
                FixAction.OPEN_NOTIFICATION_SETTINGS, "Open settings"
            )
            else -> CheckItem(
                CheckId.NOTIFICATIONS, CheckGroup.BACKGROUND, "Playing notification", CheckStatus.ATTENTION, "Off", reason,
                FixAction.REQUEST_NOTIFICATIONS, "Allow"
            )
        }
    }

    private fun batteryOptimization(f: SetupFacts): CheckItem {
        val reason = "Lets the sound keep playing with the screen off."
        return if (f.ignoringBatteryOptimizations) {
            CheckItem(CheckId.BATTERY_OPTIMIZATION, CheckGroup.BACKGROUND, "Battery optimization", CheckStatus.OK, "Unrestricted", reason)
        } else {
            CheckItem(
                CheckId.BATTERY_OPTIMIZATION, CheckGroup.BACKGROUND, "Battery optimization", CheckStatus.ATTENTION, "Optimized", reason,
                FixAction.REQUEST_BATTERY_EXEMPTION, "Allow"
            )
        }
    }

    private fun background(f: SetupFacts): CheckItem {
        return if (f.backgroundRestricted) {
            CheckItem(
                CheckId.BACKGROUND, CheckGroup.BACKGROUND, "Background activity", CheckStatus.ATTENTION, "Restricted",
                "Android stops restricted apps in the background. In App info → Battery, choose Unrestricted.",
                FixAction.OPEN_APP_INFO, "App info"
            )
        } else {
            CheckItem(CheckId.BACKGROUND, CheckGroup.BACKGROUND, "Background activity", CheckStatus.OK, "Allowed", "Android lets the player run in the background.")
        }
    }

    private fun batterySaver(f: SetupFacts): CheckItem {
        return if (f.powerSave) {
            CheckItem(
                CheckId.BATTERY_SAVER, CheckGroup.BACKGROUND, "Battery saver", CheckStatus.ATTENTION, "On",
                "Battery saver slows Wi-Fi and timers on some phones. Turn it off if the sound stutters.",
                FixAction.OPEN_BATTERY_SAVER, "Open"
            )
        } else {
            CheckItem(CheckId.BATTERY_SAVER, CheckGroup.BACKGROUND, "Battery saver", CheckStatus.OK, "Off", "Full Wi-Fi and timer speed.")
        }
    }

    /** The maker's own battery manager; can't be read, so it's a manual step the user marks done. */
    private fun brand(f: SetupFacts): CheckItem? {
        val (title, reason) = when (f.brand) {
            Brand.SAMSUNG -> "Samsung: Never sleeping apps" to "Add Dual Earbuds to Never sleeping apps so One UI doesn't put it to sleep."
            Brand.XIAOMI -> "Xiaomi: No restrictions and Autostart" to "Set battery saver to No restrictions and allow Autostart, or MIUI/HyperOS closes the player."
            Brand.OPPO -> "Realme / OPPO: Allow background activity" to "Turn on Allow background activity, or ColorOS pauses the player with the screen off."
            Brand.VIVO -> "Vivo: High background power use" to "Allow high background power consumption, or Funtouch OS closes the player."
            Brand.OTHER -> return null
        }
        return if (f.brandStepsDone) {
            CheckItem(CheckId.BRAND, CheckGroup.BACKGROUND, title, CheckStatus.OK, "Done", reason, FixAction.OPEN_BRAND_STEPS, "Steps")
        } else {
            CheckItem(CheckId.BRAND, CheckGroup.BACKGROUND, title, CheckStatus.TODO, "To do", reason, FixAction.OPEN_BRAND_STEPS, "Steps")
        }
    }

    private fun bluetooth(f: SetupFacts): CheckItem {
        val title = "Bluetooth earbuds"
        return when {
            f.noBluetooth -> CheckItem(CheckId.BLUETOOTH, CheckGroup.EARBUDS, title, CheckStatus.INFO, "No Bluetooth", "Sound plays on this phone's speaker or wired headphones.")
            !f.bluetoothPermission -> CheckItem(
                CheckId.BLUETOOTH, CheckGroup.EARBUDS, title, CheckStatus.ATTENTION, "No access",
                "Allow Bluetooth to show which earbuds are connected and use their measured delay.",
                FixAction.REQUEST_BLUETOOTH, "Allow"
            )
            !f.bluetoothOn -> CheckItem(
                CheckId.BLUETOOTH, CheckGroup.EARBUDS, title, CheckStatus.INFO, "Bluetooth off",
                "Turn on Bluetooth and connect your earbuds, or use wired headphones.",
                FixAction.OPEN_BLUETOOTH, "Bluetooth"
            )
            f.earbudsName.isNullOrBlank() -> CheckItem(
                CheckId.BLUETOOTH, CheckGroup.EARBUDS, title, CheckStatus.INFO, "Not connected",
                "Connect your earbuds to this phone. Sound plays wherever the phone's audio goes.",
                FixAction.OPEN_BLUETOOTH, "Bluetooth"
            )
            else -> CheckItem(CheckId.BLUETOOTH, CheckGroup.EARBUDS, title, CheckStatus.OK, f.earbudsName, "Connected to this phone.")
        }
    }

    private fun calibration(f: SetupFacts): CheckItem {
        val title = "Earbuds delay"
        val name = f.earbudsName
        return when {
            name.isNullOrBlank() -> CheckItem(CheckId.CALIBRATION, CheckGroup.EARBUDS, title, CheckStatus.INFO, "—", "Connect earbuds to measure their delay.")
            f.calibratedMs != null -> CheckItem(
                CheckId.CALIBRATION, CheckGroup.EARBUDS, title, CheckStatus.OK,
                String.format(Locale.US, "%.0f ms", f.calibratedMs),
                "Measured for $name. Recalibrate after a firmware update.",
                FixAction.CALIBRATE, "Again"
            )
            else -> CheckItem(
                CheckId.CALIBRATION, CheckGroup.EARBUDS, title, CheckStatus.ATTENTION, "Not measured",
                "Measuring $name's delay lets them play at the same moment as every other ear.",
                FixAction.CALIBRATE, "Calibrate"
            )
        }
    }

    private fun wifiBand(f: SetupFacts): CheckItem {
        val title = "Wi-Fi band"
        val mhz = f.wifiFrequencyMhz
        return when {
            !f.onWifi -> CheckItem(
                CheckId.WIFI_BAND, CheckGroup.NETWORK, title, CheckStatus.INFO, "Not on Wi-Fi",
                "Fine if the host joined this phone's hotspot; otherwise join the host's Wi-Fi.",
                FixAction.OPEN_WIFI, "Wi-Fi"
            )
            mhz in 5_925..7_200 -> CheckItem(CheckId.WIFI_BAND, CheckGroup.NETWORK, title, CheckStatus.OK, "6 GHz", "Fast and uncrowded.")
            mhz in 4_900..5_924 -> CheckItem(CheckId.WIFI_BAND, CheckGroup.NETWORK, title, CheckStatus.OK, "5 GHz", "Fast and less crowded than 2.4 GHz.")
            mhz in 2_400..2_500 -> CheckItem(
                CheckId.WIFI_BAND, CheckGroup.NETWORK, title, CheckStatus.ATTENTION, "2.4 GHz",
                "2.4 GHz is crowded and shares the air with Bluetooth. Use 5 GHz Wi-Fi or a 5 GHz hotspot.",
                FixAction.OPEN_WIFI, "Wi-Fi"
            )
            !f.locationGranted -> CheckItem(
                CheckId.WIFI_BAND, CheckGroup.NETWORK, title, CheckStatus.INFO, "Unknown",
                "This Android version shows the band only with location access; it's used for nothing else.",
                FixAction.REQUEST_LOCATION_FOR_BAND, "Check"
            )
            else -> CheckItem(CheckId.WIFI_BAND, CheckGroup.NETWORK, title, CheckStatus.INFO, "Unknown", "Android didn't report the band.")
        }
    }

    private fun sameNetwork(f: SetupFacts): CheckItem {
        val title = "Same network as the host"
        val host = f.hostIp
        if (host.isNullOrBlank()) {
            return CheckItem(CheckId.SAME_NETWORK, CheckGroup.NETWORK, title, CheckStatus.INFO, "No host yet", "Join a host once; this then checks its address.")
        }
        val same = Ipv4.anySameSubnet(host, f.localAddresses)
        return when (same) {
            true -> CheckItem(CheckId.SAME_NETWORK, CheckGroup.NETWORK, title, CheckStatus.OK, "Yes", "This phone and $host share a network.")
            false -> CheckItem(
                CheckId.SAME_NETWORK, CheckGroup.NETWORK, title, CheckStatus.ATTENTION, "No",
                "$host isn't on this phone's network. Join the host's Wi-Fi or hotspot.",
                FixAction.OPEN_WIFI, "Wi-Fi"
            )
            null -> CheckItem(CheckId.SAME_NETWORK, CheckGroup.NETWORK, title, CheckStatus.INFO, "Unknown", "Couldn't compare addresses on this network.")
        }
    }
}

/** IPv4 helpers (pure). */
object Ipv4 {
    /** Dotted quad → 32-bit value, or null. */
    fun parse(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255 || p.isEmpty() || p.length > 3) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    fun sameSubnet(a: String, b: String, prefix: Int): Boolean? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (x and mask) == (y and mask)
    }

    /** True when [host] is inside any of [locals]' subnets; null when nothing can be compared. */
    fun anySameSubnet(host: String, locals: List<Pair<String, Int>>): Boolean? {
        if (parse(host) == null) return null
        val usable = locals.filter { parse(it.first) != null && it.second in 1..32 && !it.first.startsWith("127.") }
        if (usable.isEmpty()) return null
        return usable.any { sameSubnet(host, it.first, it.second) == true }
    }
}
