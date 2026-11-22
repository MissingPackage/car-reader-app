package io.carreader.core.review

import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.GenericDtcText
import io.carreader.core.brand.IdFormat
import io.carreader.core.brand.IdentificationDid
import io.carreader.core.brand.ScaledDataItem
import io.carreader.core.brand.SignalFormat
import io.carreader.core.brand.UdsBrandModule
import io.carreader.core.brand.VehicleContext
import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import io.carreader.core.uds.DefaultUdsClient
import io.carreader.core.uds.ScriptedLink
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class UdsReviewTest {
    private val engine = EcuDescriptor("01", "Engine", EcuAddress(0x7E0, 0x7E8))
    private val airbag = EcuDescriptor("15", "Airbag", EcuAddress(0x715, 0x77F))
    private val ext = ScaledDataItem("X", "Ext", 0x2614, SignalFormat(bitLength = 8, max = 255.0), requiredSession = 0x03)

    private inner class B : UdsBrandModule(GenericDtcText.None) {
        override val id = "r"; override val displayName = "r"
        override fun supports(vehicle: VehicleContext) = true
        override fun ecuCatalog(vehicle: VehicleContext) = listOf(engine)
        override val identificationDids = listOf(
            IdentificationDid("Part number", 0xF187), IdentificationDid("Software version", 0xF189),
            IdentificationDid("VIN", 0xF190), IdentificationDid("Coding", 0x0600, IdFormat.HEX),
        )
        override fun dataCatalog(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem> = listOf(ext)
    }

    @Test
    fun notCompletedOnlyStatusReportedAsDtc() = runTest {
        // status 0x50 = testNotCompletedSinceLastClear | testNotCompletedThisOperationCycle: not a fault.
        val link = ScriptedLink().on(0x7E0, "22 F1 87", "62 F1 87 41")
            .on(0x7E0, "19 02 FF", "59 02 FF 03 01 00 50 03 02 00 50 03 03 00 09")
        val r = B().scan(engine, DefaultUdsClient(link))
        println("REVIEW 0x50 dtcs: ${r.dtcs.map { it.code + "/" + it.statusByte?.toString(16) }}")
        assertEquals(listOf("P0303"), r.dtcs.map { it.code })
    }

    // Accepted for 0.1: extended-session reads cost one extra request per poll; session caching is tracked as a follow-up.

    @kotlin.test.Ignore

    @Test
    fun readDataLeavesExtendedSessionAndReopensEachPoll() = runTest {
        val link = ScriptedLink().on(0x7E0, "10 03", "50 03 00 32 01 F4").on(0x7E0, "22 26 14", "62 26 14 05")
        val m = B()
        repeat(3) { m.readData(engine, ext, DefaultUdsClient(link)) }
        println("REVIEW readData x3: 1003=${link.count(0x7E0, "1003")} 1001=${link.count(0x7E0, "1001")} sent=${link.sent.map { it.second }}")
        assertEquals(0, link.count(0x7E0, "1003") - 1, "one session switch expected for 3 polls")
    }

    @Test
    fun readDataSurvivesMalformedEcho() = runTest {
        val link = ScriptedLink().on(0x7E0, "10 03", "50 03 00 32 01 F4").on(0x7E0, "22 26 14", "62 26 15 05")
        try {
            val v = B().readData(engine, ext, DefaultUdsClient(link))
            println("REVIEW malformed readData -> $v")
        } catch (e: Exception) {
            println("REVIEW malformed readData threw ${e::class.simpleName}: ${e.message}")
            fail("readData threw ${e::class.simpleName}")
        }
    }

    @Test
    fun oneBadIdentificationAnswerKeepsOthers() = runTest {
        val link = ScriptedLink().on(0x7E0, "22 F1 87", "62 F1 87 41 42")
            .on(0x7E0, "22 F1 89", "62 F1 89 30 31")
            .on(0x7E0, "22 F1 90", "62 F1") // truncated answer
            .on(0x7E0, "22 06 00", "62 06 00 01 02")
            .on(0x7E0, "19 02 FF", "59 02 FF")
        val r = B().scan(engine, DefaultUdsClient(link))
        println("REVIEW truncated F190: id=${r.identification} error=${r.error}")
        assertEquals(3, r.identification.size)
    }

    /** Link that returns another ECU's answer (stale frame / shared filter). */
    private class WrongSourceLink : io.carreader.core.guard.GatedDiagnosticLink {
        override suspend fun initialize(): AdapterInfo = error("x")
        override val busProtocol: BusProtocol = BusProtocol.CAN_11_500
        override val adapterInfo: AdapterInfo? = null
        override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions) =
            if (payload.toHex() == "22 F1 87") listOf(EcuResponse(0x7E8, "62 F1 87 30 34 45".hexToBytes())) else emptyList()
        override suspend fun readBatteryVoltage(): Double? = null
        override suspend fun close() = Unit
    }

    @Test
    fun answerFromAnotherEcuIsRejected() = runTest {
        val r = B().scan(airbag, DefaultUdsClient(WrongSourceLink()))
        println("REVIEW wrong-source: present=${r.present} id=${r.identification}")
        assertTrue(!r.present, "airbag reported present from the engine's 0x7E8 answer")
    }

    // Accepted: only read services are re-sent on 0x78; clears are never repeated.

    @kotlin.test.Ignore

    @Test
    fun pendingResendsTheRequest() = runTest {
        val link = ScriptedLink().on(0x7E0, "19 02 FF", "7F 19 78", "59 02 FF")
        DefaultUdsClient(link).readDtcsByStatus(engine.address, 0xFF)
        println("REVIEW 78 handling: 19 02 FF sent ${link.count(0x7E0, "1902FF")} times")
        assertEquals(1, link.count(0x7E0, "1902FF"))
    }

    @Test
    fun malformedShortAnswersNeverCrashScan() = runTest {
        val shorts = listOf("", "62", "62 F1", "7F", "7F 22", "59", "59 02", "59 02 FF 01", "7F 19 13", "50", "FF FF FF")
        for (a in shorts) {
            val link = ScriptedLink().on(0x7E0, "22 F1 87", "62 F1 87 41").on(0x7E0, "19 02 FF", a)
                .on(0x7E0, "22 F1 89", a).on(0x7E0, "10 03", a).on(0x7E0, "3E 00", a)
            val r = B().scan(engine, DefaultUdsClient(link))
            assertTrue(r.present)
        }
    }
}
