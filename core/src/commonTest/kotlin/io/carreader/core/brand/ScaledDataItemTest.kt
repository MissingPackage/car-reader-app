package io.carreader.core.brand

import io.carreader.core.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScaledDataItemTest {
    private fun item(fmt: SignalFormat) = ScaledDataItem("K", "Name", 0x1234, fmt)

    @Test
    fun unsignedMultiByteWithDivAndAdd() {
        // OBDb Q5_INSIDE_T: len 16, div 10, add -50, celsius
        val v = item(SignalFormat(bitLength = 16, div = 10.0, add = -50.0, min = -50.0, max = 6503.0, unit = "celsius")).decode("02 EE".hexToBytes())
        assertEquals(25.0, v.value)
        assertEquals("°C", v.unit)
        assertEquals("25.0 °C", v.text)
    }

    @Test
    fun signedValue() {
        val f = SignalFormat(bitLength = 16, signed = true, div = 10.0, min = -3276.8, max = 3276.7, unit = "newtonMeters")
        assertEquals(-12.3, item(f).decode("FF 85".hexToBytes()).value!!, 1e-9)
    }

    @Test
    fun bitOffsetAndSingleBit() {
        // bix 40 len 8 (OBDb tire pressure layout): sixth byte
        val tp = SignalFormat(bitOffset = 40, bitLength = 8, div = 40.0, max = 6.375, unit = "bars")
        assertEquals(2.5, item(tp).decode("00 00 00 00 00 64 00".hexToBytes()).value)
        // one bit at offset 3 (MSB-first)
        val flag = SignalFormat(bitOffset = 3, bitLength = 1, max = 1.0, unit = "offon")
        assertEquals("On", item(flag).decode("10".hexToBytes()).text)
        assertEquals("Off", item(flag).decode("EF".hexToBytes()).text)
    }

    @Test
    fun crossByteBitField() {
        // 12 bits starting at bit 4: 0x0ABC from "0A BC" shifted
        val f = SignalFormat(bitOffset = 4, bitLength = 12, max = 4095.0)
        assertEquals(0xABC.toDouble(), item(f).decode("FA BC".hexToBytes()).value)
    }

    @Test
    fun littleEndianBytes() {
        val f = SignalFormat(bitOffset = 8, bitLength = 16, littleEndian = true, max = 65535.0)
        assertEquals(0x1234.toDouble(), item(f).decode("FF 34 12".hexToBytes()).value)
    }

    @Test
    fun clampNullBoundsAndMap() {
        val clamp = SignalFormat(bitLength = 8, max = 100.0, unit = "percent")
        assertEquals(100.0, item(clamp).decode("FF".hexToBytes()).value)
        val nul = SignalFormat(bitLength = 16, div = 16.0, add = -50.0, min = -50.0, max = 4045.0, nullMax = 4045.0)
        val v = item(nul).decode("FF FF".hexToBytes())
        assertNull(v.value)
        assertEquals("not available", v.text)
        val gear = SignalFormat(bitLength = 8, enumMap = mapOf(1L to "Reverse", 3L to "First gear"))
        assertEquals("First gear", item(gear).decode("03".hexToBytes()).text)
        assertEquals("Unknown (9)", item(gear).decode("09".hexToBytes()).text)
    }

    @Test
    fun tooShortThrows() {
        assertFailsWith<IllegalArgumentException> { item(SignalFormat(bitLength = 16, max = 1.0)).decode("01".hexToBytes()) }
    }

    @Test
    fun numberFormatting() {
        assertEquals("1234", formatNumber(1234.4, 0))
        assertEquals("-0.05", formatNumber(-0.05, 2))
        assertEquals("0.000", formatNumber(-0.0001, 3))
        assertEquals(3, SignalFormat(bitLength = 16, div = 1000.0).decimals)
        assertEquals(1, SignalFormat(bitLength = 16, mul = 0.1).decimals)
        assertEquals(0, SignalFormat(bitLength = 16, mul = 2.0).decimals)
    }
}
