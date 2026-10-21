package com.dualearbuds.host

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/** CPU and Wi-Fi locks held while sharing so capture and streaming keep going with the screen off. */
internal class HostLocks(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockHighPerf: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    fun acquire() {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualEarbuds:host").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) }
        } catch (_: Throwable) {
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "DualEarbuds:host").apply { setReferenceCounted(false) }
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (wifiLockHighPerf == null) {
                    wifiLockHighPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DualEarbuds:host-hp")
                        .apply { setReferenceCounted(false) }
                }
                wifiLockHighPerf?.let { if (!it.isHeld) it.acquire() }
            }
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        try {
            wifiLockHighPerf?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
    }
}
