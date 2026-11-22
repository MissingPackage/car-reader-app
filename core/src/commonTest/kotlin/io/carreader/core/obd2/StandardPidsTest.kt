package io.carreader.core.obd2

import io.carreader.core.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandardPidsTest {
    private fun decode(pid: Int, data: String): LiveValue = assertNotNull(StandardPids.byPid(pid)).decode(data.hexToBytes())

    private fun assertValue(expected: Double, text: String, v: LiveValue) {
        assertEquals(expected, assertNotNull(v.value), 1e-9, "value of ${v.key}")
        assertEquals(text, v.text, "text of ${v.key}")
    }

    @Test
    fun scaledFormulas() {
        assertValue(1726.0, "1726", decode(0x0C, "1A F8"))
        assertValue(83.0, "83", decode(0x05, "7B"))
        assertValue(-40.0, "-40", decode(0x0F, "00"))
        assertValue(0.0, "0.0", decode(0x06, "80"))
        assertValue(-100.0, "-100.0", decode(0x07, "00"))
        assertValue(99.21875, "99.2", decode(0x08, "FF"))
        assertValue(8.0, "8.0", decode(0x0E, "90"))
        assertValue(-64.0, "-64.0", decode(0x0E, "00"))
        assertValue(5.0, "5.00", decode(0x10, "01 F4"))
        assertValue(100.0, "100.0", decode(0x11, "FF"))
        assertValue(100.0, "100.0", decode(0x04, "FF"))
        assertValue(300.0, "300", decode(0x0A, "64"))
        assertValue(50.0, "50", decode(0x0D, "32"))
        assertValue(14.17, "14.17", decode(0x42, "37 5A"))
        assertValue(1.0, "1.000", decode(0x44, "80 00"))
        assertValue(1.0, "1.000", decode(0x24, "80 00 12 34"))
        assertValue(0.5, "0.500", decode(0x2B, "40 00 00 00"))
        assertValue(380.0, "380.0", decode(0x3C, "10 68"))
        assertValue(5.0, "5.00", decode(0x5E, "00 64"))
        assertValue(50000.0, "50000", decode(0x23, "13 88"))
        assertValue(0.0, "0.0", decode(0x2D, "80"))
        assertValue(12345.0, "12345", decode(0x31, "30 39"))
        assertValue(65535.0, "65535", decode(0x1F, "FF FF"))
        assertValue(102.0, "102", decode(0x33, "66"))
        assertValue(90.0, "90", decode(0x5C, "82"))
    }

    @Test
    fun enumsAreText() {
        val fuel = decode(0x51, "04")
        assertNull(fuel.value)
        assertEquals("Diesel", fuel.text)
        assertEquals("EOBD (Europe)", decode(0x1C, "06").text)
        assertEquals("Unknown (200)", decode(0x1C, "C8").text)
        assertEquals("Closed loop", decode(0x03, "02 00").text)
    }

    @Test
    fun monitorStatusIsExposedAsText() {
        val v = decode(0x01, "83 07 65 21")
        assertNull(v.value)
        assertTrue(v.text.startsWith("MIL on, 3 DTC"), v.text)
    }

    @Test
    fun registryIsConsistent() {
        val all = StandardPids.all
        assertEquals(all.size, all.map { it.pid }.toSet().size, "duplicate PID numbers")
        for (p in listOf(0x01, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
            0x1C, 0x1F, 0x21, 0x23, 0x24, 0x2B, 0x2C, 0x2D, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x42, 0x43, 0x44, 0x45,
            0x46, 0x49, 0x4C, 0x51, 0x5C, 0x5E)) {
            assertNotNull(StandardPids.byPid(p), "PID ${p.toString(16)} missing")
        }
        assertEquals("01.0C", StandardPids.engineRpm.key)
        assertNull(StandardPids.byPid(0x20))
    }

    @Test
    fun formatDecimalEdgeCases() {
        assertEquals("0.0", formatDecimal(-0.04, 1))
        assertEquals("-7.5", formatDecimal(-7.5, 1))
        assertEquals("0.05", formatDecimal(0.05, 2))
        assertEquals("10", formatDecimal(9.6, 0))
    }
}
