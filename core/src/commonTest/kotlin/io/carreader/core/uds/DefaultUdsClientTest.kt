package io.carreader.core.uds

import io.carreader.core.link.EcuAddress
import io.carreader.core.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultUdsClientTest {
    private val ecu = EcuAddress(0x710, 0x77A)

    @Test
    fun readDidReturnsDataAfterEchoAndUsesSingleResponse() = runTest {
        val link = ScriptedLink().on(0x710, "22 F1 87", "62 F1 87 35 51 44 39 30 37 35 33 30 20")
        val data = DefaultUdsClient(link).readDataByIdentifier(ecu, 0xF187)
        assertEquals("35 51 44 39 30 37 35 33 30 20", data.toHex())
        assertTrue(link.sent.single().third.singleResponse)
    }

    @Test
    fun readDidRejectsWrongEcho() = runTest {
        val link = ScriptedLink().on(0x710, "22 F1 87", "62 F1 89 01")
        assertFailsWith<UdsProtocolException> { DefaultUdsClient(link).readDataByIdentifier(ecu, 0xF187) }
    }

    @Test
    fun readDidRejectsWrongServiceId() = runTest {
        val link = ScriptedLink().on(0x710, "22 F1 87", "50 03 00 32 01 F4")
        assertFailsWith<UdsProtocolException> { DefaultUdsClient(link).readDataByIdentifier(ecu, 0xF187) }
    }

    @Test
    fun negativeResponseMapsToException() = runTest {
        val link = ScriptedLink().on(0x710, "22 F1 87", "7F 22 31")
        val e = assertFailsWith<NegativeResponseException> { DefaultUdsClient(link).readDataByIdentifier(ecu, 0xF187) }
        assertEquals(0x22, e.serviceId)
        assertEquals(0x31, e.nrc)
        assertTrue(e.message!!.contains("requestOutOfRange"))
    }

    @Test
    fun noAnswerMapsToNoResponse() = runTest {
        val e = assertFailsWith<NoResponseException> { DefaultUdsClient(ScriptedLink()).testerPresent(ecu) }
        assertEquals(ecu, e.target)
    }

    @Test
    fun pendingThatSurfacesIsRetriedBounded() = runTest {
        val ok = ScriptedLink().on(0x710, "22 F1 87", "7F 22 78", "7F 22 78", "62 F1 87 41")
        assertEquals("41", DefaultUdsClient(ok).readDataByIdentifier(ecu, 0xF187).toHex())
        assertEquals(3, ok.count(0x710, "22F187"))

        val stuck = ScriptedLink().on(0x710, "22 F1 87", "7F 22 78")
        val e = assertFailsWith<NegativeResponseException> { DefaultUdsClient(stuck, maxRetries = 2).readDataByIdentifier(ecu, 0xF187) }
        assertEquals(0x78, e.nrc)
        assertEquals(3, stuck.count(0x710, "22F187"))
    }

    @Test
    fun busyRepeatIsRetried() = runTest {
        val link = ScriptedLink().on(0x710, "3E 00", "7F 3E 21", "7E 00")
        DefaultUdsClient(link).testerPresent(ecu)
        assertEquals(2, link.count(0x710, "3E00"))
    }

    @Test
    fun dtcRecordsParsed() = runTest {
        // availability FF; P0301 FTB 00 status 2F; U0100 FTB 87 status 09
        val link = ScriptedLink().on(0x7E0, "19 02 FF", "59 02 FF 03 01 00 2F C1 00 87 09")
        val records = DefaultUdsClient(link).readDtcsByStatus(EcuAddress(0x7E0, 0x7E8), 0xFF)
        assertEquals(listOf(UdsDtcRecord(0x030100, 0x2F), UdsDtcRecord(0xC10087, 0x09)), records)
        assertEquals(0x87, records[1].failureType)
    }

    @Test
    fun dtcEmptyListAndTruncatedRecord() = runTest {
        val empty = ScriptedLink().on(0x7E0, "19 02 0C", "59 02 FF")
        assertEquals(emptyList(), DefaultUdsClient(empty).readDtcsByStatus(EcuAddress(0x7E0), 0x0C))

        val odd = ScriptedLink().on(0x7E0, "19 02 FF", "59 02 FF 03 01 00 2F C1 00")
        assertEquals(listOf(UdsDtcRecord(0x030100, 0x2F)), DefaultUdsClient(odd).readDtcsByStatus(EcuAddress(0x7E0)))
    }

    @Test
    fun dtcWrongSubFunctionRejected() = runTest {
        val link = ScriptedLink().on(0x7E0, "19 02 FF", "59 0A FF")
        assertFailsWith<UdsProtocolException> { DefaultUdsClient(link).readDtcsByStatus(EcuAddress(0x7E0)) }
    }

    @Test
    fun sessionTesterPresentAndClearFrames() = runTest {
        val link = ScriptedLink()
            .on(0x710, "10 03", "50 03 00 32 01 F4")
            .on(0x710, "3E 00", "7E 00")
            .on(0x710, "14 FF FF FF", "54")
        val uds = DefaultUdsClient(link)
        uds.startSession(ecu, UdsSession.EXTENDED)
        uds.testerPresent(ecu)
        uds.clearDiagnosticInformation(ecu)
        assertEquals(listOf("10 03", "3E 00", "14 FF FF FF"), link.sent.map { it.second })
    }

    @Test
    fun sessionEchoMismatchRejected() = runTest {
        val link = ScriptedLink().on(0x710, "10 03", "50 01 00 32 01 F4")
        assertFailsWith<UdsProtocolException> { DefaultUdsClient(link).startSession(ecu, UdsSession.EXTENDED) }
    }

    @Test
    fun withTimeoutPassesShorterTimeout() = runTest {
        val link = ScriptedLink().on(0x710, "3E 00", "7E 00")
        DefaultUdsClient(link).withTimeout(300).testerPresent(ecu)
        assertEquals(300, link.sent.single().third.timeoutMs)
    }

    @Test
    fun failureTypes() {
        assertEquals("circuit short to ground", FailureTypes.describe(0x11))
        assertEquals("missing message", FailureTypes.describe(0x87))
        assertEquals("calibration missing", FailureTypes.describe(0x54))
        assertTrue(FailureTypes.describe(0x3E)!!.startsWith("frequency or pulse signal failure"))
        assertTrue(FailureTypes.describe(0xF3)!!.startsWith("manufacturer-specific"))
        assertEquals(null, FailureTypes.describe(0xA5))
    }
}
