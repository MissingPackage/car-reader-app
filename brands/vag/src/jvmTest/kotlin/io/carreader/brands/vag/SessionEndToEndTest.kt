package io.carreader.brands.vag

import io.carreader.core.brand.BrandRegistry
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.guard.GuardDenied
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.obd2.DtcSource
import io.carreader.core.session.ConnectionState
import io.carreader.core.session.DefaultVehicleSession
import io.carreader.sim.Scenarios
import io.carreader.sim.SimScenario
import io.carreader.sim.SimTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The whole stack as the app wires it: session → gate → ELM driver → simulated adapter and car. */
class SessionEndToEndTest {
    private val audit = mutableListOf<String>()

    private fun session(scenario: SimScenario, enabled: Boolean = false, confirm: Boolean = true) = DefaultVehicleSession(
        transport = SimTransport(scenario),
        brands = BrandRegistry(listOf(VagBrandModule())),
        policy = ClearOnlyPolicy { enabled },
        confirmation = { _, _ -> confirm },
        audit = { audit += it.outcome },
        traffic = RingTrafficLog(clock = { 0L }),
        clock = { 0L },
    )

    @Test
    fun passatPhaseOneAndTwo() = runTest {
        val s = session(Scenarios.passatB8())
        assertTrue(s.connect() is ConnectionState.Connected)
        val summary = s.identify()
        assertEquals("WVWZZZ3CZGE512345", summary.identification.vin)
        assertEquals("vag", summary.brand?.id)
        assertTrue(s.readAllEmissionDtcs().any { it.code == "P0401" })
        val pids = s.supportedPids()
        assertTrue(pids.any { it.pid == 0x0C })
        assertTrue(s.liveData(pids.take(3), 100).first().isNotEmpty())
        assertTrue(s.readiness() != null)

        val reports = s.scanEcus().toList()
        val present = reports.filter { it.present }.map { it.ecu.code }
        assertTrue("01" in present && "19" in present && "15" in present, "present: $present")
        assertTrue(reports.first { it.ecu.code == "19" }.dtcs.any { it.code == "U0121" })
        assertTrue(reports.first { it.ecu.code == "15" }.dtcs.any { it.code == "B1001" })
        assertTrue(reports.first { it.ecu.code == "01" }.identification.isNotEmpty())
        assertTrue(s.scanNotes(reports).none { "TP2.0" in it }, "MQB car must not get the TP2.0 note")

        val engine = reports.first { it.ecu.code == "01" }.ecu
        val items = s.brandDataItems(engine)
        assertTrue(items.isNotEmpty())
        val values = s.readBrandData(engine, items.take(40))
        assertTrue(values.isNotEmpty(), "no VAG engine value decoded from the simulator")
    }

    @Test
    fun clearsBlockedByDefaultAndWorkWhenEnabled() = runTest {
        val off = session(Scenarios.passatB8())
        off.connect(); off.identify()
        assertFailsWith<GuardDenied> { off.clearEmissionDtcs() }
        assertTrue(off.readDtcs(DtcSource.STORED).isNotEmpty())

        val running = session(Scenarios.passatB8(), enabled = true)
        running.connect(); running.identify()
        val denied = assertFailsWith<GuardDenied> { running.clearEmissionDtcs() }
        assertTrue(denied.reasons.any { "Engine running" in it })

        val car = Scenarios.passatB8().also { it.vehicle.engineRunning = false }
        val on = session(car, enabled = true)
        on.connect(); on.identify()
        assertTrue(on.clearEmissionDtcs())
        assertTrue(on.readDtcs(DtcSource.STORED).isEmpty())
        val gateway = on.scanEcus().toList().first { it.ecu.code == "19" }.ecu
        on.clearEcuDtcs(gateway)
        assertTrue(on.scanEcus().toList().first { it.ecu.code == "19" }.dtcs.isEmpty())
        assertTrue(audit.count { it == "executed" } == 2, "audit: $audit")
    }

    @Test
    fun userCancelBlocksClear() = runTest {
        val s = session(Scenarios.passatB8().also { it.vehicle.engineRunning = false }, enabled = true, confirm = false)
        s.connect(); s.identify()
        assertFailsWith<GuardDenied> { s.clearEmissionDtcs() }
        assertTrue(s.readDtcs(DtcSource.STORED).isNotEmpty())
    }

    @Test
    fun audiQ5Scan() = runTest {
        val s = session(Scenarios.audiQ5())
        s.connect()
        assertEquals("vag", s.identify().brand?.id)
        val reports = s.scanEcus().toList()
        assertTrue(reports.count { it.present } >= 5, reports.joinToString { "${it.ecu.code}:${it.present}:${it.error}" })
    }

    @Test
    fun nonVagCarHasNoBrandModuleAndClonesAreNotCertified() = runTest {
        val s = session(Scenarios.cloneAdapter())
        val state = s.connect() as ConnectionState.Connected
        assertFalse(state.adapter.certified)
        assertEquals(null, s.identify().brand)
        assertTrue(s.scanEcus().toList().isEmpty())
    }

    @Test
    fun legacyKLineCar() = runTest {
        val s = session(Scenarios.kLineLegacy())
        s.connect()
        assertEquals("W0L0TGF4835012345", s.identify().identification.vin)
        assertEquals(4, s.readDtcs(DtcSource.STORED).size)
    }
}
