package com.dualearbuds.host.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.dualearbuds.host.HostConfig
import com.dualearbuds.host.server.LocalOutput
import com.dualearbuds.host.server.LocalOutputKind

/**
 * Which output this phone plays media on right now (its own ear, protocol v3.2 host local need)
 * and its calibrated latency. No permission needed: AudioManager lists outputs to every app.
 */
internal object LocalOutputProbe {

    fun current(context: Context): LocalOutput {
        val am = context.getSystemService(AudioManager::class.java) ?: return LocalOutput(LocalOutputKind.SPEAKER)
        val device = mediaRoute(am) ?: bestGuess(am)
        val kind = device?.let { kindOf(it.type) } ?: LocalOutputKind.SPEAKER
        val name = if (device == null || kind == LocalOutputKind.SPEAKER) "" else device.productName?.toString().orEmpty().trim()
        return LocalOutput(kind, name, calibration(context, name))
    }

    /** Android 13+: the device media is actually routed to. */
    private fun mediaRoute(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { routed ->
                // The routed entry may lack the product name; take it from the full list.
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == routed.id } ?: routed
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Older Android: media goes to Bluetooth when connected, else to a wired headset, else the speaker. */
    private fun bestGuess(am: AudioManager): AudioDeviceInfo? {
        val all = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (_: Throwable) {
            emptyList()
        }
        return all.firstOrNull { kindOf(it.type) == LocalOutputKind.BLUETOOTH }
            ?: all.firstOrNull { kindOf(it.type) == LocalOutputKind.WIRED }
    }

    fun kindOf(type: Int): LocalOutputKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> LocalOutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> LocalOutputKind.WIRED
        else -> LocalOutputKind.SPEAKER
    }

    private fun calibration(context: Context, name: String): Double? {
        if (name.isBlank()) return null
        HostConfig.localOutputLatencyMs?.let { provider ->
            return try {
                provider(name)
            } catch (_: Throwable) {
                null
            }
        }
        return try {
            val text = context.applicationContext
                .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                .getString(APP_LATENCY_KEY, null)
            LocalOutput.lookup(LocalOutput.decodeCalibration(text), name)
        } catch (_: Throwable) {
            null
        }
    }

    /** Where the listener app keeps its per-earbuds calibration (read-only here). */
    private const val APP_PREFS = "dual_earbuds"
    private const val APP_LATENCY_KEY = "output_latency"
}
