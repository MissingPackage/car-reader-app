package io.carreader.sim

import io.carreader.core.link.AdapterChip
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.elm.ElmLink
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Integration: the real ElmLink driver over the simulated adapter and cars. */
class ElmLinkOverSimTest {
    private suspend fun connect(scenario: SimScenario): Pair<ElmLink, SimTransport> {
        val t = SimTransport(scenario)
        val link = ElmLink(t)
        link.initialize()
        return link to t
    }

    private suspend fun ElmLink.ask(target: EcuAddress, hex: String): List<EcuResponse> = request(target, hex.hexToBytes())

    @Test
    fun passatInitIdentifiesSimulatorAndCan() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        val info = link.adapterInfo!!
        assertEquals(AdapterChip.SIMULATOR, info.chip)
        assertTrue(info.certified)
        assertEquals(BusProtocol.CAN_11_500, link.busProtocol)
        assertEquals(14.2, link.readBatteryVoltage())
    }

    @Test
    fun stnProfileIsClassifiedAsStn() = runTest {
        val (link, _) = connect(Scenarios.passatB8(adapter = SimAdapterProfile.OBDLINK_MX))
        assertEquals(AdapterChip.STN, link.adapterInfo!!.chip)
        assertEquals("OBDLink MX+ r2", link.adapterInfo!!.identity)
    }

    @Test
    fun functionalRequestGetsEngineAndGearbox() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        val r = link.ask(EcuAddress.OBD_FUNCTIONAL, "01 00")
        assertEquals(listOf(0x7E8, 0x7E9), r.map { it.sourceId })
        assertTrue(r.all { it.payload.size == 6 && it.payload[0] == 0x41.toByte() })

        val names = link.ask(EcuAddress.OBD_FUNCTIONAL, "09 0A")
        assertEquals(listOf(0x7E8, 0x7E9), names.map { it.sourceId })
        assertEquals("ECM\u0000-EngineControl\u0000\u0000", names[0].payload.copyOfRange(3, 23).decodeToString())
    }

    @Test
    fun vinIsReassembledFromMultiFrame() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        val r = link.ask(EcuAddress.OBD_FUNCTIONAL, "09 02").single()
        assertEquals(0x7E8, r.sourceId)
        assertEquals("WVWZZZ3CZGE512345", r.payload.copyOfRange(3, 20).decodeToString())
    }

    @Test
    fun gatewayIdentificationNeedsAndGetsFlowControl() = runTest {
        val (link, t) = connect(Scenarios.passatB8())
        val gw = EcuAddress(0x710, 0x77A)
        val part = link.ask(gw, "22 F1 87").single()
        assertEquals("62 F1 87", part.payload.copyOfRange(0, 3).toHex())
        assertEquals("5Q0907530AA", part.payload.copyOfRange(3, part.payload.size).decodeToString().trim())
        assertTrue(t.commands.containsAll(listOf("ATFCSH710", "ATFCSD300000", "ATFCSM1", "ATCRA77A")))
        val vin = link.ask(gw, "22 F1 90").single()
        assertEquals("WVWZZZ3CZGE512345", vin.payload.copyOfRange(3, vin.payload.size).decodeToString())
    }

    @Test
    fun simulatedEcuSendsOnlyFirstFrameWithoutFlowControl() = runTest {
        val t = SimTransport(Scenarios.passatB8())
        t.connect()
        suspend fun cmd(c: String): String {
            t.write("$c\r".encodeToByteArray())
            return t.read(100).decodeToString()
        }
        cmd("ATZ"); cmd("ATE0"); cmd("ATH1"); cmd("ATSP6")
        cmd("ATSH710"); cmd("ATCRA77A")
        val broken = cmd("22F187")
        assertEquals("77A 10 0E 62 F1 87 35 51 30 \r\r>", broken)
        cmd("ATFCSH710"); cmd("ATFCSD300000"); cmd("ATFCSM1")
        val fixed = cmd("22F187")
        assertEquals(3, fixed.split('\r').count { it.startsWith("77A") })
    }

    @Test
    fun airbagAnswersPendingThenDtcs() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        val r = link.ask(EcuAddress(0x715, 0x77F), "19 02 FF").single()
        assertEquals("59 02 FF 90 01 1B 2F", r.payload.toHex())
    }

    @Test
    fun pendingAcrossRequestsIsReissued() = runTest {
        val scenario = Scenarios.passatB8()
        scenario.behavior<UdsEcu>(0x710)!!.pendingRepeats = 3
        val (link, t) = connect(scenario)
        val r = link.ask(EcuAddress(0x710, 0x77A), "19 02 FF").single()
        assertEquals("59 02 FF C1 21 00 28", r.payload.toHex())
        assertEquals(4, t.commands.count { it == "1902FF" })
    }

    @Test
    fun extendedSessionDidNeedsSession() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        val engine = EcuAddress.OBD_ENGINE
        assertEquals("7F 22 31", link.ask(engine, "22 10 44").single().payload.toHex())
        assertEquals("50 03 00 32 01 F4", link.ask(engine, "10 03").single().payload.toHex())
        val r = link.ask(engine, "22 10 44").single()
        assertEquals("62 10 44", r.payload.copyOfRange(0, 3).toHex())
        assertEquals("7E 00", link.ask(engine, "3E 00").single().payload.toHex())
    }

    @Test
    fun clearsAndNegativeResponses() = runTest {
        val scenario = Scenarios.passatB8()
        val (link, _) = connect(scenario)
        val gw = EcuAddress(0x710, 0x77A)
        assertEquals("54", link.ask(gw, "14 FF FF FF").single().payload.toHex())
        assertEquals("59 02 FF", link.ask(gw, "19 02 FF").single().payload.toHex())
        assertEquals("7F 2E 7F", link.ask(gw, "2E 06 00 01").single().payload.toHex())
        assertEquals("7F 85 11", link.ask(gw, "85 02").single().payload.toHex())

        assertEquals("43 01 04 01", link.ask(EcuAddress.OBD_FUNCTIONAL, "03").first { it.sourceId == 0x7E8 }.payload.toHex())
        val cleared = link.ask(EcuAddress.OBD_FUNCTIONAL, "04")
        assertTrue(cleared.all { it.payload.toHex() == "44" })
        assertEquals("43 00", link.ask(EcuAddress.OBD_FUNCTIONAL, "03").first { it.sourceId == 0x7E8 }.payload.toHex())
        assertEquals("4A 01 04 01", link.ask(EcuAddress.OBD_FUNCTIONAL, "0A").first { it.sourceId == 0x7E8 }.payload.toHex(), "permanent DTC survives")
        assertEquals(1, scenario.behavior<ObdEngineEcu>(0x7E0)!!.clearCount)
    }

    @Test
    fun absentEcuIsNoData() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        assertEquals(0, link.ask(EcuAddress(0x757, 0x7C1), "22 F1 87").size)
    }

    @Test
    fun liveValuesArePlausible() = runTest {
        val (link, _) = connect(Scenarios.passatB8())
        val rpm = link.ask(EcuAddress.OBD_ENGINE, "01 0C").single().payload
        val value = ((rpm[2].toInt() and 0xFF) * 256 + (rpm[3].toInt() and 0xFF)) / 4.0
        assertTrue(value in 700.0..1000.0, "idle rpm $value")
        val multi = link.ask(EcuAddress.OBD_ENGINE, "01 0D 05 42").single().payload
        assertEquals("41 0D 00 05", multi.copyOfRange(0, 4).toHex())
        assertEquals(0x42, multi[5].toInt() and 0xFF)
    }

    @Test
    fun extraDidsHook() = runTest {
        val scenario = Scenarios.passatB8(extraDids = mapOf(0x7E0 to mapOf(0x1234 to { _: SignalSource -> byteArrayOf(1, 2) })))
        val (link, _) = connect(scenario)
        assertEquals("62 12 34 01 02", link.ask(EcuAddress.OBD_ENGINE, "22 12 34").single().payload.toHex())
        val rpm = link.ask(EcuAddress.OBD_ENGINE, "22 F4 0C").single().payload
        assertEquals("62 F4 0C", rpm.copyOfRange(0, 3).toHex())
    }

    @Test
    fun audiQ5ScanReachesEveryEcu() = runTest {
        val scenario = Scenarios.audiQ5()
        val (link, _) = connect(scenario)
        for (ecu in scenario.ecus) {
            val r = link.ask(EcuAddress(ecu.requestId, ecu.responseId), "22 F1 87")
            assertEquals(1, r.size, ecu.name)
            assertEquals(ecu.responseId, r[0].sourceId)
            assertEquals(0x62, r[0].payload[0].toInt() and 0xFF, ecu.name)
        }
        val abs = link.ask(EcuAddress(0x713, 0x77D), "19 02 FF").single()
        assertEquals("59 02 FF 40 35 29 28", abs.payload.toHex())
        assertEquals("47 01 02 99", link.ask(EcuAddress.OBD_FUNCTIONAL, "07").first { it.sourceId == 0x7E8 }.payload.toHex())
    }

    @Test
    fun genericPetrolHasNoUds() = runTest {
        val (link, _) = connect(Scenarios.genericPetrol())
        assertEquals("7F 22 11", link.ask(EcuAddress.OBD_ENGINE, "22 F1 90").single().payload.toHex())
        val vin = link.ask(EcuAddress.OBD_FUNCTIONAL, "09 02").single()
        assertEquals("ZFA3120000J123456", vin.payload.copyOfRange(3, 20).decodeToString())
    }

    @Test
    fun kLineLegacyAnswersPerLine() = runTest {
        val (link, _) = connect(Scenarios.kLineLegacy())
        assertEquals(BusProtocol.ISO_9141_2, link.busProtocol)
        val vin = link.ask(EcuAddress.OBD_FUNCTIONAL, "09 02")
        assertEquals(5, vin.size)
        assertTrue(vin.all { it.sourceId == 0x10 })
        val text = vin.flatMap { it.payload.copyOfRange(3, 7).toList() }.toByteArray().decodeToString().trimStart('\u0000')
        assertEquals("W0L0TGF4835012345", text)
        val dtcs = link.ask(EcuAddress.OBD_FUNCTIONAL, "03")
        assertEquals(listOf("43 01 30 03 00 01 71", "43 04 20 00 00 00 00"), dtcs.map { it.payload.toHex() })
        val bitmap = link.ask(EcuAddress.OBD_FUNCTIONAL, "01 00").single().payload
        assertEquals("41 00", bitmap.copyOfRange(0, 2).toHex())
        assertEquals(0, bitmap[5].toInt() and 0x01, "no PIDs above 0x20 on a 2003 car")
    }

    @Test
    fun cloneAdapterWorksWithSoftwareFilter() = runTest {
        val (link, t) = connect(Scenarios.cloneAdapter())
        val info = link.adapterInfo!!
        assertEquals(AdapterChip.ELM327_CLONE, info.chip)
        assertEquals(false, info.certified)
        assertEquals(false, info.supportsStCommands)
        val r = link.ask(EcuAddress.OBD_ENGINE, "01 0C")
        assertEquals(0x7E8, r.single().sourceId)
        assertTrue("ATCRA7E8" in t.commands)
        assertEquals(false, link.adapterProfile!!.supportsReceiveFilter)
        link.ask(EcuAddress.OBD_ENGINE, "01 0D")
        assertEquals(1, t.commands.count { it.startsWith("ATCRA") }, "a rejected filter is not retried")
    }

    @Test
    fun ignitionOffThenOn() = runTest {
        val scenario = Scenarios.genericPetrol()
        scenario.vehicle.ignitionOn = false
        val (link, _) = connect(scenario)
        assertNull(link.busProtocol)
        assertTrue(link.adapterInfo!!.notes.any { it.contains("UNABLE TO CONNECT") })
        scenario.vehicle.ignitionOn = true
        val r = link.ask(EcuAddress.OBD_FUNCTIONAL, "01 00")
        assertEquals(BusProtocol.CAN_11_500, link.busProtocol)
        assertEquals(0x7E8, r.single().sourceId)
    }

    @Test
    fun extendedCanScenario() = runTest {
        val vehicle = SimVehicleState()
        val engine = ComposedEcu(
            "Engine", 0x18DA10F1, 0x18DAF110,
            ObdEngineEcu(LiveSignals(vehicle, diesel = false), vin = "ZFA3120000J123456"),
        )
        val scenario = SimScenario("29-bit test car", "CAN 29-bit", BusProtocol.CAN_29_500, SimAdapterProfile.SIMULATOR, listOf(engine), vehicle)
        val (link, _) = connect(scenario)
        assertEquals(BusProtocol.CAN_29_500, link.busProtocol)
        val vin = link.ask(EcuAddress.OBD_FUNCTIONAL, "09 02").single()
        assertEquals(0x18DAF110, vin.sourceId)
        assertEquals("ZFA3120000J123456", vin.payload.copyOfRange(3, 20).decodeToString())
        assertEquals(0x18DAF110, link.ask(EcuAddress.OBD_ENGINE, "01 0D").single().sourceId)
    }
}
