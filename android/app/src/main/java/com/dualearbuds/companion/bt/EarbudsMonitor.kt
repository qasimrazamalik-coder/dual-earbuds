package com.dualearbuds.companion.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Currently connected Bluetooth audio device on the phone. battery = -1 when not reported. */
data class EarbudsState(
    val connected: Boolean = false,
    val name: String = "",
    val address: String = "",
    val battery: Int = -1,
    val permissionMissing: Boolean = false,
    val bluetoothOff: Boolean = false,
    val noBluetooth: Boolean = false
)

/**
 * Process-wide watcher for the phone's Bluetooth earbuds: name via A2DP / headset profile proxies,
 * battery via the hidden BATTERY_LEVEL_CHANGED broadcast and BluetoothDevice.getBatteryLevel() (reflection).
 * All state changes happen on the main thread.
 */
object EarbudsMonitor {
    private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    private val _state = MutableStateFlow(EarbudsState())
    val state: StateFlow<EarbudsState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var receiverRegistered = false

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var a2dpRequested = false
    private var headsetRequested = false

    private val batteryCache = ConcurrentHashMap<String, Int>()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            main.post {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dp = proxy
                    BluetoothProfile.HEADSET -> headset = proxy
                }
                recompute()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            main.post {
                when (profile) {
                    BluetoothProfile.A2DP -> {
                        a2dp = null
                        a2dpRequested = false
                    }
                    BluetoothProfile.HEADSET -> {
                        headset = null
                        headsetRequested = false
                    }
                }
                recompute()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_BATTERY_LEVEL_CHANGED -> {
                        val device = IntentCompat.getParcelableExtra(
                            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                        )
                        val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                        val address = try {
                            device?.address
                        } catch (_: Throwable) {
                            null
                        }
                        if (address != null) {
                            if (level in 0..100) batteryCache[address] = level else batteryCache.remove(address)
                        }
                        recompute()
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        refreshNow()
                        main.postDelayed({ refreshNow() }, 1500)
                    }
                    else -> {
                        recompute()
                        // Profile proxies update their connected list slightly after the broadcast.
                        main.postDelayed({ recompute() }, 1500)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        registerReceiver()
        refresh()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Re-reads everything. Call after the BLUETOOTH_CONNECT permission is granted or on app resume. */
    fun refresh() {
        main.post { refreshNow() }
    }

    private fun registerReceiver() {
        val ctx = appContext ?: return
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            // Exported: these broadcasts come from the system Bluetooth app (another process).
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun adapter(ctx: Context): BluetoothAdapter? = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (_: Throwable) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun refreshNow() {
        val ctx = appContext ?: return
        if (!hasPermission(ctx)) {
            _state.value = EarbudsState(permissionMissing = true)
            return
        }
        val adapter = adapter(ctx)
        if (adapter == null) {
            _state.value = EarbudsState(noBluetooth = true)
            return
        }
        val enabled = try {
            adapter.isEnabled
        } catch (_: Throwable) {
            false
        }
        if (!enabled) {
            _state.value = EarbudsState(bluetoothOff = true)
            return
        }
        try {
            if (a2dp == null && !a2dpRequested) {
                a2dpRequested = adapter.getProfileProxy(ctx, profileListener, BluetoothProfile.A2DP)
            }
            if (headset == null && !headsetRequested) {
                headsetRequested = adapter.getProfileProxy(ctx, profileListener, BluetoothProfile.HEADSET)
            }
        } catch (_: Throwable) {
        }
        recompute()
    }

    @SuppressLint("MissingPermission")
    private fun recompute() {
        val ctx = appContext ?: return
        if (!hasPermission(ctx)) {
            _state.value = EarbudsState(permissionMissing = true)
            return
        }
        val adapter = adapter(ctx)
        val enabled = try {
            adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }
        if (adapter == null) {
            _state.value = EarbudsState(noBluetooth = true)
            return
        }
        if (!enabled) {
            _state.value = EarbudsState(bluetoothOff = true)
            return
        }
        val devices = LinkedHashMap<String, BluetoothDevice>()
        for (profile in listOfNotNull(a2dp, headset)) {
            try {
                for (d in profile.connectedDevices) {
                    val addr = d.address ?: continue
                    if (!devices.containsKey(addr)) devices[addr] = d
                }
            } catch (_: Throwable) {
            }
        }
        val device = devices.values.firstOrNull()
        if (device == null) {
            _state.value = EarbudsState()
            return
        }
        val name = try {
            device.name
        } catch (_: Throwable) {
            null
        }?.takeIf { it.isNotBlank() } ?: "Bluetooth audio"
        val address = device.address ?: ""
        val cached = batteryCache[address]?.takeIf { it in 0..100 }
        val battery = cached ?: readBatteryByReflection(device)
        _state.value = EarbudsState(connected = true, name = name, address = address, battery = battery)
    }

    /** BluetoothDevice.getBatteryLevel() is hidden; it may be blocked on some Android versions. */
    private fun readBatteryByReflection(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val value = method.invoke(device) as? Int ?: -1
            if (value in 0..100) value else -1
        } catch (_: Throwable) {
            -1
        }
    }
}
