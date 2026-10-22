package com.dualearbuds.companion.setup

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.dualearbuds.companion.bt.EarbudsMonitor
import com.dualearbuds.companion.data.Prefs
import com.dualearbuds.companion.data.SoundSettings
import com.dualearbuds.companion.net.LinkParser
import com.dualearbuds.companion.net.ParseResult
import com.dualearbuds.companion.power.BackgroundSettings
import com.dualearbuds.companion.power.PowerState
import com.dualearbuds.companion.service.PlaybackController
import com.dualearbuds.companion.ui.Permissions
import com.dualearbuds.companion.ui.findActivity
import androidx.core.app.ActivityCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/** Reads [SetupFacts] on the device. Never throws; anything unreadable counts as "unknown". Main thread is fine. */
object SetupFactsReader {

    fun read(context: Context): SetupFacts {
        val power = PowerState.read(context)
        val earbuds = EarbudsMonitor.state.value
        val name = if (earbuds.connected) earbuds.name.takeIf { it.isNotBlank() } else null
        val notifGranted = !Permissions.needsNotifications(context)
        val notifBlocked = !notifGranted && Prefs.askedNotifications && context.findActivity()?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
        } == true
        val (onWifi, mhz) = wifi(context)
        return SetupFacts(
            sdk = Build.VERSION.SDK_INT,
            notificationsGranted = notifGranted,
            notificationsBlocked = notifBlocked,
            ignoringBatteryOptimizations = power.ignoringOptimizations,
            backgroundRestricted = power.backgroundRestricted,
            powerSave = power.powerSave,
            brand = BackgroundSettings.detectBrand(),
            brandStepsDone = Prefs.brandStepsDone,
            bluetoothPermission = !earbuds.permissionMissing && !Permissions.needsBluetooth(context),
            bluetoothOn = !earbuds.bluetoothOff,
            noBluetooth = earbuds.noBluetooth,
            earbudsName = name,
            calibratedMs = name?.let { SoundSettings.latencyFor(it) },
            onWifi = onWifi,
            wifiFrequencyMhz = mhz,
            locationGranted = Permissions.has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                Permissions.has(context, Manifest.permission.ACCESS_COARSE_LOCATION),
            hostIp = hostIp(),
            localAddresses = localAddresses()
        )
    }

    private fun hostIp(): String? {
        PlaybackController.state.value.target?.let { return it.host }
        val link = Prefs.lastLink
        if (link.isBlank()) return null
        return (LinkParser.parse(link) as? ParseResult.Ok)?.target?.host
    }

    /** (on Wi-Fi, frequency MHz or −1). Looks at every Wi-Fi network, not only the default one. */
    @Suppress("DEPRECATION")
    private fun wifi(context: Context): Pair<Boolean, Int> {
        var onWifi = false
        var mhz = -1
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    onWifi = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val info = caps.transportInfo as? WifiInfo
                        if (info != null && info.frequency > 0) mhz = info.frequency
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (mhz <= 0) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val f = wm?.connectionInfo?.frequency ?: -1
                if (f > 0) {
                    mhz = f
                    onWifi = true
                }
            } catch (_: Throwable) {
            }
        }
        return onWifi to mhz
    }

    /** IPv4 addresses of every interface that is up (Wi-Fi client, own hotspot, USB tethering). */
    private fun localAddresses(): List<Pair<String, Int>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.interfaceAddresses.mapNotNull { a ->
                    val ip = a.address as? Inet4Address ?: return@mapNotNull null
                    (ip.hostAddress ?: return@mapNotNull null) to a.networkPrefixLength.toInt()
                }
            }
    } catch (_: Throwable) {
        emptyList()
    }
}
