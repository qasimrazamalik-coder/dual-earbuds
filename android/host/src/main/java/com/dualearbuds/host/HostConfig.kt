package com.dualearbuds.host

/**
 * Values the app can hand to the host module. Everything is optional; set once (e.g. in
 * `Application.onCreate`) or pass the same values to [HostScreen], which sets them here.
 */
object HostConfig {
    /**
     * Calibrated output latency (ms) of this phone's own output, by device name as Android reports it
     * (Bluetooth: the earbuds' name, the key the app's calibration stores), or null when not calibrated.
     * Used for the host local need of protocol v3.2: listeners line up with what the host's own ear hears.
     * Called off the main thread every few seconds; keep it cheap.
     *
     * When null, the module reads the listener app's stored calibration itself (preference file
     * `dual_earbuds`, key `output_latency`, `name<TAB>ms` lines) and otherwise assumes 180 ms for
     * Bluetooth and 20 ms for wired/speaker.
     */
    @Volatile
    var localOutputLatencyMs: ((deviceName: String) -> Double?)? = null
}
