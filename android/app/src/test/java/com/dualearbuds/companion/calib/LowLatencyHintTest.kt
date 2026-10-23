package com.dualearbuds.companion.calib

import com.dualearbuds.companion.power.Brand
import com.dualearbuds.companion.setup.CheckId
import com.dualearbuds.companion.setup.CheckStatus
import com.dualearbuds.companion.setup.SetupChecks
import com.dualearbuds.companion.setup.SetupFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLatencyHintTest {

    @Test
    fun onlyAbove150Ms() {
        assertNull(LowLatencyHint.text(null))
        assertNull(LowLatencyHint.text(150.0))
        val t = LowLatencyHint.text(212.4)!!
        assertTrue(t, t.startsWith("These earbuds add 212 ms. If they have a game or low-latency mode"))
        assertTrue(t, t.contains("turn it on and calibrate again."))
        assertTrue(t, t.contains("Prefer AAC or SBC over LDAC (Developer options → Bluetooth audio codec)"))
    }

    @Test
    fun setupCheckShowsItForSlowEarbuds() {
        val f = SetupFacts(
            sdk = 34, notificationsGranted = true, ignoringBatteryOptimizations = true, backgroundRestricted = false,
            powerSave = false, brand = Brand.OTHER, brandStepsDone = true, bluetoothPermission = true, bluetoothOn = true,
            earbudsName = "Buds", calibratedMs = 240.0, onWifi = true, wifiFrequencyMhz = 5_180, locationGranted = true,
            hostIp = null, localAddresses = emptyList()
        )
        val slow = SetupChecks.evaluate(f).first { it.id == CheckId.CALIBRATION }
        assertEquals(CheckStatus.OK, slow.status) // a hint, not a problem to count
        assertTrue(slow.reason.startsWith("These earbuds add 240 ms."))
        val fast = SetupChecks.evaluate(f.copy(calibratedMs = 120.0)).first { it.id == CheckId.CALIBRATION }
        assertTrue(fast.reason.startsWith("Measured for Buds."))
    }
}
