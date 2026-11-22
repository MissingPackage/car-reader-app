package io.carreader.core.obd2

import io.carreader.core.link.BusProtocol
import io.carreader.core.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Obd2ServiceTest {
    private fun ascii(text: String): String = text.encodeToByteArray().toHex()

    // ------------------------------------------------------------ supported PIDs

    @Test
    fun bitmapsMergeAcrossEcusAndStopWhenNextRangeIsClear() = runTest {
        val link = FakeLink()
            .on("01 00", 0x7E8, "41 00 BE 1F A8 13")
            .on("01 00", 0x7E9, "41 00 98 18 00 01")
            .on("01 20", 0x7E8, "41 20 80 00 00 00")
            .on("01 20", 0x7E9, "41 20 00 00 00 01")
            .on("01 40", 0x7E8, "41 40 40 00 00 00")
            .on("01 40", 0x7EA, "7F 01 12")
        val pids = Obd2Service(link).supportedPids()
        val expected = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15,
            0x1C, 0x1F, 0x20, 0x21, 0x40, 0x42)
        assertEquals(expected, pids)
        assertEquals(listOf("01 00", "01 20", "01 40"), link.sent)
    }

    @Test
    fun noAnswerMeansNoPids() = runTest {
        assertEquals(emptySet(), Obd2Service(FakeLink()).supportedPids())
    }

    // ------------------------------------------------------------ readPid

    @Test
    fun readPidReturnsOneValuePerValidEcu() = runTest {
        val link = FakeLink()
            .on("01 0D", 0x7E8, "41 0D 32")
            .on("01 0D", 0x7E9, "41 0D 30")
            .on("01 0D", 0x7EA, "7F 01 12") // negative: ignored
            .on("01 0D", 0x7EB, "41 0C 00") // wrong echo: ignored
            .on("01 0D", 0x7EC, "41 0D") // too short: ignored
        val values = Obd2Service(link).readPid(StandardPids.vehicleSpeed)
        assertEquals(listOf("7E8" to 50.0, "7E9" to 48.0), values.map { it.ecu to it.value })
    }

    // ------------------------------------------------------------ readiness

    @Test
    fun readinessPetrol() = runTest {
        val link = FakeLink().on("01 01", 0x7E8, "41 01 83 07 65 21")
        val r = assertNotNull(Obd2Service(link).readiness())
        assertTrue(r.milOn)
        assertEquals(3, r.storedDtcCount)
        assertFalse(r.compressionIgnition)
        val byName = r.monitors.associateBy { it.name }
        assertEquals(MonitorStatus("Misfire", true, true), byName["Misfire"])
        assertEquals(MonitorStatus("Catalyst", true, false), byName["Catalyst"])
        assertEquals(MonitorStatus("Evaporative system", true, true), byName["Evaporative system"])
        assertEquals(MonitorStatus("Oxygen sensor", true, false), byName["Oxygen sensor"])
        assertEquals(MonitorStatus("Oxygen sensor heater", true, true), byName["Oxygen sensor heater"])
        assertEquals(MonitorStatus("Secondary air system", false, false), byName["Secondary air system"])
        assertNull(byName["Particulate filter"])
    }

    @Test
    fun readinessDiesel() = runTest {
        val link = FakeLink().on("01 01", 0x7E8, "41 01 00 2F E3 40")
        val r = assertNotNull(Obd2Service(link).readiness())
        assertFalse(r.milOn)
        assertEquals(0, r.storedDtcCount)
        assertTrue(r.compressionIgnition)
        val byName = r.monitors.associateBy { it.name }
        assertEquals(MonitorStatus("Fuel system", true, false), byName["Fuel system"])
        assertEquals(MonitorStatus("NMHC catalyst", true, true), byName["NMHC catalyst"])
        assertEquals(MonitorStatus("NOx / SCR aftertreatment", true, true), byName["NOx / SCR aftertreatment"])
        assertEquals(MonitorStatus("Particulate filter", true, false), byName["Particulate filter"])
        assertEquals(MonitorStatus("EGR / VVT system", true, true), byName["EGR / VVT system"])
        assertEquals(MonitorStatus("Boost pressure", false, false), byName["Boost pressure"])
        assertNull(byName["Catalyst"])
        assertNull(byName["Evaporative system"])
    }

    @Test
    fun readinessMergesEcus() = runTest {
        val link = FakeLink()
            .on("01 01", 0x7E9, "41 01 81 05 00 00")
            .on("01 01", 0x7E8, "41 01 01 07 01 00")
        val r = assertNotNull(Obd2Service(link).readiness())
        assertTrue(r.milOn)
        assertEquals(2, r.storedDtcCount)
        assertEquals(MonitorStatus("Fuel system", true, true), r.monitors.first { it.name == "Fuel system" })
        assertNull(Obd2Service(FakeLink()).readiness())
    }

    // ------------------------------------------------------------ DTCs

    @Test
    fun dtcsOnCanUseTheCountByte() = runTest {
        val link = FakeLink(BusProtocol.CAN_11_500)
            .on("03", 0x7E8, "43 02 01 33 03 01")
            .on("03", 0x7E9, "43 00")
            .on("03", 0x7EA, "43 01 C1 00")
        val dtcs = Obd2Service(link).readDtcs(DtcSource.STORED)
        assertEquals(listOf("P0133" to "7E8", "P0301" to "7E8", "U0100" to "7EA"), dtcs.map { it.code to it.ecu })
        assertTrue(dtcs.all { it.source == DtcSource.STORED })
        assertEquals("Cylinder 1 misfire detected", dtcs[1].description)
        assertEquals("Oxygen sensor slow response (bank 1 sensor 1)", dtcs[0].description)
        assertEquals(listOf("03"), link.sent)
    }

    @Test
    fun dtcsOnKLineHaveNoCountByteAndDropPadding() = runTest {
        val link = FakeLink(BusProtocol.KWP_FAST)
            .on("07", 0x10, "47 01 33 03 01 04 20")
            .on("07", 0x10, "47 04 30 00 00 00 00")
        val dtcs = Obd2Service(link).readDtcs(DtcSource.PENDING)
        assertEquals(listOf("P0133", "P0301", "P0420", "P0430"), dtcs.map { it.code })
        assertTrue(dtcs.all { it.source == DtcSource.PENDING })
        assertEquals("010", dtcs[0].ecu)
    }

    @Test
    fun dtcPayloadParsing() {
        assertEquals(listOf(0x0420), Obd2Service.parseDtcPayload(byteArrayOf(0x4A, 0x01, 0x04, 0x20), canBus = true))
        assertEquals(listOf(0x0420), Obd2Service.parseDtcPayload(byteArrayOf(0x43, 0x04, 0x20, 0, 0, 0, 0), canBus = false))
        // Unknown protocol: even length implies a count byte, odd length implies none.
        assertEquals(listOf(0x0420), Obd2Service.parseDtcPayload(byteArrayOf(0x43, 0x01, 0x04, 0x20), canBus = null))
        assertEquals(listOf(0x0420), Obd2Service.parseDtcPayload(byteArrayOf(0x43, 0x04, 0x20, 0, 0, 0, 0), canBus = null))
        // Count byte smaller than the data: trust the count.
        assertEquals(listOf(0x0301), Obd2Service.parseDtcPayload(byteArrayOf(0x43, 0x01, 0x03, 0x01, 0x04, 0x20), canBus = true))
    }

    @Test
    fun permanentDtcsUseService0A() = runTest {
        val link = FakeLink().on("0A", 0x7E8, "4A 01 24 63")
        val dtcs = Obd2Service(link).readDtcs(DtcSource.PERMANENT)
        assertEquals(listOf("P2463"), dtcs.map { it.code })
        assertEquals("Particulate filter restriction: soot accumulation", dtcs[0].description)
    }

    // ------------------------------------------------------------ freeze frame

    @Test
    fun freezeFrameReadsTriggerAndSupportedPids() = runTest {
        val link = FakeLink()
            .on("02 02 00", 0x7E8, "42 02 00 03 01")
            .on("02 00 00", 0x7E8, "42 00 00 48 18 00 00")
            .on("02 05 00", 0x7E8, "42 05 00 7B")
            .on("02 0C 00", 0x7E8, "42 0C 00 1A F8")
            .on("02 0D 00", 0x7E8, "42 0D 00 32")
        val ff = assertNotNull(Obd2Service(link).freezeFrame())
        assertEquals(0, ff.frameIndex)
        assertEquals("P0301", ff.triggerDtc)
        assertEquals(listOf("01.05" to 83.0, "01.0C" to 1726.0, "01.0D" to 50.0), ff.values.map { it.key to it.value })
        assertTrue(ff.values.all { it.ecu == "7E8" })
        assertFalse("02 20 00" in link.sent)
    }

    @Test
    fun noFreezeFrameStored() = runTest {
        assertNull(Obd2Service(FakeLink().on("02 02 00", 0x7E8, "42 02 00 00 00")).freezeFrame())
        assertNull(Obd2Service(FakeLink()).freezeFrame())
    }

    // ------------------------------------------------------------ identification

    @Test
    fun identificationOnCan() = runTest {
        val link = FakeLink(BusProtocol.CAN_11_500)
            .on("09 00", 0x7E8, "49 00 55 40 00 00")
            .on("09 02", 0x7E8, "49 02 01 " + ascii("WVWZZZ3CZJE123456"))
            .on("09 04", 0x7E8, "49 04 01 " + ascii("03L906018AB") + " 00 00 00 00 00")
            .on("09 04", 0x7E9, "49 04 02 " + ascii("0AM927769D") + " 00 00 00 00 00 00 " + ascii("ABCDEFGH12345678"))
            .on("09 06", 0x7E8, "49 06 01 A1 B2 C3 D4")
            .on("09 0A", 0x7E8, "49 0A 01 " + ascii("ECM") + " 00 " + ascii("-EngineControl") + " 00 00")
        val id = Obd2Service(link).identification()
        assertEquals("WVWZZZ3CZJE123456", id.vin)
        assertEquals(listOf("03L906018AB", "0AM927769D", "ABCDEFGH12345678"), id.calibrationIds)
        assertEquals(listOf("A1B2C3D4"), id.cvns)
        assertEquals(listOf("ECM-EngineControl"), id.ecuNames)
        // 09 08 is advertised but not requested: only the four identification PIDs are read.
        assertFalse("09 08" in link.sent)
    }

    @Test
    fun identificationOnKLineMergesFiveMessagesBySequence() = runTest {
        val link = FakeLink(BusProtocol.ISO_9141_2)
            .on("09 02", 0x10, "49 02 03 " + ascii("Z3CZ"))
            .on("09 02", 0x10, "49 02 01 00 00 00 " + ascii("W"))
            .on("09 02", 0x10, "49 02 05 " + ascii("3456"))
            .on("09 02", 0x10, "49 02 02 " + ascii("VWZZ"))
            .on("09 02", 0x10, "49 02 04 " + ascii("JE12"))
            .on("09 02", 0x10, "49 02 04 " + ascii("JE12")) // repeated message
            .on("09 06", 0x10, "49 06 01 11 22 33 44")
            .on("09 06", 0x10, "49 06 02 55 66 77 88")
        link.failing += "09 04" // an ECU that errors out must not break identification
        val id = Obd2Service(link).identification()
        assertEquals("WVWZZZ3CZJE123456", id.vin)
        assertEquals(emptyList(), id.calibrationIds)
        assertEquals(listOf("11223344", "55667788"), id.cvns)
        assertEquals(emptyList(), id.ecuNames)
    }

    @Test
    fun identificationWithNoAnswers() = runTest {
        val id = Obd2Service(FakeLink()).identification()
        assertEquals(VehicleIdentification(vin = null), id)
    }

    // ------------------------------------------------------------ clear

    @Test
    fun clearReportsPositiveResponse() = runTest {
        assertTrue(Obd2Service(FakeLink().on("04", 0x7E8, "44")).clearEmissionDtcs())
        assertFalse(Obd2Service(FakeLink().on("04", 0x7E8, "7F 04 22")).clearEmissionDtcs())
        assertFalse(Obd2Service(FakeLink()).clearEmissionDtcs())
    }
}
