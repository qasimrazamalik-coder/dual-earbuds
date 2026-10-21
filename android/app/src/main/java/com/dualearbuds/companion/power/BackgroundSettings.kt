package com.dualearbuds.companion.power

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

enum class Brand(val label: String) {
    XIAOMI("Xiaomi / Redmi / POCO"),
    OPPO("Oppo / Realme / OnePlus"),
    VIVO("Vivo / iQOO"),
    SAMSUNG("Samsung"),
    OTHER("Other phones")
}

/** Result of trying to open a settings screen. FALLBACK = we opened this app's App info instead. */
enum class OpenResult { EXACT, FALLBACK, FAILED }

/** One button in the brand guide. */
data class SettingsAction(val label: String, val open: (Context) -> OpenResult)

data class GuideStep(val title: String, val detail: String, val action: SettingsAction? = null)

/**
 * Opens battery / autostart screens on OEM skins. Every startActivity is wrapped: OEM activities move
 * between versions, so we try a list of known components and fall back to this app's App info page.
 */
object BackgroundSettings {

    fun detectBrand(): Brand {
        val m = (Build.MANUFACTURER ?: "").lowercase(Locale.ROOT)
        val b = (Build.BRAND ?: "").lowercase(Locale.ROOT)
        fun matches(vararg names: String) = names.any { m.contains(it) || b.contains(it) }
        return when {
            matches("xiaomi", "redmi", "poco") -> Brand.XIAOMI
            matches("oppo", "realme", "oneplus") -> Brand.OPPO
            matches("vivo", "iqoo") -> Brand.VIVO
            matches("samsung") -> Brand.SAMSUNG
            else -> Brand.OTHER
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) {
        false
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): OpenResult {
        if (isIgnoringBatteryOptimizations(context)) return OpenResult.EXACT
        return tryStart(
            context,
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        )
    }

    fun openAppDetails(context: Context): OpenResult {
        return try {
            context.startActivity(appDetailsIntent(context))
            OpenResult.FALLBACK
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                OpenResult.FALLBACK
            } catch (_: Throwable) {
                OpenResult.FAILED
            }
        }
    }

