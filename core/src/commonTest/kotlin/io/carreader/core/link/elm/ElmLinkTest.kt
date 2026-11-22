package io.carreader.core.link.elm

import io.carreader.core.link.AdapterChip
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.LinkException
import io.carreader.core.log.Direction
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElmLinkTest {
    private fun stnAdapter() = ScriptedTransport(echo = true)
        .on("ATZ", "\nELM327 v1.4b")
        .on("ATI", "ELM327 v1.4b")
        .on("AT@1", "OBDLink MX+")
        .on("STI", "STN2255 v5.10.3")
        .on("STDI", "OBDLink MX+ r2")
        .on("0100", "SEARCHING...\n7E8 06 41 00 BE 3F A8 13 00")
        .on("ATDPN", "A6")
        .on("ATRV", "12.6V")

    private fun cloneAdapter() = ScriptedTransport(echo = true)
        .on("ATZ", "\nELM327 v1.5")
        .on("ATI", "ELM327 v1.5")
        .on("AT@1", "?")
        .on("ATAT1", "?")
        .on("STI", "?")
        .on("STDI", "?")
        .on("0100", "SEARCHING...\n7E8064100BE3FA813")
        .on("ATDPN", "A6")
        .on("ATRV", "12.3V")

    @Test
    fun initSequenceOnStnAdapter() = runTest {
        val t = stnAdapter()
        val link = ElmLink(t)
        val info = link.initialize()
        assertTrue(t.connected)
        assertEquals(AdapterChip.STN, info.chip)
        assertTrue(info.certified)
        assertTrue(info.supportsStCommands)
        assertTrue(info.supportsVoltage)
        assertEquals("OBDLink MX+ r2", info.identity)
        assertEquals("STN2255 v5.10.3", info.firmware)
        assertEquals(BusProtocol.CAN_11_500, link.busProtocol)
        val init = t.sent.takeWhile { it != "ATI" }
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP0"), init)
        val after = t.sent.dropWhile { it != "ATI" }
        assertEquals(listOf("ATI", "AT@1", "STI", "STDI", "0100", "ATDPN", "ATRV"), after)
    }

    @Test
    fun initSequenceOnCloneAdapter() = runTest {
        val t = cloneAdapter()
        val info = ElmLink(t).initialize()
        assertEquals(AdapterChip.ELM327_CLONE, info.chip)
        assertEquals(false, info.certified)
        assertEquals(false, info.supportsStCommands)
        assertEquals("ELM327 v1.5", info.identity)
        assertTrue(info.notes.any { it.contains("likely a clone") }, info.notes.toString())
    }

    @Test
    fun simulatorIdentityIsCertified() = runTest {
        val t = cloneAdapter().on("AT@1", "Car Reader Simulator")
        val info = ElmLink(t).initialize()
        assertEquals(AdapterChip.SIMULATOR, info.chip)
        assertTrue(info.certified)
    }

    @Test
    fun initFailsWhenEchoCannotBeDisabled() = runTest {
        val t = stnAdapter().on("ATE0", "?")
        assertFailsWith<LinkException> { ElmLink(t).initialize() }
    }

    @Test
    fun unableToConnectAtInitLeavesProtocolUnknown() = runTest {
        val t = stnAdapter().on("0100", "SEARCHING...\nUNABLE TO CONNECT").on("ATDPN", "A0")
        val link = ElmLink(t)
        val info = link.initialize()
        assertNull(link.busProtocol)
        assertTrue(info.notes.any { it.contains("UNABLE TO CONNECT") })
    }

    @Test
    fun singleFrameWithoutSpacesIgnoresPadding() = runTest {
        val t = stnAdapter().on("010C", "7E804410C1AF8AAAAAA")
        val link = ElmLink(t).also { it.initialize() }
        val r = link.request(EcuAddress.OBD_FUNCTIONAL, "01 0C".hexToBytes())
        assertEquals(1, r.size)
        assertEquals(0x7E8, r[0].sourceId)
        assertEquals("41 0C 1A F8", r[0].payload.toHex())
    }

    @Test
    fun functionalRequestReassemblesInterleavedMultiFrameAnswers() = runTest {
        val t = stnAdapter().on(
            "0902",
            listOf(
                "7E8 10 14 49 02 01 57 56 57",
                "7E9 10 14 49 02 01 57 56 57",
                "7E8 21 5A 5A 5A 33 43 5A 47",
                "7E9 21 5A 5A 5A 33 43 5A 47",
                "7E8 22 45 31 32 33 34 35 36",
                "7E9 22 45 31 32 33 34 35 36",
            ).joinToString("\n"),
        )
        val link = ElmLink(t).also { it.initialize() }
        val r = link.request(EcuAddress.OBD_FUNCTIONAL, "09 02".hexToBytes())
        assertEquals(listOf(0x7E8, 0x7E9), r.map { it.sourceId })
        r.forEach {
            assertEquals(0x14, it.payload.size)
            assertEquals("WVWZZZ3CZGE123456", it.payload.copyOfRange(3, 20).decodeToString())
        }
    }

    @Test
    fun physicalRequestOutsideObdRangeSetsFlowControlOnce() = runTest {
        val t = stnAdapter().on(
            "22F187",
            "77A 10 0E 62 F1 87 35 51 30\n77A 21 39 30 37 35 33 30 20\n77A 22 20 AA AA AA AA AA AA",
        )
        val link = ElmLink(t).also { it.initialize() }
        val gateway = EcuAddress(0x710, 0x77A)
        val start = t.sent.size
        val r = link.request(gateway, "22 F1 87".hexToBytes())
        assertEquals("62 F1 87 35 51 30 39 30 37 35 33 30 20 20", r.single().payload.toHex())
        assertEquals(
            listOf("ATSH710", "ATFCSH710", "ATFCSD300000", "ATFCSM1", "ATCRA77A", "ATSTFF", "22F187"),
            t.sent.drop(start),
        )
        val second = t.sent.size
        link.request(gateway, "22 F1 87".hexToBytes())
        assertEquals(listOf("22F187"), t.sent.drop(second))

        val third = t.sent.size
        t.on("0100", "7E8 06 41 00 BE 3F A8 13 00")
        link.request(EcuAddress.OBD_FUNCTIONAL, "01 00".hexToBytes())
        assertEquals(listOf("ATSH7DF", "ATAR", "ATFCSM0", "ATST32", "0100"), t.sent.drop(third))
    }

    @Test
    fun responsePendingIsReissuedUntilTheFinalAnswer() = runTest {
        val t = stnAdapter().on(
            "22F190",
            "7E8 03 7F 22 78 AA AA AA AA",
            "7E8 03 7F 22 78 AA AA AA AA",
            "7E8 10 14 62 F1 90 57 56 57\n7E8 21 5A 5A 5A 33 43 5A 47\n7E8 22 45 31 32 33 34 35 36",
        )
        val link = ElmLink(t).also { it.initialize() }
        val r = link.request(EcuAddress.OBD_ENGINE, "22 F1 90".hexToBytes())
        assertEquals(3, t.sentCount("22F190"))
        assertEquals("WVWZZZ3CZGE123456", r.single().payload.copyOfRange(3, 20).decodeToString())
    }

    @Test
    fun responsePendingFollowedByAnswerInTheSameReply() = runTest {
        val t = stnAdapter().on("1902FF", "7E8 03 7F 19 78 00 00 00 00\n7E8 07 59 02 FF 01 04 01 2F")
        val link = ElmLink(t).also { it.initialize() }
        val r = link.request(EcuAddress.OBD_ENGINE, "19 02 FF".hexToBytes())
        assertEquals(1, t.sentCount("1902FF"))
        assertEquals("59 02 FF 01 04 01 2F", r.single().payload.toHex())
    }

    @Test
    fun responsePendingGivesUpAfterBudget() = runTest {
        val t = stnAdapter().on("22F190", "7E8 03 7F 22 78")
        val link = ElmLink(t).also { it.initialize() }
        assertFailsWith<LinkException> { link.request(EcuAddress.OBD_ENGINE, "22 F1 90".hexToBytes()) }
        assertTrue(t.sentCount("22F190") in 10..30)
    }

    @Test
    fun noDataIsAnEmptyList() = runTest {
        val t = stnAdapter().on("0110", "NO DATA")
        val link = ElmLink(t).also { it.initialize() }
        assertEquals(0, link.request(EcuAddress.OBD_FUNCTIONAL, "01 10".hexToBytes()).size)
    }

    @Test
    fun adapterErrorsAreLinkExceptions() = runTest {
        for (err in listOf("CAN ERROR", "BUS ERROR", "BUFFER FULL", "STOPPED", "?", "UNABLE TO CONNECT")) {
            val t = stnAdapter().on("010D", err)
            val link = ElmLink(t).also { it.initialize() }
            assertFailsWith<LinkException>(err) { link.request(EcuAddress.OBD_FUNCTIONAL, "01 0D".hexToBytes()) }
        }
    }

    @Test
    fun incompleteMultiFrameOnPhysicalRequestFails() = runTest {
        val t = stnAdapter().on("22F187", "77A 10 0E 62 F1 87 35 51 30")
        val link = ElmLink(t).also { it.initialize() }
        val e = assertFailsWith<LinkException> { link.request(EcuAddress(0x710, 0x77A), "22 F1 87".hexToBytes()) }
        assertTrue(e.message!!.contains("Incomplete"))
    }

    @Test
    fun kLineReturnsOneResponsePerLine() = runTest {
        val t = stnAdapter()
            .on("0100", "SEARCHING...\n48 6B 10 41 00 BE 3E B8 11 C9")
            .on("ATDPN", "A3")
            .on(
                "0902",
                listOf(
                    "48 6B 10 49 02 01 00 00 00 31 21",
                    "48 6B 10 49 02 02 47 31 4A 43 6E",
                    "48 6B 10 49 02 03 35 34 34 34 FE",
                    "48 6B 10 49 02 04 52 37 32 35 08",
                    "48 6B 10 49 02 05 32 33 36 37 03",
                ).joinToString("\n"),
            )
        val link = ElmLink(t).also { it.initialize() }
        assertEquals(BusProtocol.ISO_9141_2, link.busProtocol)
        val start = t.sent.size
        val r = link.request(EcuAddress.OBD_FUNCTIONAL, "09 02".hexToBytes())
        assertEquals(listOf("0902"), t.sent.drop(start), "no CAN addressing on K-line")
        assertEquals(5, r.size)
        assertTrue(r.all { it.sourceId == 0x10 })
        assertEquals("49 02 01 00 00 00 31", r[0].payload.toHex())
        assertEquals("49 02 05 32 33 36 37", r[4].payload.toHex())
    }

    @Test
    fun batteryVoltage() = runTest {
        val t = stnAdapter()
        val link = ElmLink(t).also { it.initialize() }
        assertEquals(12.6, link.readBatteryVoltage())
        t.on("ATRV", "?")
        assertNull(link.readBatteryVoltage())
    }

    @Test
    fun trafficLogGetsEveryLineWithClockTimestamps() = runTest {
        var now = 1000L
        val log = RingTrafficLog(clock = { now })
        val t = stnAdapter().on("010C", "7E8 04 41 0C 1A F8")
        val link = ElmLink(t, log) { now++ }
        link.initialize()
        link.request(EcuAddress.OBD_FUNCTIONAL, "01 0C".hexToBytes())
        val entries = log.snapshot()
        assertTrue(entries.any { it.direction == Direction.TX && it.text == "010C" })
        assertTrue(entries.any { it.direction == Direction.RX && it.text == "7E8 04 41 0C 1A F8" })
        assertTrue(entries.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
    }

    @Test
    fun requestBeforeInitFails() = runTest {
        assertFailsWith<LinkException> { ElmLink(ScriptedTransport()).request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(1, 0)) }
    }

    @Test
    fun extendedCanUsesPriorityAndShortHeader() = runTest {
        val t = stnAdapter()
            .on("0100", "18 DA F1 10 06 41 00 BE 3F A8 13 00")
            .on("ATDPN", "A7")
            .on("010D", "18DAF11003410D32AAAAAAAA")
        val link = ElmLink(t).also { it.initialize() }
        assertEquals(BusProtocol.CAN_29_500, link.busProtocol)
        val start = t.sent.size
        val r = link.request(EcuAddress.OBD_ENGINE, "01 0D".hexToBytes())
        assertEquals(0x18DAF110, r.single().sourceId)
        assertEquals("41 0D 32", r.single().payload.toHex())
        assertEquals(listOf("ATCP18", "ATSHDA10F1", "ATCRA18DAF110", "010D"), t.sent.drop(start))
    }
}
