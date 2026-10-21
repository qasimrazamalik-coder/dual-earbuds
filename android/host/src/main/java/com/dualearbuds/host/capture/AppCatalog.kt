package com.dualearbuds.host.capture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

/** An installed app the user can open from the launcher. */
data class InstalledApp(val packageName: String, val label: String)

/** Lists launchable apps for "One app only" and resolves an app's uid (needs the `<queries>` in the manifest). */
internal object AppCatalog {

    fun launchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Throwable) {
            emptyList()
        }
        val own = context.packageName
        return infos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == own) return@mapNotNull null
                InstalledApp(pkg, ri.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** The app's uid, or null when it is not installed. */
    fun uidOf(context: Context, packageName: String): Int? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L)).uid
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0).uid
        }
    } catch (_: Throwable) {
        null
    }

    /** The app's icon as a small bitmap, or null. Call off the main thread. */
    fun icon(context: Context, packageName: String, sizePx: Int): Bitmap? = try {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
    } catch (_: Throwable) {
        null
    }
}
