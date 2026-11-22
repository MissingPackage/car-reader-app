package io.carreader.sim

import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.guard.GatedLink
import io.carreader.core.guard.StandardChecks
import io.carreader.core.guard.WriteGuard
import io.carreader.core.link.elm.ElmLink
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.Obd2Service
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End to end: the OBD-II service over the ELM driver over the simulator. */
class Obd2OverSimTest {
    private suspend fun obd(scenario: SimScenario): Obd2Service {
        val link = ElmLink(SimTransport(scenario))
        link.initialize()
        val guard = WriteGuard(ClearOnlyPolicy { true }, StandardChecks.all(), { _, _ -> true }, { }, { 0L })
        return Obd2Service(GatedLink(link, guard))
    }

    @Test
    fun passatOverCan() = runTest {
        val obd = obd(Scenarios.passatB8())
        assertEquals("WVWZZZ3CZGE512345", obd.identification().vin)
        assertTrue(0x0C in obd.supportedPids())
        assertTrue(obd.readDtcs(DtcSource.STORED).any { it.code == "P0401" })
        val readiness = obd.readiness()!!
        assertTrue(readiness.milOn)
        assertTrue(readiness.compressionIgnition)
        assertEquals("P0401", obd.freezeFrame()?.triggerDtc)
    }

    @Test
    fun legacyCarOverKLine() = runTest {
        val obd = obd(Scenarios.kLineLegacy())
        assertEquals("W0L0TGF4835012345", obd.identification().vin)
        assertEquals(setOf("P0130", "P0300", "P0171", "P0420"), obd.readDtcs(DtcSource.STORED).map { it.code }.toSet())
    }
}
