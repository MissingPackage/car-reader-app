package io.carreader.core.link.elm.realadapter

import io.carreader.core.link.AdapterChip
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.LinkException
import io.carreader.core.link.RequestOptions
import io.carreader.core.link.elm.AdapterIdentity
import io.carreader.core.link.elm.AdapterProfile
import io.carreader.core.link.elm.CanFrame
import io.carreader.core.link.elm.ElmLink
import io.carreader.core.link.elm.ElmResponseParser
import io.carreader.core.link.elm.IsoTpReassembler
import io.carreader.core.transport.Transport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Timeline adapter model closer to a real ELM327: output becomes available at a virtual time, stale output
 * is never discarded, and a byte written while the adapter is still busy aborts the running command
 * (`STOPPED`) and the interrupting byte is lost (so the new command answers `?`). Exception: while
 * rebooting (commands in [deafWhileBusy], e.g. `ATZ`) a real chip ignores input entirely and then prints its
 * banner.
 */
class TimelineElm(private val now: () -> Long) : Transport {
    override val description = "timeline"
    private class Chunk(val readyAt: Long, val bytes: String)
    private val queue = ArrayDeque<Chunk>()
    /** handler: command -> list of (delayMs, text) */
    var handler: (String) -> List<Pair<Long, String>> = { listOf(0L to "OK\r\r>") }
    val sent = ArrayList<String>()
    private var busyUntil = 0L
    private var busyCommand = ""
    /** Commands during which input is ignored (a rebooting chip), instead of interrupting them. */
    val deafWhileBusy = HashSet<String>()
    /** Commands whose answer is late only in transit (BLE notification lag): the chip itself is idle. */
    val lagged = HashSet<String>()

    override suspend fun connect() {}
    override suspend fun close() {}

    override suspend fun write(data: ByteArray) {
        val cmd = data.decodeToString().trimEnd('\r')
        sent += cmd
        val t = now()
        if (t < busyUntil && busyCommand in deafWhileBusy) return
        if (t < busyUntil) {
            // Real ELM327: any received character interrupts the current operation.
            queue.removeAll { it.readyAt > t }
            queue.addLast(Chunk(t, "STOPPED\r\r>"))
            queue.addLast(Chunk(t + 1, "?\r\r>"))
            busyUntil = t + 1
            return
        }
        var at = t
        for ((d, text) in handler(cmd)) {
            at += d
            queue.addLast(Chunk(at, text))
        }
        busyUntil = if (cmd in lagged) t else at
        busyCommand = cmd
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        val t = now()
        val c = queue.firstOrNull()
        if (c == null || c.readyAt > t + timeoutMs) { delay(timeoutMs); return ByteArray(0) }
        if (c.readyAt > t) delay(c.readyAt - t)
        queue.removeFirst()
        return c.bytes.encodeToByteArray()
    }
}

private fun TestScope.vclock(): () -> Long = { testScheduler.currentTime }

class ElmRealAdapterReviewTest {
    private var ignitionOn = false

    private fun cloneHandler(cmd: String): List<Pair<Long, String>> = when (cmd.uppercase()) {
        "ATZ" -> listOf(800L to "\r\rELM327 v1.5\r\r>")
        "ATI" -> listOf(0L to "ELM327 v1.5\r\r>")
        "AT@1", "STI", "STDI", "ATAT1" -> listOf(0L to "?\r\r>")
        "ATDPN" -> listOf(0L to (if (ignitionOn) "A6" else "A0") + "\r\r>")
        "ATRV" -> listOf(0L to "12.4V\r\r>")
        // Real automatic search: SEARCHING... at once, then several seconds of silence (K-line inits).
        "0100" -> if (ignitionOn) listOf(0L to "SEARCHING...\r", 4500L to "7E8 06 41 00 BE 3F A8 13 00 \r\r>")
                  else listOf(0L to "SEARCHING...\r", 6000L to "UNABLE TO CONNECT\r\r>")
        "010C" -> listOf(50L to "7E8 04 41 0C 1A F8 00 00 00 \r\r>")
        "010D" -> listOf(50L to "7E8 03 41 0D 32 00 00 00 00 \r\r>")
        else -> if (cmd.startsWith("AT")) listOf(0L to "OK\r\r>") else listOf(50L to "NO DATA\r\r>")
    }

