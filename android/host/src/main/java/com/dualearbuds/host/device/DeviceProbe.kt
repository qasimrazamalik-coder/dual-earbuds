package com.dualearbuds.host.device

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dualearbuds.host.net.HostAddress
import com.dualearbuds.host.net.NetKind
import com.dualearbuds.host.setup.DeviceFacts

/**
 * Reads the phone's state for the Setup check. Needs no runtime permission: Wi-Fi frequency is not
 * location data, the hotspot shows in this phone's own addresses, and AudioManager names the output.
 * Cheap; call from a background thread every few seconds.
 */
internal object DeviceProbe {

    fun facts(context: Context, addresses: List<HostAddress>, canCalibrate: Boolean): DeviceFacts {
        val app = context.applicationContext
        val pm = app.getSystemService(PowerManager::class.java)
        val wifi = wifiState(app)
        return DeviceFacts(
            sdk = Build.VERSION.SDK_INT,
            brand = "${Build.MANUFACTURER} ${Build.BRAND}",
            powerSave = safe(false) { pm?.isPowerSaveMode == true },
            backgroundRestricted = safe(false) {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
            },
            ignoringBatteryOptimizations = safe(true) { pm?.isIgnoringBatteryOptimizations(app.packageName) != false },
            notificationsAllowed = safe(true) { notificationsAllowed(app) },
            wifiOn = wifi.on,
            wifiConnected = wifi.connected,
            wifiFrequencyMhz = wifi.frequencyMhz,
            hotspotOn = addresses.any { it.kind == NetKind.HOTSPOT },
            bluetoothOn = safe<Boolean?>(null) { app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled },
            localOutput = safe(null) { LocalOutputProbe.current(app) },
            canCalibrate = canCalibrate
        )
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private class Wifi(val on: Boolean, val connected: Boolean, val frequencyMhz: Int)

    @Suppress("DEPRECATION")
    private fun wifiState(context: Context): Wifi {
        val wm = context.getSystemService(WifiManager::class.java)
        val on = safe(false) { wm?.isWifiEnabled == true }
        var connected = false
        var freq = 0
        safe(Unit) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@safe
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                connected = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (caps.transportInfo as? WifiInfo)?.frequency?.takeIf { it > 0 }?.let { freq = it }
                }
                break
            }
        }
        if (connected && freq <= 0) freq = safe(0) { wm?.connectionInfo?.frequency ?: 0 }.coerceAtLeast(0)
        return Wifi(on, connected, freq)
    }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Throwable) {
        fallback
    }
}
