package io.carreader.brands.stellantis

import io.carreader.brands.vag.VagBrandModule
import io.carreader.core.brand.BrandRegistry
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.session.ConnectionState
import io.carreader.core.session.DefaultVehicleSession
import io.carreader.sim.Scenarios
import io.carreader.sim.SimScenario
import io.carreader.sim.SimTransport
import io.carreader.sim.StellantisScenarios
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The whole stack as the app wires it, over simulated Stellantis cars. */
class StellantisSessionTest {
    private fun session(scenario: SimScenario) = DefaultVehicleSession(
        transport = SimTransport(scenario),
        brands = BrandRegistry(listOf(VagBrandModule(), StellantisBrandModule())),
        policy = ClearOnlyPolicy { false },
        confirmation = { _, _ -> false },
        audit = { },
        traffic = RingTrafficLog(clock = { 0L }),
        clock = { 0L },
    )

    @Test
    fun peugeot308PsaPlatform() = runTest {
        val scenario = StellantisScenarios.peugeot308()
        val s = session(scenario)
        assertTrue(s.connect() is ConnectionState.Connected)
        val summary = s.identify()
        assertEquals("stellantis", summary.brand?.id)
        assertEquals(false, summary.context.extendedCan)

        val reports = s.scanEcus().toList()
        val present = reports.filter { it.present }.map { it.ecu.code }
        assertEquals(listOf("6A8", "752", "6A9", "6B5"), present)
        assertTrue(reports.none { it.ecu.address.extendedId }, "a PSA car must not be probed on 29-bit ids")
        val engine = reports.first { it.ecu.code == "6A8" }
        assertTrue(engine.dtcs.any { it.code == "P0420" })
        assertTrue(engine.identification.any { it.first == "Part number" && it.second == "9832451780" })
        assertTrue(reports.first { it.ecu.code == "752" }.dtcs.any { it.code == "B1A00" })
        assertTrue(s.scanNotes(reports).isEmpty(), "notes: ${s.scanNotes(reports)}")

        val items = s.brandDataItems(engine.ecu)
        val mileage = items.first { it.did == 0xD49C }
        val values = s.readBrandData(engine.ecu, listOf(mileage) + items.filter { it.did == 0xD402 || it.did == 0xD415 })
        val km = values.first { it.key == mileage.key }
        assertEquals(scenario.vehicle.odometerKm.toDouble(), km.value)
        assertEquals("km", km.unit)
        assertTrue(values.size >= 3, "decoded: $values")

        val gearbox = reports.first { it.ecu.code == "6A9" }.ecu
        val gearboxOdo = s.brandDataItems(gearbox).first { it.did == 0xD802 }
        assertEquals(scenario.vehicle.odometerKm.toDouble(), s.readBrandData(gearbox, listOf(gearboxOdo)).single().value)
    }

    @Test
    fun fiat500xFcaPlatformOn29Bit() = runTest {
        val scenario = StellantisScenarios.fiat500x()
        val s = session(scenario)
        assertTrue(s.connect() is ConnectionState.Connected)
        val summary = s.identify()
        assertEquals("stellantis", summary.brand?.id)
        assertTrue(summary.context.extendedCan)

        val reports = s.scanEcus().toList()
        assertTrue(reports.all { it.ecu.address.extendedId }, "a 29-bit car is probed on 29-bit ids only")
        val present = reports.filter { it.present }.map { it.ecu.code }
        assertEquals(listOf("10", "18", "40"), present)
        assertTrue(reports.first { it.ecu.code == "10" }.dtcs.any { it.code == "P2002" })
        assertTrue(reports.first { it.ecu.code == "40" }.dtcs.any { it.code == "B1004" })
        assertTrue(s.scanNotes(reports).isEmpty())

        // RPM and battery voltage need the extended session: the base opens it.
        val engine = reports.first { it.ecu.code == "10" }.ecu
        val items = s.brandDataItems(engine).filter { it.did in setOf(0x1000, 0x1003, 0x1955) }
        val values = s.readBrandData(engine, items)
        assertEquals(setOf(0x1000, 0x1003, 0x1955), items.filter { i -> values.any { it.key == i.key } }.map { it.did }.toSet(), "decoded: $values")

        // Tables disagree on how DID 1004 is encoded: every variant is kept and named after its model.
        val bcm = reports.first { it.ecu.code == "40" }.ecu
        val variants = s.brandDataItems(bcm).filter { it.did == 0x1004 }
        assertTrue(variants.size > 1 && variants.all { it.name.endsWith(")") }, "variants: ${variants.map { it.name }}")
        val volts = s.readBrandData(bcm, variants).first { it.name.endsWith("(Fiat 500X)") }
        assertEquals(scenario.signals!!.moduleVolts, assertNotNull(volts.value), 0.051)
    }

    @Test
    fun accessDeniedIsReportedAsSecureGateway() = runTest {
        val s = session(StellantisScenarios.fiat500x(secureGateway = true))
        s.connect(); s.identify()
        val reports = s.scanEcus().toList()
        val refused = reports.filter { it.accessDenied }.map { it.ecu.code }
        assertEquals(listOf("40"), refused)
        val bcmReport = reports.first { it.ecu.code == "40" }
        assertTrue(bcmReport.present && bcmReport.identification.any { it.first == "Part number" }, "values read before the refusal stay")
        assertEquals(listOf(StellantisBrandModule.GATEWAY_DENIED_NOTE), s.scanNotes(reports))
        val bcm = reports.first { it.ecu.code == "40" }.ecu
        assertTrue(s.readBrandData(bcm, s.brandDataItems(bcm).filter { it.did == 0x1004 }).isEmpty())
    }

    @Test
    fun silentBrandUnitsRaiseTheGatewaySuspicion() = runTest {
        val s = session(StellantisScenarios.citroenGatewayBlocked())
        s.connect()
        assertEquals("stellantis", s.identify().brand?.id)
        val reports = s.scanEcus().toList()
        assertTrue(reports.none { it.present })
        assertEquals(listOf(StellantisBrandModule.GATEWAY_SILENT_NOTE), s.scanNotes(reports))
    }

    @Test
    fun europeanFiatOnElevenBitIsProbedAsPsaWithoutBlamingTheGateway() = runTest {
        val s = session(Scenarios.genericPetrol())
        s.connect()
        val summary = s.identify()
        assertEquals("stellantis", summary.brand?.id)
        val reports = s.scanEcus().toList()
        assertTrue(reports.all { it.ecu.address.requestId in 0x6A0..0x7FF && it.ecu.code != "7E0" }, "codes: ${reports.map { it.ecu.code }}")
        // An older FCA-platform car: its units are unknown, so the silence is not blamed on a gateway alone.
        assertEquals(listOf(StellantisBrandModule.UNKNOWN_UNITS_NOTE), s.scanNotes(reports))
    }

    @Test
    fun gmEraOpelIsNotClaimed() = runTest {
        val s = session(Scenarios.kLineLegacy())
        s.connect()
        assertEquals(null, s.identify().brand)
    }
}
