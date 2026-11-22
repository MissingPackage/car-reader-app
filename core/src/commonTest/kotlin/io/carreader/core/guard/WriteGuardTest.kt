package io.carreader.core.guard

import io.carreader.core.link.AdapterChip
import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WriteGuardTest {
    private class RecordingLink : DiagnosticLink {
        val sent = mutableListOf<String>()
        override suspend fun initialize() = adapterInfo!!
        override val busProtocol: BusProtocol? = BusProtocol.CAN_11_500
        override val adapterInfo: AdapterInfo? = AdapterInfo("ELM327 v1.5", null, AdapterChip.ELM327_CLONE, false, true, true, certified = false)
        override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> {
            sent += "${target.requestId.toString(16)}:" + payload.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return listOf(EcuResponse(target.responseId, byteArrayOf((payload[0] + 0x40).toByte())))
        }
        override suspend fun readBatteryVoltage(): Double? = 12.5
        override suspend fun close() = Unit
    }

    private class Ctx(var speed: Int? = 0, var rpm: Int? = 0, var volts: Double? = 12.6) : GuardContext {
        override val adapter = AdapterInfo("STN2255", "5.10", AdapterChip.STN, true, true, true, certified = true)
        override val busProtocol: BusProtocol? = BusProtocol.CAN_11_500
        override suspend fun vehicleSpeedKmh() = speed
        override suspend fun engineRpm() = rpm
        override suspend fun batteryVoltage() = volts
    }

    private val audit = mutableListOf<String>()
    private val ecu = EcuAddress(0x713, 0x77D)
    private val clear = MutationRequest(MutationKind.CLEAR_ECU_DTC, ecu, "03 Brakes", "Clear")

    private fun setup(enabled: Boolean = true, confirm: suspend () -> Boolean = { true }): Pair<WriteGuard, GatedLink> {
        val guard = WriteGuard(ClearOnlyPolicy { enabled }, StandardChecks.all(), { _, _ -> confirm() }, { audit += it.outcome }, { 0L })
        return guard to GatedLink(link, guard)
    }

    private val link = RecordingLink()

    @Test
    fun readsPassWithoutPermit() = runTest {
        val (_, gated) = setup()
        gated.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0C), RequestOptions())
        gated.request(ecu, byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()), RequestOptions())
        gated.request(ecu, byteArrayOf(0x10, 0x03), RequestOptions())
        assertEquals(3, link.sent.size)
    }

    @Test
    fun unguardedMutationsAndUnknownServicesAreRefused() = runTest {
        val (_, gated) = setup()
        for (payload in listOf(byteArrayOf(0x14, -1, -1, -1), byteArrayOf(0x11, 0x01), byteArrayOf(0x10, 0x02),
            byteArrayOf(0x10, 0x85.toByte()), byteArrayOf(0x30, 0x01, 0x07), byteArrayOf(0x2C), byteArrayOf(0x83.toByte()))) {
            assertFailsWith<GuardDenied> { gated.request(ecu, payload, RequestOptions()) }
        }
        assertTrue(link.sent.isEmpty())
        assertTrue(audit.all { it == "denied at gate" } && audit.size == 7)
    }

    @Test
    fun permitCoversOnlyApprovedServiceAndTarget() = runTest {
        val (guard, gated) = setup()
        guard.execute(clear, Ctx()) {
            gated.request(ecu, byteArrayOf(0x14, -1, -1, -1), RequestOptions())
            assertFailsWith<GuardDenied> { gated.request(ecu, byteArrayOf(0x2E, 0xF1.toByte(), 0x90.toByte(), 0x41), RequestOptions()) }
            assertFailsWith<GuardDenied> { gated.request(ecu, byteArrayOf(0x31, 0x01, 0x02, 0x03), RequestOptions()) }
            assertFailsWith<GuardDenied> { gated.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x14, -1, -1, -1), RequestOptions()) }
        }
        assertEquals(listOf("713:14 ff ff ff"), link.sent)
    }

    @Test
    fun otherCoroutinesGetNoPermitDuringExecute() = runTest {
        val (guard, gated) = setup()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch { guard.execute(clear, Ctx()) { inside.complete(Unit); release.await() } }
        inside.await()
        val intruder = async { runCatching { gated.request(ecu, byteArrayOf(0x14, -1, -1, -1), RequestOptions()) } }
        assertTrue(intruder.await().exceptionOrNull() is GuardDenied)
        release.complete(Unit)
        job.join()
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun disabledPolicyBlocksAndAudits() = runTest {
        val (guard, _) = setup(enabled = false)
        assertFailsWith<GuardDenied> { guard.execute(clear, Ctx()) { } }
        assertTrue(audit.single().startsWith("denied"))
    }

    @Test
    fun writesNeverAllowedByClearOnlyPolicy() = runTest {
        val (guard, _) = setup()
        val write = MutationRequest(MutationKind.WRITE_DATA, ecu, "x", "x")
        assertFailsWith<GuardDenied> { guard.execute(write, Ctx()) { } }
    }

    @Test
    fun movingOrLowBatteryOrUnknownSpeedBlocks() = runTest {
        val (guard, _) = setup()
        for (ctx in listOf(Ctx(speed = 30), Ctx(speed = null), Ctx(volts = 11.2), Ctx(volts = null), Ctx(rpm = 800))) {
            assertFailsWith<GuardDenied> { guard.execute(clear, ctx) { } }
        }
        assertEquals(5, audit.size)
    }

    @Test
    fun unknownRpmOnlyWarnsForClears() = runTest {
        val (guard, _) = setup()
        var ran = false
        guard.execute(clear, Ctx(rpm = null)) { ran = true }
        assertTrue(ran)
    }

    @Test
    fun checksRunAgainAfterConfirmation() = runTest {
        val ctx = Ctx()
        val (guard, _) = setup(confirm = { ctx.speed = 12; true })
        assertFailsWith<GuardDenied> { guard.execute(clear, ctx) { } }
    }

    @Test
    fun cancelAndThrowingChecksAreAudited() = runTest {
        val (guard, _) = setup(confirm = { false })
        assertFailsWith<GuardDenied> { guard.execute(clear, Ctx()) { } }
        val (guard2, _) = setup()
        val throwing = object : GuardContext by Ctx() { override suspend fun vehicleSpeedKmh(): Int? = error("bt drop") }
        assertFailsWith<GuardDenied> { guard2.execute(clear, throwing) { } }
        assertEquals(2, audit.size)
    }

    @Test
    fun auditFailureDoesNotMaskSuccess() = runTest {
        val guard = WriteGuard(ClearOnlyPolicy { true }, StandardChecks.all(), { _, _ -> true }, { error("disk full") }, { 0L })
        assertEquals(42, guard.execute(clear, Ctx()) { 42 })
    }

    @Test
    fun nestedExecuteFailsFast() = runTest {
        val (guard, _) = setup()
        assertFailsWith<IllegalStateException> { guard.execute(clear, Ctx()) { guard.execute(clear, Ctx()) { } } }
    }
}
