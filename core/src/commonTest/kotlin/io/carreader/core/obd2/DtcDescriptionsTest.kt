package io.carreader.core.obd2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DtcDescriptionsTest {
    @Test
    fun commonCodesHaveText() {
        val codes = listOf("P0300", "P0312", "P0130", "P0141", "P0167", "P0171", "P0175", "P0400", "P0409",
            "P0420", "P0430", "P0440", "P0457", "P0500", "P0507", "P0100", "P0123", "P0115", "P0128", "P0335",
            "P0349", "P0325", "P0334", "P0380", "P0670", "P0684", "P2002", "P2452", "P2463", "P20EE", "P2BAD",
            "U0100", "U0101", "U0121", "U0140", "U0155")
        for (c in codes) assertNotNull(DtcDescriptions.describe(c), "missing $c")
        assertEquals("Cylinder 4 misfire detected", DtcDescriptions.describe("P0304"))
        assertEquals("Oxygen sensor heater circuit (bank 1 sensor 2)", DtcDescriptions.describe("P0141"))
        assertEquals("Glow plug circuit, cylinder 3", DtcDescriptions.describe("p0673"))
        assertEquals("Ignition coil \"B\" primary / secondary circuit", DtcDescriptions.describe("P0352"))
        assertNull(DtcDescriptions.describe("P1234"))
    }

    @Test
    fun categories() {
        assertEquals("Powertrain – generic (SAE): ignition system or misfire", DtcDescriptions.categoryOf("P0399"))
        assertEquals("Powertrain – manufacturer-specific", DtcDescriptions.categoryOf("P1234"))
        assertEquals("Powertrain – generic (SAE)", DtcDescriptions.categoryOf("P2ABC"))
        assertEquals("Powertrain – manufacturer-specific", DtcDescriptions.categoryOf("P3000"))
        assertEquals("Network – generic (SAE)", DtcDescriptions.categoryOf("U0999"))
        assertEquals("Body – manufacturer-specific", DtcDescriptions.categoryOf("B1001"))
        assertEquals("Chassis – generic (SAE)", DtcDescriptions.categoryOf("C0035"))
        assertNull(DtcDescriptions.categoryOf("X0000"))
        assertEquals("Powertrain – manufacturer-specific", DtcDescriptions.describeOrCategory("P1234"))
    }
}
