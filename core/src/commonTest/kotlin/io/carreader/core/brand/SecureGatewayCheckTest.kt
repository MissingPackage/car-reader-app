package io.carreader.core.brand

import io.carreader.core.link.EcuAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class SecureGatewayCheckTest {
    private fun r(id: Int, present: Boolean, denied: Boolean = false) =
        EcuReport(EcuDescriptor(id.toString(16), "u", EcuAddress(id, id + 8)), present, accessDenied = denied)

    private val open = setOf(0x7E0)

    @Test
    fun deniedWins() {
        assertEquals(GatewayFinding.ACCESS_DENIED, SecureGatewayCheck.assess(listOf(r(0x7E0, true), r(0x700, true, denied = true)), open, true))
        // A refusal on an always-open emission unit is that unit's own lock, not the gateway.
        assertEquals(GatewayFinding.NONE, SecureGatewayCheck.assess(listOf(r(0x7E0, true, denied = true), r(0x700, true)), open, true))
    }

    @Test
    fun onlyOpenUnitsAnswered() {
        val reports = listOf(r(0x7E0, true), r(0x700, false), r(0x701, false), r(0x702, false))
        assertEquals(GatewayFinding.ONLY_OPEN_UNITS_ANSWERED, SecureGatewayCheck.assess(reports, open, true))
        assertEquals(GatewayFinding.ONLY_OPEN_UNITS_ANSWERED, SecureGatewayCheck.assess(reports.drop(1), open, true))
        // Nothing at all and no OBD answer either: the car is just not talking, not a gateway clue.
        assertEquals(GatewayFinding.NONE, SecureGatewayCheck.assess(reports.drop(1), open, false))
    }

    @Test
    fun tooFewOrSomeAnsweredIsNone() {
        assertEquals(GatewayFinding.NONE, SecureGatewayCheck.assess(listOf(r(0x7E0, true), r(0x700, false), r(0x701, false)), open, true))
        assertEquals(GatewayFinding.NONE, SecureGatewayCheck.assess(listOf(r(0x7E0, true), r(0x700, true), r(0x701, false), r(0x702, false), r(0x703, false)), open, true))
    }
}