    /**
     * Tester connects with ignition off, then turns the key. The first request re-runs the protocol search,
     * which takes longer than the 2 s idle timeout. Afterwards every request must still get ITS OWN answer.
     */
    @Test
    fun searchLongerThanIdleTimeoutDesynchronizesLink() = runTest {
        val t = TimelineElm(vclock()).also { it.handler = ::cloneHandler }
        val link = ElmLink(t)
        link.initialize()
        ignitionOn = true
        val log = ArrayList<String>()
        repeat(4) { i ->
            try { log += "0100#$i -> " + link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x00)) }
            catch (e: LinkException) { log += "0100#$i -> ${e.message}" }
        }
        val rpm = runCatching { link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0C)) }
        println("REVIEW desync log: $log ; 010C -> $rpm ; sent=${t.sent}")
        val payload = rpm.getOrNull()?.firstOrNull()?.payload
        assertTrue(payload != null && payload[0] == 0x41.toByte() && payload[1] == 0x0C.toByte(),
            "010C did not get its own answer after a slow search: $rpm (log $log)")
    }

    /** Coroutine cancelled while the adapter is answering: the next request must not read the stale answer. */
    @Test
    fun cancellationMidRequestLeavesStaleOutput() = runTest {
        ignitionOn = true
        val t = TimelineElm(vclock()).also { tr ->
            tr.handler = { c -> if (c == "22F190") listOf(0L to "7E8 03 7F 22 78 00 00 00 00\r", 900L to "7E8 10 14 62 F1 90 57 56 57\r7E8 21 5A 5A 5A 33 43 5A 45\r7E8 22 30 30 30 30 30 31 AA\r\r>") else cloneHandler(c) }
        }
        val link = ElmLink(t)
        link.initialize()
        val job = async(start = CoroutineStart.UNDISPATCHED) { link.request(EcuAddress(0x7E0, 0x7E8), byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())) }
        delay(300)
        job.cancel()
        delay(2000) // adapter finishes its old answer in the meantime
        val r = runCatching { link.request(EcuAddress(0x7E0, 0x7E8), byteArrayOf(0x01, 0x0D)) }
        println("REVIEW after cancel: $r sent=${t.sent}")
        val p = r.getOrNull()?.firstOrNull()?.payload
        assertTrue(p != null && p[0] == 0x41.toByte() && p[1] == 0x0D.toByte(), "010D got $r after a cancelled request")
    }

    /** ECU keeps answering 7F 22 78 (each re-send restarts its work). Contract: give up after ~5 s. */
    @Test
    fun pendingBudgetIsRealTimeAndWritesAreResent() = runTest {
        ignitionOn = true
        val t = TimelineElm(vclock()).also { tr ->
            tr.handler = { c ->
                when {
                    c.startsWith("22") -> listOf(30L to "77E 03 7F 22 78 AA AA AA AA\r", 1020L to "\r>") // adapter waits ATSTFF after last frame
                    c.startsWith("2E") -> listOf(30L to "77E 03 7F 2E 78 AA AA AA AA\r", 1020L to "\r>")
                    else -> cloneHandler(c)
                }
            }
        }
        val link = ElmLink(t)
        link.initialize()
        val start = testScheduler.currentTime
        runCatching { link.request(EcuAddress(0x714, 0x77E), byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())) }
        val elapsed = testScheduler.currentTime - start
        val writes = runCatching { link.request(EcuAddress(0x714, 0x77E), byteArrayOf(0x2E, 0x01, 0x02, 0x03)) }
        println("REVIEW pending: elapsed=$elapsed ms, 22 sent ${t.sent.count { it.startsWith("22") }}x, 2E sent ${t.sent.count { it.startsWith("2E") }}x ($writes)")
        assertTrue(elapsed <= 7000, "pending budget documented as 5000 ms but took $elapsed ms")
        assertEquals(1, t.sent.count { it.startsWith("2E") }, "a write (0x2E) was re-sent on 7F 2E 78")
    }

    /** Adapter browned out (cranking) and came back with ATZ defaults: headers off, echo on. */
    @Test
    fun adapterResetToDefaultsIsReportedNotSilentlyEmpty() = runTest {
        ignitionOn = true
        var resetHappened = false
        val t = TimelineElm(vclock()).also { tr ->
            tr.handler = { c -> if (resetHappened && c == "010C") listOf(50L to "010C\r41 0C 1A F8 \r\r>") else cloneHandler(c) }
        }
        val link = ElmLink(t)
        link.initialize()
        resetHappened = true
        val r = runCatching { link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0C)) }
        println("REVIEW after adapter reset: $r")
        assertFalse(r.isSuccess && r.getOrThrow().isEmpty(), "header-less answer after an adapter reset reported as 'no ECU answered'")
    }

    @Test
    fun voltageFromBannerIsNot27Volts() = runTest {
        ignitionOn = true
        var stale = false
        val t = TimelineElm(vclock()).also { tr -> tr.handler = { c -> if (stale && c == "ATRV") listOf(0L to "ELM327 v1.5\r\r>") else cloneHandler(c) } }
        val link = ElmLink(t)
        link.initialize()
        stale = true
        val v = link.readBatteryVoltage()
        println("REVIEW voltage from banner: $v")
        assertEquals(null, v)
    }

    /**
     * ATZ on some clones / Wi-Fi adapters takes ~2 s before the banner. A rebooting chip ignores input (it does
     * not print STOPPED), so the model marks ATZ as deaf: what matters is that the driver waits long enough
     * and starts from a clean prompt.
     */
    @Test
    fun slowAtzDoesNotFailInit() = runTest {
        val t = TimelineElm(vclock()).also { tr -> tr.handler = { c -> if (c == "ATZ") listOf(1900L to "\r\rELM327 v1.5\r\r>") else cloneHandler(c) }; tr.deafWhileBusy += "ATZ" }
        val r = runCatching { ElmLink(t).initialize() }
        println("REVIEW slow ATZ: $r")
        assertTrue(r.isSuccess, "init failed with a 1.9 s ATZ: ${r.exceptionOrNull()?.message}")
        assertEquals(listOf("ATZ", "ATE0", "ATL0"), t.sent.take(3), "settings sent while the chip was still rebooting")
    }

    /** Banner later than the driver's ATZ wait: the input written meanwhile is ignored, init still succeeds. */
    @Test
    fun atzLaterThanTheWaitStillInitializes() = runTest {
        val t = TimelineElm(vclock()).also { tr -> tr.handler = { c -> if (c == "ATZ") listOf(3500L to "\r\rELM327 v1.5\r\r>") else cloneHandler(c) }; tr.deafWhileBusy += "ATZ" }
        val info = ElmLink(t).initialize()
        assertEquals(AdapterChip.ELM327_CLONE, info.chip)
        assertEquals("ELM327 v1.5", info.identity)
    }

    @Test
    fun simulatorStringFromThirdPartyIsNotCertified() {
        val p = AdapterProfile.classify(AdapterIdentity("ELM327 v1.5", "OBDII Simulator", null, null))
        println("REVIEW classify 'OBDII Simulator': ${p.chip} certified=${p.certified}")
        assertFalse(p.certified, "a string-controlled identity made a clone certified")
    }

    // --- expected-pass probes ------------------------------------------------------------------------

    @Test
    fun isoTpWrapPaddingNoSpacesAndForeignFrames() {
        val payload = ByteArray(300) { it.toByte() }
        val lines = ArrayList<String>()
        fun hex(b: List<Byte>) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } // lowercase
        lines += "77e" + hex(listOf<Byte>(0x11, 0x2C) + payload.take(6))
        var pos = 6; var seq = 1
        while (pos < 300) {
            val chunk = payload.drop(pos).take(7)
            lines += "77E" + hex(listOf((0x20 or seq).toByte()) + chunk + List(7 - chunk.size) { 0xAA.toByte() })
            if (seq == 3) lines += "7E8037F2278555555" + "55"
            pos += 7; seq = (seq + 1) and 0xF
        }
        val parsed = ElmResponseParser.parse(lines, io.carreader.core.link.BusProtocol.CAN_11_500)
        assertTrue(parsed.other.isEmpty(), "unparsed ${parsed.other}")
        val res = IsoTpReassembler.reassemble(parsed.canFrames.filter { it.id == 0x77E })
        assertEquals(1, res.messages.size, "incomplete ${res.incomplete}")
        assertTrue(res.messages[0].payload.contentEquals(payload))
    }

    @Test
    fun vagPhysicalThenFunctionalRestoresAutoFlowControl() = runTest {
        ignitionOn = true
        val t = TimelineElm(vclock()).also { it.handler = ::cloneHandler }
        val link = ElmLink(t)
        link.initialize()
        runCatching { link.request(EcuAddress(0x714, 0x77E), byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())) }
        val mark = t.sent.size
        runCatching { link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0C)) }
        val after = t.sent.drop(mark)
        println("REVIEW VAG then functional: ${t.sent}")
        assertTrue(t.sent.containsAll(listOf("ATSH714", "ATFCSH714", "ATFCSD300000", "ATFCSM1", "ATCRA77E")))
        assertTrue(after.containsAll(listOf("ATSH7DF", "ATAR", "ATFCSM0")), "functional after VAG: $after")
    }

    /** One laggy BLE answer (>1 s) during identity probing shifts every following answer by one command. */
    @Test
    fun laggyIdentityAnswerCannotCertifyAClone() = runTest {
        val t = TimelineElm(vclock()).also { tr -> tr.handler = { c -> if (c == "AT@1") listOf(1200L to "OBDII to RS232 Interpreter\r\r>") else cloneHandler(c) }; tr.lagged += "AT@1" }
        val info = ElmLink(t).initialize()
        println("REVIEW laggy identity: chip=${info.chip} certified=${info.certified} firmware=${info.firmware} identity=${info.identity}")
        assertFalse(info.certified, "clone certified after a laggy AT@1: $info")
    }

    // --- additional regression cases -----------------------------------------------------------------

    /** A clear (0x14) that gets 7F 14 78 is never repeated: the result is reported as unknown. */
    @Test
    fun clearAnsweredPendingIsNotResent() = runTest {
        ignitionOn = true
        val t = TimelineElm(vclock()).also { tr ->
            tr.handler = { c -> if (c.startsWith("14")) listOf(30L to "77E 03 7F 14 78 AA AA AA AA\r", 1020L to "\r>") else cloneHandler(c) }
        }
        val link = ElmLink(t)
        link.initialize()
        val e = runCatching { link.request(EcuAddress(0x714, 0x77E), byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) }.exceptionOrNull()
        assertTrue(e is LinkException && e.message!!.contains("unknown"), "got $e")
        assertEquals(1, t.sent.count { it.startsWith("14") })
    }

    /** After an adapter reset is reported, the next request re-applies the settings and works. */
    @Test
    fun requestAfterAdapterResetWorks() = runTest {
        ignitionOn = true
        var resetHappened = false
        val t = TimelineElm(vclock()).also { tr ->
            tr.handler = { c -> if (resetHappened && c == "010C") listOf(50L to "010C\r41 0C 1A F8 \r\r>") else cloneHandler(c) }
        }
        val link = ElmLink(t)
        link.initialize()
        resetHappened = true
        assertTrue(runCatching { link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0C)) }.exceptionOrNull() is LinkException)
        resetHappened = false
        val mark = t.sent.size
        val r = link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0D))
        val after = t.sent.drop(mark)
        assertTrue(after.containsAll(listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATSH7DF")), "no re-init: $after")
        assertEquals(0x41, r.single().payload[0].toInt() and 0xFF)
    }

    /** LV RESET in an answer is an adapter restart, not a generic error. */
    @Test
    fun lvResetIsDetected() = runTest {
        ignitionOn = true
        var lv = false
        val t = TimelineElm(vclock()).also { tr -> tr.handler = { c -> if (lv && c == "010C") listOf(10L to "LV RESET\r\r>") else cloneHandler(c) } }
        val link = ElmLink(t)
        link.initialize()
        lv = true
        val e = runCatching { link.request(EcuAddress.OBD_FUNCTIONAL, byteArrayOf(0x01, 0x0C)) }.exceptionOrNull()
        assertTrue(e is LinkException && e.message!!.contains("restarted"), "got $e")
    }

    @Test
    fun stiAnswerMustBeAnStnId() {
        for (sti in listOf("OK", "ELM327 v1.5", "STN", "stnX", "1.0")) {
            val p = AdapterProfile.classify(AdapterIdentity("ELM327 v1.5", null, sti, "OBDLink MX+"))
            assertFalse(p.certified, "STI '$sti' certified a clone")
        }
        assertEquals(AdapterChip.STN, AdapterProfile.classify(AdapterIdentity("ELM327 v1.4b", null, " stn1110 v4.2.1 ", null)).chip)
    }

    @Test
    fun voltageMustBeOneAnchoredPlausibleLine() = runTest {
        ignitionOn = true
        var answer = "12.4V"
        val t = TimelineElm(vclock()).also { tr -> tr.handler = { c -> if (c == "ATRV") listOf(0L to "$answer\r\r>") else cloneHandler(c) } }
        val link = ElmLink(t)
        link.initialize()
        assertEquals(12.4, link.readBatteryVoltage())
        for (bad in listOf("27V", "0.4V", "12.4V 13.0V", "ATRV 12.4V", "v1.5", "4.9V", "20.5V")) {
            answer = bad
            assertEquals(null, link.readBatteryVoltage(), "accepted '$bad'")
        }
        answer = "14V"
        assertEquals(14.0, link.readBatteryVoltage())
    }

    /** BUFFER FULL on a multi-frame read with explicit flow control: retried once with a larger STmin. */
    @Test
    fun bufferFullRetriesOnceWithLargerStmin() = runTest {
        ignitionOn = true
        var stmin = "00"
        val t = TimelineElm(vclock()).also { tr ->
            tr.handler = { c ->
                when {
                    c.startsWith("ATFCSD3000") -> { stmin = c.takeLast(2); listOf(0L to "OK\r\r>") }
                    c == "22F187" -> if (stmin == "00") listOf(50L to "77E 10 0E 62 F1 87 35 51 30\rBUFFER FULL\r\r>")
                        else listOf(50L to "77E 10 0E 62 F1 87 35 51 30\r77E 21 39 30 37 35 33 30 20\r77E 22 20 AA AA AA AA AA AA\r\r>")
                    else -> cloneHandler(c)
                }
            }
        }
        val link = ElmLink(t)
        link.initialize()
        val r = link.request(EcuAddress(0x714, 0x77E), byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()))
        assertEquals(14, r.single().payload.size)
        assertEquals(2, t.sent.count { it == "22F187" })
        assertTrue("ATFCSD30000A" in t.sent, "sent ${t.sent}")
    }
}
