package com.dualearbuds.companion.power

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** What the phone's power management is doing to this app right now. */
data class PowerFacts(
    /** Battery saver (PowerManager.isPowerSaveMode). */
    val powerSave: Boolean,
    /** The user (or the system) restricted this app's background work (ActivityManager.isBackgroundRestricted). */
    val backgroundRestricted: Boolean,
    /** Exempt from battery optimization (Doze/App Standby). */
    val ignoringOptimizations: Boolean
) {
    /** One line for logs and diagnostics. */
    fun summary(): String =
        "battery saver ${if (powerSave) "on" else "off"} · background ${if (backgroundRestricted) "restricted" else "allowed"} · " +
            "optimization ${if (ignoringOptimizations) "exempt" else "on"}"
}

object PowerState {
    fun read(context: Context): PowerFacts {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val powerSave = try {
            pm?.isPowerSaveMode == true
        } catch (_: Throwable) {
            false
        }
        val restricted = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isBackgroundRestricted == true
        } catch (_: Throwable) {
            false
        }
        return PowerFacts(powerSave, restricted, BackgroundSettings.isIgnoringBatteryOptimizations(context))
    }
}
