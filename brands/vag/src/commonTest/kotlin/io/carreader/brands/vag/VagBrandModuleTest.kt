package io.carreader.brands.vag

import io.carreader.brands.vag.data.ObdbAudiQ5
import io.carreader.brands.vag.data.ObdbSignalTable
import io.carreader.brands.vag.data.ObdbVolkswagenPassat
import io.carreader.core.brand.BrandRegistry
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.ScaledDataItem
import io.carreader.core.brand.VehicleContext
import io.carreader.core.uds.DefaultUdsClient
import io.carreader.core.vin.Manufacturer
import io.carreader.core.vin.Vin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VagBrandModuleTest {
    private val vag = VagBrandModule()
    private val vw = Manufacturer("Volkswagen", "Volkswagen", "vag", "Germany")
    private val passat = VehicleContext(Vin("WVWZZZ3CZJE123456"), vw, canBus = true)
    private val q5 = VehicleContext(Vin("WAUZZZFY1J2012345"), Manufacturer("Audi", "Audi", "vag"), canBus = true)
    private val unknownVag = VehicleContext(Vin("TMBJJ7NE8J0123456"), Manufacturer("Skoda", "Skoda", "vag"), canBus = true)

    @Test
    fun supportsByGroupIdAndWmiFallback() {
        assertTrue(vag.supports(passat))
        assertFalse(vag.supports(VehicleContext(Vin("WVWZZZ3CZJE123456"), Manufacturer("Ford", "Ford", "ford"), canBus = true)))
        assertTrue(vag.supports(VehicleContext(Vin("WAUZZZFY1J2012345"), null, canBus = true)))
        assertFalse(vag.supports(VehicleContext(Vin("JTDKB20U093123456"), null, canBus = true)))
        assertFalse(vag.supports(VehicleContext(null, null, canBus = true)))
        // manual override: the UI asks the registry by id
        assertEquals(vag, BrandRegistry(listOf(vag)).byId("vag"))
    }

    @Test
    fun catalogMatchesContracts() {
        val byCode = vag.ecus(passat).associateBy { it.code }
        assertEquals(0x7E8, byCode.getValue("01").address.responseId)
        assertEquals(0x7E9, byCode.getValue("02").address.responseId)
        val contract = mapOf(
            "03" to (0x713 to 0x77D), "08" to (0x746 to 0x7B0), "09" to (0x70E to 0x778), "13" to (0x757 to 0x7C1),
            "15" to (0x715 to 0x77F), "16" to (0x70C to 0x776), "17" to (0x714 to 0x77E), "19" to (0x710 to 0x77A),
            "42" to (0x74A to 0x7B4), "44" to (0x712 to 0x77C), "52" to (0x74B to 0x7B5), "5F" to (0x773 to 0x7DD),
            "76" to (0x70A to 0x774),
        )
        for ((code, ids) in contract) {
            val a = byCode.getValue(code).address
            assertEquals(ids, a.requestId to a.responseId, code)
        }
        assertEquals("01", vag.ecus(passat).first().code)
    }

    @Test
    fun obdbTablesParseAndMapToKnownEcus() {
        val passatSignals = ObdbSignalTable.parse(ObdbVolkswagenPassat.chunks, "p")
        val q5Signals = ObdbSignalTable.parse(ObdbAudiQ5.chunks, "q")
        assertEquals(ObdbVolkswagenPassat.SIGNAL_COUNT, passatSignals.size)
        assertEquals(ObdbAudiQ5.SIGNAL_COUNT, q5Signals.size)
        val headers = VagEcus.all.map { it.address.requestId }.toSet()
        val unmapped = (passatSignals + q5Signals).map { it.header }.filter { it !in headers }.toSet()
        assertEquals(emptySet(), unmapped, "OBDb headers without a catalog ECU: ${unmapped.map { it.toString(16) }}")
        // every signal decodes a zero-filled answer long enough for its layout
        for (s in passatSignals + q5Signals) {
            val bytes = ByteArray((s.format.bitOffset + s.format.bitLength + 7) / 8)
            s.toDataItem().decode(bytes)
        }
    }

    @Test
    fun dataItemsPerModel() {
        val engine = VagEcus.ENGINE
        val p = vag.dataItems(engine, passat)
        val q = vag.dataItems(engine, q5)
        val any = vag.dataItems(engine, unknownVag)
        assertTrue(p.isNotEmpty() && q.isNotEmpty())
        assertTrue(p.all { it.key.startsWith("PASSAT_") })
        assertTrue(q.all { it.key.startsWith("Q5_") })
        assertTrue(any.size > p.size && any.size > q.size && any.size <= p.size + q.size)
        assertEquals(any.size, any.map { it.key }.toSet().size)
        assertTrue(vag.dataItems(VagEcus.GATEWAY, passat).isNotEmpty())
        assertTrue(vag.dataItems(VagEcus.AIRBAG, passat).isEmpty())
    }

    @Test
    fun modelYearFilterPicksVariant() {
        val early = vag.dataItems(VagEcus.ENGINE, passat.copy(modelYear = 2018)).map { it.key }
        val late = vag.dataItems(VagEcus.ENGINE, passat.copy(modelYear = 2023)).map { it.key }
        assertTrue("PASSAT_ENG_CTRL_MODULE_T" in early && "PASSAT_ENG_CTRL_MODULE_T_K" !in early)
        assertTrue("PASSAT_ENG_CTRL_MODULE_T_K" in late && "PASSAT_ENG_CTRL_MODULE_T" !in late)
    }

    @Test
    fun readObdbSignalThroughUds() = runTest {
        // Q5_INSIDE_T on central electronics 0x70E: 22 2613, len 16, div 10, add -50, needs session 03
        val item = vag.dataItems(VagEcus.CENTRAL_ELECTRONICS, q5).first { it.key == "Q5_INSIDE_T" } as ScaledDataItem
        assertEquals(0x03, item.requiredSession)
        val link = FakeVagLink.of(
            (0x70E to "10 03") to "50 03 00 32 01 F4",
            (0x70E to "22 26 13") to "62 26 13 02 F8",
        )
        val v = vag.readData(VagEcus.CENTRAL_ELECTRONICS, item, DefaultUdsClient(link))
        assertNotNull(v)
        assertEquals(26.0, v.value!!, 1e-9)
        assertEquals("°C", v.unit)
        assertEquals("09 Central electronics", v.ecu)
    }

    @Test
    fun mqbScanHasNoNotes() = runTest {
        val link = FakeVagLink.of(
            (0x7E0 to "22 F1 87") to "62 F1 87 30 34 4C 39 30 36 30 35 36 44 20",
            (0x7E0 to "22 06 00") to "62 06 00 01 00 3D",
            (0x7E0 to "19 02 FF") to "59 02 FF",
            (0x710 to "22 F1 87") to "62 F1 87 33 51 30 39 30 37 35 33 30 20 20",
            (0x710 to "19 02 FF") to "59 02 FF C1 00 87 2F",
        )
        val reports = scanAll(passat, link)
        val gateway = reports.first { it.ecu == VagEcus.GATEWAY }
        assertTrue(gateway.present)
        assertEquals("Part number" to "3Q0907530", gateway.identification.first())
        assertEquals("U0100", gateway.dtcs.single().code)
        assertEquals("19 Gateway", gateway.dtcs.single().ecu)
        assertTrue(gateway.dtcs.single().description!!.endsWith("missing message"))
        val engine = reports.first { it.ecu == VagEcus.ENGINE }
        assertEquals("Coding" to "01003D", engine.identification.last())
        assertEquals(emptyList(), vag.scanNotes(passat, reports))
        assertNull(reports.first { it.ecu == VagEcus.AIRBAG }.error)
    }

    @Test
    fun onlyEngineAnsweringSuggestsTp20() = runTest {
        val link = FakeVagLink.of(
            (0x7E0 to "22 F1 87") to "7F 22 11",
            (0x7E0 to "19 02 FF") to "7F 19 11",
        )
        val old = VehicleContext(Vin("WVWZZZ3CZAE123456"), vw, modelYear = 2010, canBus = true)
        val reports = scanAll(old, link)
        assertTrue(reports.first { it.ecu == VagEcus.ENGINE }.present)
        assertEquals(VagBrandModule.TP20_NOTE, reports.first { it.ecu == VagEcus.GATEWAY }.error)
        assertEquals(listOf(VagBrandModule.TP20_NOTE), vag.scanNotes(old, reports))
        // nothing answered at all (e.g. ignition off): no TP2.0 claim
        assertEquals(emptyList(), vag.scanNotes(old, scanAll(old, FakeVagLink.of())))
    }

    @Test
    fun kLineCarGetsNote() {
        assertEquals(listOf(VagBrandModule.KLINE_NOTE), vag.scanNotes(passat.copy(canBus = false), emptyList()))
    }

    private suspend fun scanAll(vehicle: VehicleContext, link: FakeVagLink): List<EcuReport> {
        val uds = DefaultUdsClient(link)
        return vag.ecus(vehicle).map { vag.scan(it, uds) }
    }
}
