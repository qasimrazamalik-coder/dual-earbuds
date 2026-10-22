package com.dualearbuds.companion.setup

import com.dualearbuds.companion.power.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecksTest {

    private val allGood = SetupFacts(
        sdk = 34,
        notificationsGranted = true,
        ignoringBatteryOptimizations = true,
        backgroundRestricted = false,
        powerSave = false,
        brand = Brand.SAMSUNG,
        brandStepsDone = true,
        bluetoothPermission = true,
        bluetoothOn = true,
        earbudsName = "Galaxy Buds2",
        calibratedMs = 182.0,
        onWifi = true,
        wifiFrequencyMhz = 5_180,
        locationGranted = false,
        hostIp = "192.168.1.20",
        localAddresses = listOf("192.168.1.33" to 24)
    )

    private fun item(items: List<CheckItem>, id: CheckId) = items.first { it.id == id }

    @Test
    fun allGoodNeedsNothing() {
        val items = SetupChecks.evaluate(allGood)
        assertEquals(0, SetupChecks.attentionCount(items))
        assertNull(SetupChecks.chipText(items))
        assertEquals("5 GHz", item(items, CheckId.WIFI_BAND).state)
        assertEquals("182 ms", item(items, CheckId.CALIBRATION).state)
    }

    @Test
    fun everyProblemHasAReasonAndAFix() {
        val bad = allGood.copy(
            notificationsGranted = false, ignoringBatteryOptimizations = false, backgroundRestricted = true,
            powerSave = true, brandStepsDone = false, calibratedMs = null, wifiFrequencyMhz = 2_437,
            localAddresses = listOf("10.0.0.5" to 24)
        )
        val items = SetupChecks.evaluate(bad)
        val attention = items.filter { it.needsAttention }
        assertEquals(8, attention.size)
        attention.forEach {
            assertTrue("${it.id} reason", it.reason.isNotBlank())
            assertTrue("${it.id} fix", it.action != FixAction.NONE && !it.actionLabel.isNullOrBlank())
        }
        assertEquals("Setup: 8 to check", SetupChecks.chipText(items))
        assertEquals(FixAction.REQUEST_NOTIFICATIONS, item(items, CheckId.NOTIFICATIONS).action)
        assertEquals(FixAction.REQUEST_BATTERY_EXEMPTION, item(items, CheckId.BATTERY_OPTIMIZATION).action)
    }

    @Test
    fun blockedNotificationsGoToSettings() {
        val items = SetupChecks.evaluate(allGood.copy(notificationsGranted = false, notificationsBlocked = true))
        assertEquals(FixAction.OPEN_NOTIFICATION_SETTINGS, item(items, CheckId.NOTIFICATIONS).action)
    }

    @Test
    fun oldAndroidHasNoNotificationOrBackgroundCheck() {
        val items = SetupChecks.evaluate(allGood.copy(sdk = 27, notificationsGranted = false))
        assertEquals(CheckStatus.OK, item(items, CheckId.NOTIFICATIONS).status)
        assertTrue(items.none { it.id == CheckId.BACKGROUND })
    }

    @Test
    fun brandStepsPerMaker() {
        assertTrue(item(SetupChecks.evaluate(allGood.copy(brandStepsDone = false)), CheckId.BRAND).title.contains("Never sleeping apps"))
        val xiaomi = item(SetupChecks.evaluate(allGood.copy(brand = Brand.XIAOMI, brandStepsDone = false)), CheckId.BRAND)
        assertTrue(xiaomi.title.contains("No restrictions") && xiaomi.title.contains("Autostart"))
        assertTrue(item(SetupChecks.evaluate(allGood.copy(brand = Brand.OPPO, brandStepsDone = false)), CheckId.BRAND).title.contains("Allow background activity"))
        assertTrue(SetupChecks.evaluate(allGood.copy(brand = Brand.OTHER)).none { it.id == CheckId.BRAND })
        assertEquals(CheckStatus.TODO, item(SetupChecks.evaluate(allGood.copy(brandStepsDone = false)), CheckId.BRAND).status)
    }

    @Test
    fun bluetoothAccessIsAskedWithTheReason() {
        val b = item(SetupChecks.evaluate(allGood.copy(bluetoothPermission = false, earbudsName = null)), CheckId.BLUETOOTH)
        assertEquals(FixAction.REQUEST_BLUETOOTH, b.action)
        assertTrue(b.reason.contains("which earbuds are connected") && b.reason.contains("measured delay"))
        // Wired headphones: not connected is information, not a problem.
        val wired = item(SetupChecks.evaluate(allGood.copy(earbudsName = null)), CheckId.BLUETOOTH)
        assertEquals(CheckStatus.INFO, wired.status)
        assertEquals(CheckStatus.INFO, item(SetupChecks.evaluate(allGood.copy(earbudsName = null)), CheckId.CALIBRATION).status)
    }

    @Test
    fun unknownBandAsksForLocationOnlyOnTap() {
        val unknown = item(SetupChecks.evaluate(allGood.copy(wifiFrequencyMhz = -1)), CheckId.WIFI_BAND)
        assertEquals(CheckStatus.INFO, unknown.status) // not counted: nothing is asked up front
        assertEquals(FixAction.REQUEST_LOCATION_FOR_BAND, unknown.action)
        val granted = item(SetupChecks.evaluate(allGood.copy(wifiFrequencyMhz = 0, locationGranted = true)), CheckId.WIFI_BAND)
        assertEquals(FixAction.NONE, granted.action)
        assertEquals("6 GHz", item(SetupChecks.evaluate(allGood.copy(wifiFrequencyMhz = 6_115)), CheckId.WIFI_BAND).state)
        assertEquals(CheckStatus.INFO, item(SetupChecks.evaluate(allGood.copy(onWifi = false)), CheckId.WIFI_BAND).status)
    }

    @Test
    fun sameNetworkWorksOnHotspots() {
        // Listener on an Android host's hotspot.
        val hotspot = allGood.copy(hostIp = "192.168.43.1", localAddresses = listOf("192.168.43.118" to 24, "10.20.0.7" to 8))
        assertEquals(CheckStatus.OK, item(SetupChecks.evaluate(hotspot), CheckId.SAME_NETWORK).status)
        // This phone is the hotspot (laptop joined it): the hotspot interface counts too.
        val ownAp = allGood.copy(onWifi = false, hostIp = "10.214.5.90", localAddresses = listOf("10.214.5.1" to 24))
        assertEquals(CheckStatus.OK, item(SetupChecks.evaluate(ownAp), CheckId.SAME_NETWORK).status)
        val elsewhere = allGood.copy(hostIp = "192.168.0.10")
        assertEquals(CheckStatus.ATTENTION, item(SetupChecks.evaluate(elsewhere), CheckId.SAME_NETWORK).status)
        assertEquals(CheckStatus.INFO, item(SetupChecks.evaluate(allGood.copy(hostIp = null)), CheckId.SAME_NETWORK).status)
        assertEquals(CheckStatus.INFO, item(SetupChecks.evaluate(allGood.copy(hostIp = "laptop.local")), CheckId.SAME_NETWORK).status)
    }

    @Test
    fun ipv4Math() {
        assertEquals(true, Ipv4.sameSubnet("172.16.5.4", "172.16.200.1", 16))
        assertEquals(false, Ipv4.sameSubnet("172.16.5.4", "172.17.0.1", 16))
        assertEquals(true, Ipv4.sameSubnet("1.2.3.4", "9.9.9.9", 0))
        assertNull(Ipv4.parse("256.1.1.1"))
        assertNull(Ipv4.parse("1.2.3"))
        assertNull(Ipv4.anySameSubnet("1.2.3.4", listOf("127.0.0.1" to 8)))
        assertFalse(Ipv4.anySameSubnet("1.2.3.4", listOf("5.6.7.8" to 24))!!)
    }

    @Test
    fun brandFromBuildFields() {
        assertEquals(Brand.XIAOMI, Brand.of("Xiaomi", "Redmi"))
        assertEquals(Brand.OPPO, Brand.of("realme", "realme"))
        assertEquals(Brand.SAMSUNG, Brand.of("samsung", "samsung"))
        assertEquals(Brand.OTHER, Brand.of("Google", "google"))
        assertEquals(Brand.OTHER, Brand.of(null, null))
    }
}
