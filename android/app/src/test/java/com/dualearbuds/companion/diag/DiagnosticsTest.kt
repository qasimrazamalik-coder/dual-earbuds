package com.dualearbuds.companion.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun redactsKeysAndPins() {
        val s = Redact.secrets("GET http://10.0.0.2:8790/ws?k=ABCD2345&client=android&p=1234 {\"p\":\"9999\",\"pin\":\"4321\"}")
        assertFalse(s.contains("ABCD2345"))
        assertFalse(s.contains("1234"))
        assertFalse(s.contains("9999"))
        assertFalse(s.contains("4321"))
        assertTrue(s.contains("client=android"))
    }

    @Test
    fun ringKeepsLastLines() {
        val r = LogRing(3)
        repeat(5) { r.add("line $it") }
        assertEquals(listOf("line 2", "line 3", "line 4"), r.snapshot())
    }

    @Test
    fun reportHasNoSecrets() {
        val text = DiagnosticsReport.build(
            DiagnosticsInput(
                "1.0.0", "14", 34, "Redmi Note 12", "Xiaomi", "PLAYING", "laptop", "3.1.0", 3, "SMART", 60, 55,
                12.0, 2, 5, 3, 1, true, 1234.5, 4.0, -1.2, 180.0, "Buds", "Movie", "Off",
                listOf("connect http://1.2.3.4:8790/?k=QWER2345")
            )
        )
        assertFalse(text.contains("QWER2345"))
        assertTrue(text.contains("Redmi Note 12"))
        assertTrue(text.contains("offset 1234.5 ms"))
        assertTrue(text.contains("Underruns 2"))
    }
}
