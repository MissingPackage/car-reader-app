package io.carreader.core.link.elm

import io.carreader.core.link.AdapterChip
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.EcuAddress
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoTpReassemblerTest {
    private fun f(id: Int, hex: String) = CanFrame(id, hex.hexToBytes())

    @Test
    fun singleFrameIgnoresPadding() {
        val r = IsoTpReassembler.reassemble(listOf(f(0x7E8, "06 41 00 BE 3F A8 13 AA")))
        assertEquals("41 00 BE 3F A8 13", r.messages.single().payload.toHex())
    }

    @Test
    fun canFdEscapeSingleFrame() {
        val r = IsoTpReassembler.reassemble(listOf(f(0x7E8, "00 03 41 0D 32")))
        assertEquals("41 0D 32", r.messages.single().payload.toHex())
    }

    @Test
    fun interleavedSourcesAndSequentialMessages() {
        val r = IsoTpReassembler.reassemble(
            listOf(
                f(0x7E8, "03 7F 22 78 AA AA AA AA"),
                f(0x7E8, "10 09 62 F1 87 01 02 03"),
                f(0x7E9, "02 41 00"),
                f(0x7E8, "21 04 05 06 07 AA AA AA"),
            ),
        )
        assertEquals(listOf("7F 22 78", "41 00", "62 F1 87 01 02 03 04 05 06"), r.messages.map { it.payload.toHex() })
        assertTrue(r.incomplete.isEmpty())
    }

    @Test
    fun sequenceWrapsAfter15() {
        val frames = ArrayList<CanFrame>()
        val total = 6 + 7 * 16 // 118 bytes: FF + 16 CFs (seq 1..15, 0)
        frames += f(0x77A, "10 ${total.toString(16).padStart(2, '0')} 00 01 02 03 04 05")
        var n = 6
        for (i in 1..16) {
            val seq = i and 0x0F
            val data = (0 until 7).joinToString(" ") { ((n + it) and 0xFF).toString(16).padStart(2, '0') }
            frames += f(0x77A, "2${seq.toString(16)} $data")
            n += 7
        }
        val r = IsoTpReassembler.reassemble(frames)
        assertEquals(total, r.messages.single().payload.size)
        assertEquals((0 until total).map { it.toByte() }, r.messages.single().payload.toList())
    }

    @Test
    fun missingConsecutiveFramesAreReported() {
        val r = IsoTpReassembler.reassemble(listOf(f(0x77A, "10 0E 62 F1 87 35 51 30")))
        assertTrue(r.messages.isEmpty())
        assertEquals(IncompleteMessage(0x77A, 14, 6, "consecutive frames missing"), r.incomplete.single())
    }

    @Test
    fun wrongSequenceAbandonsMessage() {
        val r = IsoTpReassembler.reassemble(listOf(f(0x77A, "10 0E 62 F1 87 35 51 30"), f(0x77A, "22 00 00 00 00 00 00 00")))
        assertTrue(r.messages.isEmpty())
        assertTrue(r.incomplete.single().reason.contains("sequence"))
    }
}

class ElmResponseParserTest {
    @Test
    fun can11WithAndWithoutSpaces() {
        val a = ElmResponseParser.parse(listOf("7E8 06 41 00 BE 3F A8 13 00 "), BusProtocol.CAN_11_500)
        val b = ElmResponseParser.parse(listOf("7E8064100BE3FA81300"), BusProtocol.CAN_11_500)
        for (p in listOf(a, b)) {
            assertEquals(0x7E8, p.canFrames.single().id)
            assertEquals("06 41 00 BE 3F A8 13 00", p.canFrames.single().data.toHex())
        }
    }

    @Test
    fun can29() {
        val p = ElmResponseParser.parse(listOf("18DAF110064100BE3FA813"), BusProtocol.CAN_29_500)
        assertEquals(0x18DAF110, p.canFrames.single().id)
    }

    @Test
    fun serialLineDropsHeaderAndChecksum() {
        val p = ElmResponseParser.parse(listOf("48 6B 10 41 0C 1A F8 C8"), BusProtocol.ISO_9141_2)
        val m = p.serialMessages.single()
        assertEquals(0x10, m.source)
        assertEquals("41 0C 1A F8", m.data.toHex())
    }

