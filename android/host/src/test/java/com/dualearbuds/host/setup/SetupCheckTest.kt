package com.dualearbuds.host.setup

import com.dualearbuds.host.server.ListenerInfo
import com.dualearbuds.host.server.LocalOutput
import com.dualearbuds.host.server.LocalOutputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCheckTest {
    private fun listener(name: String, synced: Boolean = true, need: Int? = 120, rtt: Int = 8) = ListenerInfo(
        id = name.hashCode(), name = name, client = "android", address = "10.0.0.2", device = name, earbuds = "",
        battery = -1, volume = 100, bufferMs = 60, underruns = 0, playing = true, rttMs = rtt, dropped = 0,
        synced = synced, needMs = need
    )

    private val good = DeviceFacts(
        sdk = 34, brand = "Google", wifiOn = true, wifiConnected = true, wifiFrequencyMhz = 5_180,
        bluetoothOn = true, localOutput = LocalOutput(LocalOutputKind.BLUETOOTH, "Pixel Buds", 150.0)
    )

    private fun ids(items: List<CheckItem>) = items.map { it.id }

    @Test
    fun aWellSetUpPhoneShowsOnlyGoodNews() {
        val items = SetupCheck.evaluate(good)
        assertTrue(items.all { it.level == CheckLevel.OK })
        assertEquals(setOf("battery", "net", "earbuds"), ids(items).toSet())
        assertEquals("Wi-Fi 5 GHz", items.first { it.id == "net" }.title)
    }

    @Test
    fun batteryProblemsComeFirstWithFixes() {
        val items = SetupCheck.evaluate(good.copy(ignoringBatteryOptimizations = false, backgroundRestricted = true, powerSave = true))
        assertEquals(listOf("battery_opt", "background", "power_save"), ids(items).take(3))
        assertEquals(Fix.BATTERY_EXEMPTION, items[0].fix)
        assertEquals(Fix.APP_SETTINGS, items[1].fix)
        assertEquals(Fix.BATTERY_SAVER_SETTINGS, items[2].fix)
        assertFalse("battery" in ids(items))
    }

    @Test
    fun twoPointFourGhzAndNoNetworkAreFlagged() {
        val slow = SetupCheck.evaluate(good.copy(wifiFrequencyMhz = 2_437)).first { it.id == "net" }
        assertEquals(CheckLevel.WARN, slow.level)
        assertEquals(Fix.WIFI_SETTINGS, slow.fix)
        val none = SetupCheck.evaluate(good.copy(wifiOn = false, wifiConnected = false)).first { it.id == "net" }
        assertEquals(Fix.HOTSPOT_SETTINGS, none.fix)
        val hotspot = SetupCheck.evaluate(good.copy(wifiConnected = false, hotspotOn = true)).first { it.id == "net" }
        assertEquals("Hotspot on", hotspot.title)
        assertEquals("Wi-Fi 6 GHz", SetupCheck.evaluate(good.copy(wifiFrequencyMhz = 6_115)).first { it.id == "net" }.title)
    }

    @Test
    fun earbudsCalibrationAndBluetooth() {
        val uncal = SetupCheck.evaluate(good.copy(localOutput = LocalOutput(LocalOutputKind.BLUETOOTH, "Buds"), canCalibrate = true))
            .first { it.id == "earbuds" }
        assertEquals(CheckLevel.INFO, uncal.level)
        assertTrue(uncal.detail.contains("180 ms"))
        assertEquals(Fix.CALIBRATE, uncal.fix)
        val noApp = SetupCheck.evaluate(good.copy(localOutput = LocalOutput(LocalOutputKind.BLUETOOTH, "Buds")))
            .first { it.id == "earbuds" }
        assertNull(noApp.fix)
        val off = SetupCheck.evaluate(good.copy(bluetoothOn = false, localOutput = LocalOutput(LocalOutputKind.SPEAKER)))
            .first { it.id == "earbuds" }
        assertEquals("Bluetooth is off", off.title)
        assertEquals(Fix.BLUETOOTH_SETTINGS, off.fix)
    }

    @Test
    fun weakListenersAreNamed() {
        val l = listOf(listener("Redmi", need = 320), listener("Pixel", need = 110), listener("Old", synced = false, need = 390, rtt = 210))
        val weak = SetupCheck.evaluate(good, l, playoutMs = 320).first { it.id == "weak" }
        assertEquals("Weak links: Redmi, Old", weak.title)
        assertTrue(weak.detail.contains("320 ms"))
        assertNull(SetupCheck.evaluate(good, listOf(listener("Pixel"))).firstOrNull { it.id == "weak" })
    }

    @Test
    fun brandNotes() {
        assertNotNull(SetupCheck.brandNote("samsung"))
        assertFalse(SetupCheck.brandNote("Samsung")!!.always)
        assertTrue(SetupCheck.brandNote("Xiaomi")!!.detail.contains("Autostart"))
        assertTrue(SetupCheck.brandNote("Redmi")!!.detail.contains("No restrictions"))
        assertEquals("Realme: allow background activity", SetupCheck.brandNote("realme")!!.title)
        assertTrue(SetupCheck.brandNote("OPPO")!!.detail.contains("Allow background activity"))
        assertNull(SetupCheck.brandNote("Google"))
        // Samsung's note shows only when the app is limited; Xiaomi's always.
        assertFalse("brand" in ids(SetupCheck.evaluate(good.copy(brand = "samsung"))))
        assertTrue("brand" in ids(SetupCheck.evaluate(good.copy(brand = "samsung", ignoringBatteryOptimizations = false))))
        assertTrue("brand" in ids(SetupCheck.evaluate(good.copy(brand = "Xiaomi"))))
    }

    @Test
    fun notificationsOffIsAHintNotAProblem() {
        val n = SetupCheck.evaluate(good.copy(notificationsAllowed = false)).first { it.id == "notifications" }
        assertEquals(CheckLevel.INFO, n.level)
        assertEquals(Fix.NOTIFICATIONS, n.fix)
    }
}
