package io.carreader.core.vin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WmiTableTest {
    private fun group(vin: String) = WmiTable.resolve(Vin(vin))?.groupId

    @Test
    fun vagBrandsResolveToVag() {
        for (wmi in listOf("WVW", "WV1", "WV2", "WV3", "1VW", "3VW", "WAU", "WA1", "WUA", "TRU", "TMB", "VSS")) {
            assertEquals("vag", group("${wmi}ZZZ3CZJE123456"), wmi)
        }
        assertEquals("Skoda", WmiTable.resolve(Vin("TMBJJ7NE8L0123456"))?.brand)
    }

    @Test
    fun otherGroups() {
        assertEquals("porsche", group("WP0ZZZ99ZTS392124"))
        assertEquals("stellantis", group("ZFA31200000123456"))
        assertEquals("stellantis", group("VF3ABCDEF12345678"))
        assertEquals("stellantis", group("W0LPD8EJ1C1234567"))
        assertEquals("ferrari", group("ZFFAB12C000123456"))
        assertEquals("renault", group("UU1ABCDEF12345678"))
        assertEquals("bmw", group("WMWXP7104K2A12345"))
        assertEquals("mercedes", group("W1K1770841J123456"))
        assertEquals("tesla", group("XP7YGCEK3PB123456"))
        assertEquals("hyundai-kia", group("U5YH2G1ABCD123456"))
    }

    @Test
    fun prefixFallbackAndExactOverride() {
        assertEquals("Toyota", WmiTable.resolve(Vin("JTDKB20U403123456"))?.brand)
        assertEquals("Lexus", WmiTable.resolve(Vin("JTHBA30G455123456"))?.brand)
        assertNull(WmiTable.resolve(Vin("ABC12345678901234")))
        assertNotNull(WmiTable.resolveWmi("wvw"))
    }

    @Test
    fun modelYearIsAHint() {
        val vin = Vin("WVWZZZ3CZJE123456") // position 10 = J
        assertEquals(listOf(1988, 2018), ModelYear.candidates(vin))
        assertEquals(2018, ModelYear.hint(vin, currentYear = 2026))
        assertEquals(1988, ModelYear.hint(vin, currentYear = 2010))
        assertEquals(listOf(2000, 2030), ModelYear.candidates(Vin("WVWZZZ3CZYE123456")))
        assertEquals(2001, ModelYear.hint(Vin("WVWZZZ3CZ1E123456"), currentYear = 2026))
        assertEquals(2027, ModelYear.hint(Vin("WVWZZZ3CZVE123456"), currentYear = 2026))
        assertEquals(emptyList(), ModelYear.candidates(Vin("WVWZZZ3CZ0E123456")))
    }
}