    fun openBluetoothSettings(context: Context): OpenResult =
        tryStart(context, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))

    fun openNotificationSettings(context: Context): OpenResult {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        return tryStart(context, listOf(i))
    }

    // ------------------------------------------------------------ Xiaomi / Redmi / POCO (MIUI, HyperOS)

    fun openXiaomiAutostart(context: Context): OpenResult = tryStart(
        context,
        listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )
    )

    fun openXiaomiBatterySaver(context: Context): OpenResult {
        val label = try {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        } catch (_: Throwable) {
            "Dual Earbuds"
        }
        return tryStart(
            context,
            listOf(
                Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                    .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", context.packageName)
                    .putExtra("package_label", label),
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
            )
        )
    }

    // ------------------------------------------------------------ Oppo / Realme / OnePlus (ColorOS, realme UI, OxygenOS)

    fun openOppoAutostart(context: Context): OpenResult = tryStart(
        context,
        listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        )
    )

    fun openOppoBackground(context: Context): OpenResult = tryStart(
        context,
        listOf(
            component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity"),
            component("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerControlActivity"),
            component("com.coloros.battery", "com.coloros.powermanager.fuelgaue.PowerControlActivity")
        )
    )

    // ------------------------------------------------------------ Vivo / iQOO (Funtouch OS, OriginOS)

    fun openVivoBackground(context: Context): OpenResult = tryStart(
        context,
        listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        )
    )

    fun openVivoHighPower(context: Context): OpenResult = tryStart(
        context,
        listOf(
            component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            component("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
        )
    )

    // ------------------------------------------------------------ Samsung (One UI)

    fun openSamsungDeviceCare(context: Context): OpenResult = tryStart(
        context,
        listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
    )

    /** On One UI the "Unrestricted" battery option lives in App info → Battery. */
    fun openSamsungUnrestricted(context: Context): OpenResult = openAppDetails(context)

    // ------------------------------------------------------------ guide content

    fun steps(brand: Brand): List<GuideStep> {
        val appInfo = SettingsAction("Open App info") { openAppDetails(it) }
        return when (brand) {
            Brand.XIAOMI -> listOf(
                GuideStep(
                    "Turn on Autostart",
                    "Security → Permissions → Autostart → enable Dual Earbuds.",
                    SettingsAction("Open Autostart") { openXiaomiAutostart(it) }
                ),
                GuideStep(
                    "Battery saver: No restrictions",
                    "App info → Battery saver → choose \"No restrictions\".",
                    SettingsAction("Open battery saver") { openXiaomiBatterySaver(it) }
                ),
                GuideStep(
                    "Lock the app in Recents",
                    "Open Recents, long-press Dual Earbuds (or swipe it down) and tap the lock icon so cleaning doesn't close it."
                ),
                GuideStep(
                    "Allow \"Display pop-up windows while running in background\"",
                    "App info → Other permissions. Not required for sound, but some MIUI versions kill apps without it.",
                    appInfo
                )
            )
            Brand.OPPO -> listOf(
                GuideStep(
                    "Allow Auto launch / Auto startup",
                    "Settings → Apps → Auto launch (or App management → Auto startup) → enable Dual Earbuds.",
                    SettingsAction("Open Auto launch") { openOppoAutostart(it) }
                ),
                GuideStep(
                    "Allow background activity",
                    "App info → Battery usage → turn on \"Allow background activity\" (and \"Allow foreground activity\"). On older versions: Battery → Power saving → Dual Earbuds → allow.",
                    SettingsAction("Open battery manager") { openOppoBackground(it) }
                ),
                GuideStep(
                    "Lock the app in Recents",
                    "Open Recents, tap the ⋮ menu on Dual Earbuds and choose Lock."
                ),
                GuideStep("Check App info", "If a button above didn't open the right page, use App info → Battery usage.", appInfo)
            )
            Brand.VIVO -> listOf(
                GuideStep(
                    "Allow background running",
                    "i Manager → App manager → Autostart / Background startup → enable Dual Earbuds.",
                    SettingsAction("Open background startup") { openVivoBackground(it) }
                ),
                GuideStep(
                    "Allow high background power use",
                    "Settings → Battery → Background power consumption management (or High background power consumption) → allow Dual Earbuds.",
                    SettingsAction("Open power management") { openVivoHighPower(it) }
                ),
                GuideStep("Lock the app in Recents", "Open Recents, pull Dual Earbuds down to lock it."),
                GuideStep("Check App info", "App info → Battery → allow background activity if shown.", appInfo)
            )
            Brand.SAMSUNG -> listOf(
                GuideStep(
                    "Set battery to Unrestricted",
                    "App info → Battery → choose \"Unrestricted\".",
                    SettingsAction("Open App info") { openSamsungUnrestricted(it) }
                ),
                GuideStep(
                    "Add to \"Never sleeping apps\"",
                    "Device care → Battery → Background usage limits → Never sleeping apps → + → Dual Earbuds. Also make sure it is not in \"Sleeping\" or \"Deep sleeping\" apps.",
                    SettingsAction("Open Device care") { openSamsungDeviceCare(it) }
                ),
                GuideStep("Turn off \"Put unused apps to sleep\"", "Same screen: switch off \"Put unused apps to sleep\" if you only use this app occasionally.")
            )
            Brand.OTHER -> listOf(
                GuideStep(
                    "Set battery to Unrestricted",
                    "App info → Battery → choose \"Unrestricted\" or \"Don't optimize\".",
                    appInfo
                ),
                GuideStep("Allow notifications", "The playing notification keeps Android from closing the player.", SettingsAction("Notification settings") { openNotificationSettings(it) }),
                GuideStep("Don't swipe the app away", "Some phones close everything you swipe out of Recents. Lock it there if your phone offers that.")
            )
        }
    }

    // ------------------------------------------------------------ helpers

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Tries each intent in order; falls back to App info. Never throws. */
    fun tryStart(context: Context, candidates: List<Intent>): OpenResult {
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return OpenResult.EXACT
            } catch (_: Throwable) {
                // ActivityNotFoundException, SecurityException (not exported), etc. — try the next one.
            }
        }
        return openAppDetails(context)
    }
}
