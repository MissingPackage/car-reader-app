package io.carreader.core.brand

import io.carreader.core.link.EcuAddress
import io.carreader.core.obd2.DtcSource
import io.carreader.core.uds.DefaultUdsClient
import io.carreader.core.uds.ScriptedLink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UdsBrandModuleTest {
    private val engine = EcuDescriptor("01", "Engine", EcuAddress(0x7E0, 0x7E8))
    private val gateway = EcuDescriptor("19", "Gateway", EcuAddress(0x710, 0x77A))
    private val temp = ScaledDataItem("T", "Temperature", 0x2613, SignalFormat(bitLength = 16, div = 10.0, add = -50.0, min = -50.0, max = 6503.0, unit = "celsius"))
    private val extTemp = ScaledDataItem("X", "Extended temp", 0x2614, SignalFormat(bitLength = 8, max = 255.0), requiredSession = 0x03)

    private inner class TestBrand(text: GenericDtcText = GenericDtcText { if (it == "P0301") "Cylinder 1 misfire detected" else null }) : UdsBrandModule(text) {
        override val id = "test"
        override val displayName = "Test"
        override fun supports(vehicle: VehicleContext) = true
        override fun ecuCatalog(vehicle: VehicleContext) = listOf(engine, gateway)
        override val identificationDids = listOf(
            IdentificationDid("Part number", 0xF187),
            IdentificationDid("Software version", 0xF189),
            IdentificationDid("Coding", 0x0600, IdFormat.HEX),
        )
        override fun dataCatalog(ecu: EcuDescriptor, vehicle: VehicleContext) = listOf(temp, extTemp)
        override fun describeManufacturerDtc(ecu: EcuDescriptor, code: String, failureType: Int?) =
            if (code == "P1234") "Brand text" else null
        override fun absentNote(ecu: EcuDescriptor) = if (ecu == gateway) "gateway missing" else null
    }

    private fun presentEngine() = ScriptedLink()
        .on(0x7E0, "22 F1 87", "62 F1 87 30 34 45 39 30 36 30 32 37 20 20 20")
        .on(0x7E0, "22 F1 89", "62 F1 89 FF 00 12")
        .on(0x7E0, "22 06 00", "62 06 00 01 02 0A")
        .on(0x7E0, "19 02 FF", "59 02 FF 03 01 00 2F 12 34 11 08 C1 00 87 00")

    @Test
    fun scanPresentEcu() = runTest {
        val link = presentEngine()
        val report = TestBrand().scan(engine, DefaultUdsClient(link))
        assertTrue(report.present)
        assertNull(report.error)
        assertEquals(
            listOf("Part number" to "04E906027", "Software version" to "FF 00 12", "Coding" to "01020A"),
            report.identification,
        )
        // probe value reused: F187 read once, with the short probe timeout
        assertEquals(1, link.count(0x7E0, "22F187"))
        assertEquals(500, link.sent.first().third.timeoutMs)
        // status-0 record dropped
        assertEquals(listOf("P0301", "P1234"), report.dtcs.map { it.code })
        val misfire = report.dtcs[0]
        assertEquals(DtcSource.ECU, misfire.source)
        assertEquals("01 Engine", misfire.ecu)
        assertEquals(0x2F, misfire.statusByte)
        assertEquals("Cylinder 1 misfire detected", misfire.description)
        assertEquals("Brand text — circuit short to ground", report.dtcs[1].description)
    }

    @Test
    fun scanAbsentEcu() = runTest {
        val link = ScriptedLink()
        val report = TestBrand().scan(gateway, DefaultUdsClient(link))
        assertFalse(report.present)
        assertEquals("gateway missing", report.error)
        assertEquals(listOf("22 F1 87", "3E 00"), link.sent.map { it.second })
        assertTrue(link.sent.all { it.third.timeoutMs == 500L })
    }

    @Test
    fun ecuAnsweringOnlyTesterPresentIsPresent() = runTest {
        val link = ScriptedLink().on(0x710, "3E 00", "7E 00").on(0x710, "19 02 FF", "59 02 FF")
        val report = TestBrand().scan(gateway, DefaultUdsClient(link))
        assertTrue(report.present)
        assertEquals(emptyList(), report.identification)
        assertEquals(emptyList(), report.dtcs)
    }

    @Test
    fun negativeProbeStillPresentAndDtcErrorReported() = runTest {
        val link = ScriptedLink()
            .on(0x7E0, "22 F1 87", "7F 22 31")
            .on(0x7E0, "19 02 FF", "7F 19 22")
        val report = TestBrand().scan(engine, DefaultUdsClient(link))
        assertTrue(report.present)
        assertNotNull(report.error)
        assertTrue(report.error!!.startsWith("DTC read failed"), report.error)
    }

    @Test
    fun extendedSessionOnlyWhenDefaultRefuses() = runTest {
        val link = ScriptedLink()
            .on(0x7E0, "22 F1 87", "62 F1 87 41 42")
            .on(0x7E0, "22 F1 89", "7F 22 7F", "62 F1 89 30 30 31 30")
            .on(0x7E0, "10 03", "50 03 00 32 01 F4")
            .on(0x7E0, "10 01", "50 01 00 32 01 F4")
            .on(0x7E0, "19 02 FF", "59 02 FF")
        val report = TestBrand().scan(engine, DefaultUdsClient(link))
        assertEquals(listOf("Part number" to "AB", "Software version" to "0010"), report.identification)
        assertEquals(1, link.count(0x7E0, "1003"))
        assertEquals(1, link.count(0x7E0, "1001")) // back to default at the end
    }

    @Test
    fun maskFallbackWhenRejected() = runTest {
        val link = ScriptedLink()
            .on(0x7E0, "22 F1 87", "62 F1 87 41")
            .on(0x7E0, "19 02 FF", "7F 19 31")
            .on(0x7E0, "19 02 2F", "59 02 2F 03 01 00 08")
            .on(0x7E0, "10 03", "7F 10 12")
        val report = TestBrand().scan(engine, DefaultUdsClient(link))
        assertEquals(listOf("P0301"), report.dtcs.map { it.code })
        assertNull(report.error)
    }

    @Test
    fun readDataDecodesAndHandlesRefusal() = runTest {
        val link = ScriptedLink()
            .on(0x7E0, "22 26 13", "62 26 13 02 EE")
            .on(0x7E0, "10 03", "50 03 00 32 01 F4")
            .on(0x7E0, "22 26 14", "62 26 14 07")
        val brand = TestBrand()
        val uds = DefaultUdsClient(link)
        val v = brand.readData(engine, temp, uds)!!
        assertEquals(25.0, v.value)
        assertEquals("01 Engine", v.ecu)
        assertEquals(0, link.count(0x7E0, "1003"))

        assertEquals(7.0, brand.readData(engine, extTemp, uds)!!.value)
        assertEquals(1, link.count(0x7E0, "1003"))

        val refused = ScriptedLink().on(0x7E0, "22 26 13", "7F 22 31").on(0x7E0, "10 03", "7F 10 12")
        assertNull(brand.readData(engine, temp, DefaultUdsClient(refused)))
        val short = ScriptedLink().on(0x7E0, "22 26 13", "62 26 13 02")
        assertNull(brand.readData(engine, temp, DefaultUdsClient(short)))
    }

    @Test
    fun identificationDecoding() {
        assertEquals("04E906027", UdsBrandModule.decodeIdentification("00 30 34 45 39 30 36 30 32 37 20 FF".hexBytes(), IdFormat.ASCII))
        assertEquals("01 FF", UdsBrandModule.decodeIdentification("01 FF".hexBytes(), IdFormat.ASCII))
        assertEquals("", UdsBrandModule.decodeIdentification("20 20 00".hexBytes(), IdFormat.ASCII))
    }

    @Test
    fun describeDtcWithoutAnyTable() {
        val brand = TestBrand(GenericDtcText.None)
        assertNull(brand.describeDtc(engine, "P0999", 0))
        assertEquals("missing message", brand.describeDtc(engine, "U0100", 0x87))
    }

    private fun String.hexBytes() = io.carreader.core.util.Hex.decode(this)
}
