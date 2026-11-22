package io.carreader.brands.stellantis

import io.carreader.brands.stellantis.data.ObdbFiat500
import io.carreader.brands.stellantis.data.ObdbFiat500X
import io.carreader.brands.stellantis.data.ObdbPeugeot208
import io.carreader.brands.stellantis.data.ObdbPeugeot308
import io.carreader.brands.stellantis.data.ObdbPeugeot3008
import io.carreader.brands.stellantis.data.ObdbPeugeotE208
import io.carreader.core.brand.BrandRegistry
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.VehicleContext
import io.carreader.core.brand.obdb.ObdbSignalTable
import io.carreader.core.vin.Vin
import io.carreader.core.vin.WmiTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StellantisBrandModuleTest {
    private val module = StellantisBrandModule()

    private fun car(vin: String, extended: Boolean = false, can: Boolean = true): VehicleContext {
        val v = Vin(vin)
        return VehicleContext(v, WmiTable.resolve(v), canBus = can, extendedCan = extended)
    }

    @Test
    fun claimsEveryStellantisBrandButGmEraOpel() {
        for (vin in listOf("ZFA3340000P712345", "ZAR95200000123456", "ZLA31200000123456", "ZACNJABB0LPL12345", "1C4HJXEN5LW123456",
            "VF3LBHNSZNS123456", "VR7BCYHZRPJ123456", "VR1J45GGUKY123456", "VXKUPHNKKL4394011", "W0VBD6EA5MG123456")) {
            assertTrue(module.supports(car(vin)), vin)
        }
        assertFalse(module.supports(car("W0L0TGF4835012345")))
        assertFalse(module.supports(car("WVWZZZ3CZGE512345")))
        assertEquals("stellantis", BrandRegistry(listOf(module)).forVehicle(car("VF3LBHNSZNS123456"))?.id)
    }

    @Test
    fun platformFollowsBusAndVin() {
        assertEquals(listOf(StellantisPlatformId.PSA), module.platformsFor(car("VF3LBHNSZNS123456")).map { it.id })
        assertEquals(listOf(StellantisPlatformId.FCA), module.platformsFor(car("ZFA3340000P712345", extended = true)).map { it.id })
        // A European FCA brand on 11-bit CAN: a PSA-based model (Jeep Avenger, Fiat 600), not certain.
        assertEquals(listOf(StellantisPlatformId.PSA), module.platformsFor(car("ZACNJABB0LPL12345")).map { it.id })
        assertFalse(module.choosePlatforms(car("ZACNJABB0LPL12345")).certain)
        // North America: FCA 11-bit units only, never PSA ids on that bus.
        for (vin in listOf("1C4HJXEN5LW123456", "1C6JJTBG0LL123456", "3C4NJDCB5LT123456")) {
            val choice = module.choosePlatforms(car(vin))
            assertEquals(listOf(StellantisPlatformId.FCA), choice.platforms.map { it.id }, vin)
            assertTrue(choice.certain && module.ecus(car(vin)).none { it.address.requestId in 0x6A0..0x6BF || it.address.requestId == 0x752 })
            assertTrue(module.supports(car(vin)), vin)
        }
        assertTrue(module.ecus(car("ZFA3340000P712345", can = false)).isEmpty())
        assertEquals(listOf(StellantisBrandModule.NO_CAN_NOTE), module.scanNotes(car("ZFA3340000P712345", can = false), emptyList()))
    }

    @Test
    fun ecuCatalogsMatchTheBus() {
        val psa = module.ecus(car("VF3LBHNSZNS123456"))
        assertTrue(psa.all { !it.address.extendedId })
        assertEquals(0x652, PsaPlatform.BODY_COMPUTER.address.responseId)
        val fca29 = module.ecus(car("ZFA3340000P712345", extended = true))
        assertTrue(fca29.all { it.address.extendedId })
        assertEquals(0x18DAF110, fca29.first().address.responseId)
        assertEquals(fca29.size, fca29.map { it.code }.toSet().size)
    }

    @Test
    fun bundledTablesParseAndOnlyUseKnownUnits() {
        for (table in listOf(ObdbPeugeot208, ObdbPeugeot308, ObdbPeugeot3008, ObdbPeugeotE208, ObdbFiat500, ObdbFiat500X)) {
            assertTrue(ObdbSignalTable.parse(table).isNotEmpty(), table.source)
        }
        val known = (PsaPlatform.all + FcaPlatform.extended + FcaPlatform.standard).map { it.address.requestId to it.address.responseId }.toSet()
        for (s in PsaPlatform.allSignals() + FcaPlatform.allSignals()) {
            assertTrue((s.header to s.responseId) in known, "${s.id}: ${s.header.toString(16)} -> ${s.responseId?.toString(16)} not in the unit catalog")
            assertTrue(s.session == null || s.session == 0x03, "${s.id} needs session ${s.session}")
        }
    }

    @Test
    fun psaMileageIsDecoded() {
        val vehicle = car("VF3LBHNSZNS123456")
        val item = module.dataItems(PsaPlatform.ENGINE, vehicle).first { it.did == 0xD49C }
        assertEquals(41_870.0, item.decode(byteArrayOf(0x00, 0x00, 0xA3.toByte(), 0x8E.toByte())).value)
    }

    @Test
    fun conflictingLayoutsAreNamedAfterTheirModel() {
        val bcm = module.dataItems(FcaPlatform.BODY_COMPUTER_29, car("ZFA3340000P712345", extended = true)).filter { it.did == 0x1004 }
        assertTrue(bcm.size > 1)
        assertTrue(bcm.any { it.name == "Battery voltage, BCM (Fiat 500X)" }, bcm.map { it.name }.toString())
        // A DID every table decodes alike keeps its plain name.
        val engine = module.dataItems(PsaPlatform.ENGINE, car("VF3LBHNSZNS123456")).filter { it.did == 0xD49C }
        assertEquals(listOf("Total mileage"), engine.map { it.name })
    }

    @Test
    fun noVinProbesNothing() {
        val vehicle = VehicleContext(null, null, canBus = true)
        assertTrue(module.ecus(vehicle).isEmpty())
        assertEquals(listOf(StellantisBrandModule.NO_VIN_NOTE), module.scanNotes(vehicle, emptyList()))
    }

    @Test
    fun noGatewayNoteWhenForcedOntoAnotherBrand() {
        val ford = car("WF0XXXGCDX1234567")
        assertFalse(module.supports(ford))
        assertTrue(module.scanNotes(ford, module.ecus(ford).map { EcuReport(it, present = false) }).isEmpty())
    }

    @Test
    fun noGatewayNoteWhenUnitsAnswer() {
        val vehicle = car("VF3LBHNSZNS123456")
        val reports = module.ecus(vehicle).mapIndexed { i, e -> EcuReport(e, present = i < 2) }
        assertTrue(module.scanNotes(vehicle, reports).isEmpty())
        val silent = module.ecus(vehicle).map { EcuReport(it, present = false) }
        assertEquals(listOf(StellantisBrandModule.GATEWAY_SILENT_NOTE), module.scanNotes(vehicle, silent))
        val denied = module.ecus(vehicle).mapIndexed { i, e -> EcuReport(e, present = i == 1, accessDenied = i == 1) }
        assertEquals(listOf(StellantisBrandModule.GATEWAY_DENIED_NOTE), module.scanNotes(vehicle, denied))
    }
}

