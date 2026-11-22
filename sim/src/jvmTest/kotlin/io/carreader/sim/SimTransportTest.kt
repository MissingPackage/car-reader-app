package io.carreader.sim

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Byte-level behaviour of the simulated adapter, independent of the driver. */
class SimTransportTest {
    private suspend fun SimTransport.cmd(c: String): String {
        write("$c\r".encodeToByteArray())
        return read(100).decodeToString()
    }

    private suspend fun open(scenario: SimScenario = Scenarios.passatB8()) = SimTransport(scenario).also { it.connect() }

    @Test
    fun resetBannerEchoAndPrompt() = runTest {
        val t = open()
        assertEquals("ATZ\r\rELM327 v2.3\r\r>", t.cmd("ATZ"))
        assertEquals("ATE0\rOK\r\r>", t.cmd("ATE0"))
        assertEquals("OK\r\n\r\n>", t.cmd("ATL1"))
        assertEquals("ELM327 v2.3\r\n\r\n>", t.cmd("ATI"))
    }

    @Test
    fun searchingThenHeadersOffFormatting() = runTest {
        val t = open()
        t.cmd("ATE0")
        val first = t.cmd("0100")
        assertTrue(first.startsWith("SEARCHING...\r41 00 "), first)
        assertTrue(first.endsWith(" \r\r>"))
        assertEquals("A6\r\r>", t.cmd("ATDPN"))
        val vin = t.cmd("0902").split('\r').filter { it.isNotBlank() && it != ">" }
        assertEquals("014", vin[0])
        assertEquals("0: 49 02 01 57 56 57 ", vin[1])
        assertEquals("2: 45 35 31 32 33 34 35 ", vin[3])
    }

    @Test
    fun spacesOffAndHeadersOn() = runTest {
        val t = open()
        t.cmd("ATE0"); t.cmd("ATS0"); t.cmd("ATH1"); t.cmd("ATSP6")
        assertEquals("7E803410D00AAAAAAAA\r7E903410D00AAAAAAAA\r\r>", t.cmd("010D"), "engine and gearbox both report speed")
    }

    @Test
    fun flowControlModeOneNeedsHeaderAndData() = runTest {
        val t = open()
        t.cmd("ATE0")
        assertEquals("?\r\r>", t.cmd("ATFCSM1"))
        t.cmd("ATFCSH710")
        assertEquals("?\r\r>", t.cmd("ATFCSM1"))
        t.cmd("ATFCSD300000")
        assertEquals("OK\r\r>", t.cmd("ATFCSM1"))
    }

    @Test
    fun cloneLacksStAndSomeAtCommands() = runTest {
        val t = open(Scenarios.cloneAdapter())
        t.cmd("ATE0")
        assertEquals("?\r\r>", t.cmd("STI"))
        assertEquals("?\r\r>", t.cmd("AT@1"))
        assertEquals("?\r\r>", t.cmd("ATCRA7E8"))
        assertEquals("ELM327 v1.5\r\r>", t.cmd("ATI"))
    }

    @Test
    fun unknownCommandsAndBadRequests() = runTest {
        val t = open()
        t.cmd("ATE0")
        assertEquals("?\r\r>", t.cmd("ATXYZ"))
        assertEquals("?\r\r>", t.cmd("01020304050607080910"))
        assertEquals("12.6V\r\r>".length, t.cmd("ATRV").length)
    }

    @Test
    fun emptyLineRepeatsLastCommand() = runTest {
        val t = open()
        t.cmd("ATE0"); t.cmd("ATSP6")
        val a = t.cmd("010D")
        assertEquals(a, t.cmd(""))
    }

    @Test
    fun kLineHeadersAndChecksum() = runTest {
        val t = open(Scenarios.kLineLegacy())
        t.cmd("ATE0"); t.cmd("ATH1"); t.cmd("ATSP3")
        val r = t.cmd("010D").split('\r').filter { it.isNotBlank() && it != ">" }
        assertEquals("BUS INIT: ...OK", r[0])
        // 48 + 6B + 10 + 41 + 0D + 00 = 0x111 → checksum 0x11
        assertEquals("48 6B 10 41 0D 00 11 ", r[1])
    }

    @Test
    fun ignitionOffIsUnableToConnect() = runTest {
        val s = Scenarios.genericPetrol()
        s.vehicle.ignitionOn = false
        val t = open(s)
        t.cmd("ATE0")
        assertEquals("SEARCHING...\rUNABLE TO CONNECT\r\r>", t.cmd("0100"))
    }
}
