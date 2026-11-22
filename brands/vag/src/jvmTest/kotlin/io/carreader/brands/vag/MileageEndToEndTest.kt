package io.carreader.brands.vag

import io.carreader.core.brand.BrandRegistry
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.mileage.CounterReading
import io.carreader.core.mileage.CounterRole
import io.carreader.core.mileage.MileageAnalyzer
import io.carreader.core.mileage.MileageFinding
import io.carreader.core.mileage.MileageVerdict
import io.carreader.core.mileage.ReadingStatus
import io.carreader.core.session.DefaultVehicleSession
import io.carreader.sim.Scenarios
import io.carreader.sim.SimScenario
import io.carreader.sim.SimTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mileage check through the whole stack: session → gate → ELM driver → simulated adapter and VW group cars. */
class MileageEndToEndTest {
    private fun session(scenario: SimScenario) = DefaultVehicleSession(
        transport = SimTransport(scenario),
        brands = BrandRegistry(listOf(VagBrandModule())),
        policy = ClearOnlyPolicy { false },
        confirmation = { _, _ -> false },
        audit = {},
        traffic = RingTrafficLog(clock = { 0L }),
        clock = { 0L },
    )

    private suspend fun read(scenario: SimScenario): Pair<List<CounterReading>, Int> {
        val s = session(scenario)
        s.connect()
        s.identify()
        val expected = s.counterCount()
        return s.readCounters().toList() to expected
    }

    private fun List<CounterReading>.byId(id: String) = first { it.source.id == id }

    @Test
    fun passatValuesAgree() = runTest {
        val (readings, expected) = read(Scenarios.passatB8())
        assertTrue(readings.size >= expected, "every counter yields a reading")
        assertEquals(87_412.38, readings.byId("vag.PASSAT_ODO_MILEAGE_HR").value!!, 1e-6)
        assertEquals(87_412.0, readings.byId("vag.PASSAT_ODO_DASH").value) // extended-session DID
        assertEquals(87_409.0, readings.byId("vag.PASSAT_ODO").value)
        assertEquals(87_412.0, readings.byId("vag.PASSAT_ODO_V1").value)
        assertEquals(87_412.0, readings.byId("vag.PASSAT_MQB_TCM_ODO_2").value) // little endian
        assertEquals(71_350.0, readings.byId("vag.PASSAT_PQ35_CLUTCH_1_ADAPT_KISS_POINT_PART_1_ODO_READING").value)
        assertEquals(87_412.0 - 1_900, readings.byId("vag.PASSAT_TPMS_ODO_RDG_AT_LAST_ADAPT").value)
        assertEquals(ReadingStatus.OUT_OF_RANGE, readings.byId("vag.PASSAT_CNT_STEER_SUPPORT_ODO_RDG_OF_LAST_ACT").status)
        assertEquals(2_050.0, readings.byId("vag.PASSAT_DRIVE_TIME").value)
        // Standard OBD-II counters from the engine.
        val obdOdo = readings.first { it.source.id == "obd.A6" }
        assertEquals(87_412.0, obdOdo.value!!, 1e-6)
        assertTrue(obdOdo.module.contains("7E8"), obdOdo.module)
        assertEquals(2_310.0, readings.first { it.source.id == "obd.7F" }.value!!, 1e-6)

        val check = MileageAnalyzer().analyze(readings)
        assertEquals(MileageVerdict.CONSISTENT, check.verdict, "findings: ${check.findings}")
        val avg = assertNotNull(check.averageSpeed)
        assertEquals("vag.PASSAT_DRIVE_TIME", avg.hoursSource.source.id)
        assertEquals(87_412.38 / 2_050.0, avg.kmh, 1e-6)
    }

    @Test
    fun rolledBackPassatIsFlagged() = runTest {
        val (readings, _) = read(Scenarios.passatB8(clusterRollbackKm = 60_000))
        val check = MileageAnalyzer().analyze(readings)
        assertEquals(MileageVerdict.SUSPICIOUS, check.verdict)
        assertEquals(27_412.38, check.dashboardKm!!, 1e-6)
        val above = check.findings.filterIsInstance<MileageFinding.TotalAboveDashboard>().map { it.reading.source.id }.toSet()
        assertTrue({ "vag.PASSAT_ODO" in above && "vag.PASSAT_ODO_V1" in above && "obd.A6" in above }(), "above: $above")
        assertTrue(check.findings.any { it is MileageFinding.StampAboveDashboard && it.reading.source.role == CounterRole.EVENT_STAMP })
        assertTrue(assertNotNull(check.averageSpeed).kmh < 15.0)
    }

    @Test
    fun q5UsesItsOwnTableAndReportsWhatIsMissing() = runTest {
        val (readings, _) = read(Scenarios.audiQ5())
        assertTrue(readings.none { it.source.id.startsWith("vag.PASSAT_") }, "Q5 must not get Passat rows")
        assertEquals(54_210.45, readings.byId("vag.Q5_ODO_MILEAGE_HR").value!!, 1e-6)
        assertEquals(1_204.0, readings.byId("vag.Q5_DRIVE_TIME").value)
        // The simulated Q5 engine does not support PID A6: shown as not supported, not guessed.
        assertEquals(ReadingStatus.NOT_SUPPORTED, readings.first { it.source.id == "obd.A6" }.status)
        // The chain-elongation inlet DID is not in this car: the unit answers with a negative response.
        assertEquals(ReadingStatus.NOT_SUPPORTED, readings.byId("vag.Q5_CHAIN_ELONGATION_INLET").status)
        assertEquals(MileageVerdict.CONSISTENT, MileageAnalyzer().analyze(readings).verdict)
    }

    @Test
    fun absentModuleIsProbedOnce() = runTest {
        // Generic Fiat with the VAG module forced: no UDS unit answers.
        val s = DefaultVehicleSession(
            transport = SimTransport(Scenarios.genericPetrol()),
            brands = BrandRegistry(listOf(VagBrandModule())),
            policy = ClearOnlyPolicy { false },
            confirmation = { _, _ -> false },
            audit = {},
            traffic = RingTrafficLog(clock = { 0L }),
            clock = { 0L },
            forcedBrandId = "vag",
        )
        s.connect(); s.identify()
        val readings = s.readCounters().toList()
        val brand = readings.filter { it.source.id.startsWith("vag.") }
        assertTrue(brand.isNotEmpty())
        assertTrue(brand.all { it.status == ReadingStatus.MODULE_ABSENT || it.status == ReadingStatus.NOT_SUPPORTED }, "$brand")
        assertEquals(MileageVerdict.NOT_ENOUGH_DATA, MileageAnalyzer().analyze(readings).verdict)
        assertTrue(s.counterNotes().isNotEmpty())
    }
}