    @Test
    fun kwpWithSeparateLengthByteHasFourByteHeader() {
        val p = ElmResponseParser.parse(listOf("80 F1 10 03 41 0D 32 C4"), BusProtocol.KWP_FAST)
        assertEquals("41 0D 32", p.serialMessages.single().data.toHex())
    }

    @Test
    fun statuses() {
        val p = ElmResponseParser.parse(
            listOf("SEARCHING...", "BUS INIT: ...OK", "NO DATA", "CAN ERROR", "?", "ERR94", "<DATA ERROR", "garbage!"),
            BusProtocol.CAN_11_500,
        )
        assertEquals(
            listOf(ElmStatus.SEARCHING, ElmStatus.BUS_INIT_OK, ElmStatus.NO_DATA, ElmStatus.CAN_ERROR, ElmStatus.UNKNOWN_COMMAND, ElmStatus.INTERNAL_ERROR, ElmStatus.DATA_ERROR),
            p.statuses,
        )
        assertEquals(ElmStatus.CAN_ERROR, p.firstError)
        assertEquals(listOf("garbage!"), p.other)
    }

    @Test
    fun unknownProtocolGuessesFromIdLength() {
        val p = ElmResponseParser.parse(listOf("7E8 06 41 00 BE 3F A8 13"), null)
        assertEquals(0x7E8, p.canFrames.single().id)
    }
}

class ElmAddressingTest {
    private val stn = AdapterProfile(AdapterChip.STN, "1.4b", true)

    @Test
    fun onlyChangedSettingsArePlanned() {
        val a = ElmAddressing().also { it.reset() }
        val first = a.plan(EcuAddress.OBD_ENGINE, byteArrayOf(0x01, 0x0C), 2000, false, stn)
        assertEquals(listOf("ATSH7E0", "ATCRA7E8"), first.map { it.command })
        first.forEach(a::applied)
        assertTrue(a.plan(EcuAddress.OBD_ENGINE, byteArrayOf(0x01, 0x0D), 2000, false, stn).isEmpty())
    }

    @Test
    fun functional29BitMapping() {
        val r = ElmAddressing.resolve(EcuAddress.OBD_FUNCTIONAL, extendedBus = true)
        assertEquals(0x18DB33F1, r.requestId)
        assertTrue(r.functional)
        assertEquals(0x18DAF110, ElmAddressing.resolve(EcuAddress(0x18DA10F1, extendedId = true), true).responseId)
    }

    @Test
    fun timeoutDependsOnService() {
        assertEquals("32", ElmAddressing.timeoutFor(byteArrayOf(0x01, 0x00), 2000))
        assertEquals("FF", ElmAddressing.timeoutFor(byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()), 2000))
        assertEquals("32", ElmAddressing.timeoutFor(byteArrayOf(0x22), 100))
    }
}

class AdapterProfileTest {
    @Test
    fun classification() {
        assertEquals(AdapterChip.STN, AdapterProfile.classify(AdapterIdentity("ELM327 v1.4b", "OBDLink MX+", "STN2255 v5.10.3", "OBDLink MX+ r2")).chip)
        assertEquals(AdapterChip.ELM327_CLONE, AdapterProfile.classify(AdapterIdentity("ELM327 v2.1", null, null, null)).chip)
        val genuine = AdapterProfile.classify(AdapterIdentity("ELM327 v2.3", "OBDII to RS232 Interpreter", null, null))
        assertEquals(AdapterChip.ELM327_GENUINE, genuine.chip)
        assertTrue(genuine.notes.single().contains("likely a clone"))
        assertEquals(AdapterChip.SIMULATOR, AdapterProfile.classify(AdapterIdentity("ELM327 v2.3", "Car Reader Simulator", null, null)).chip)
        val unknown = AdapterProfile.classify(AdapterIdentity("WiFi OBD", null, null, null))
        assertEquals(AdapterChip.UNKNOWN, unknown.chip)
        assertNull(unknown.elmVersion)
    }
}