class StellantisCountersTest {
    private val module = StellantisBrandModule()

    private fun car(vin: String, extended: Boolean = false): VehicleContext {
        val v = Vin(vin)
        return VehicleContext(v, WmiTable.resolve(v), canBus = true, extendedCan = extended)
    }

    @Test
    fun psaTotalsFromEngineGearboxAndElectricUnits() {
        val sources = module.counterCatalog.sources(car("VF3LBHNSZNS123456"))
        assertEquals(listOf("6A8", "6A9", "6B4", "6A2", "6A6"), sources.map { it.ecu!!.code })
        assertTrue(sources.all { it.role == io.carreader.core.mileage.CounterRole.TOTAL })
        val engine = sources.first()
        assertEquals("Total mileage", engine.label)
        assertTrue("Peugeot-208" in engine.provenance && "6A8/D49C" in engine.provenance, engine.provenance)
        assertTrue(StellantisCounters.UNVERIFIED_NOTE in module.counterCatalog.notes(car("VF3LBHNSZNS123456")))
    }

    @Test
    fun northAmericanFcaGetsNoBrandCounter() {
        val vehicle = car("1C4HJXEN5LW123456")
        assertTrue(module.counterCatalog.sources(vehicle).isEmpty())
        assertTrue(StellantisCounters.FCA_NOTE in module.counterCatalog.notes(vehicle))
    }

    @Test
    fun fca29HasNoVerifiedCounterAndSaysSo() {
        val vehicle = car("ZFA3340000P712345", extended = true)
        assertTrue(module.counterCatalog.sources(vehicle).isEmpty())
        assertTrue(StellantisCounters.FCA_NOTE in module.counterCatalog.notes(vehicle))
    }
}
