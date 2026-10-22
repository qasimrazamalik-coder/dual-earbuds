package com.dualearbuds.host.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.dualearbuds.host.setup.Fix

/** Opens the Android screen that fixes a Setup check item. Returns false when none could open. */
internal object SetupFixes {

    @SuppressLint("BatteryLife") // the user taps "Allow" on a line that explains why
    fun open(context: Context, fix: Fix): Boolean {
        val pkg = Uri.parse("package:${context.packageName}")
        val tries: List<Intent> = when (fix) {
            Fix.BATTERY_EXEMPTION -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            Fix.APP_SETTINGS -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            Fix.BATTERY_SAVER_SETTINGS -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            Fix.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            )
            Fix.WIFI_SETTINGS -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
            // No public constant, but every major skin handles it; else the network page.
            Fix.HOTSPOT_SETTINGS -> listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            Fix.BLUETOOTH_SETTINGS -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Fix.CALIBRATE -> emptyList() // handled by the app's callback
        }
        return tries.any { start(context, it) }
    }

    /** Developer options, where the Bluetooth audio codec is chosen. */
    fun openDeveloperOptions(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) ||
            start(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }
}
