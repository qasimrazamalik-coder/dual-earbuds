package com.dualearbuds.companion.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Just-in-time runtime permission helpers. Every permission here is optional: the app degrades without it. */
object Permissions {
    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS only exists on Android 13+. */
    fun needsNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(context, Manifest.permission.POST_NOTIFICATIONS)

    /** BLUETOOTH_CONNECT only exists on Android 12+. */
    fun needsBluetooth(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !has(context, Manifest.permission.BLUETOOTH_CONNECT)

    /**
     * True when Android will no longer show the prompt (the user refused it before and the system stopped
     * asking), so the only way is the app's settings page.
     */
    fun permanentlyDenied(context: Context, permission: String, askedBefore: Boolean): Boolean {
        if (!askedBefore || has(context, permission)) return false
        val activity = context.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
