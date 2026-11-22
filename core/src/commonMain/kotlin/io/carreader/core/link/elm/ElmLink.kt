package io.carreader.core.link.elm

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.LinkException
import io.carreader.core.link.RequestOptions
import io.carreader.core.log.Direction
import io.carreader.core.log.TrafficLog
import io.carreader.core.transport.Transport
import io.carreader.core.util.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DiagnosticLink] for ELM327-compatible adapters (genuine, clones) and STN chips (OBDLink).
 *
 * Init: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATAT1`, `ATSP0`, identity (`ATI`, `AT@1`, `STI`, `STDI`), then
 * `0100` to trigger the automatic protocol search and `ATDPN` to read the result, and `ATRV` for voltage.
 * If no bus answers (ignition off), [initialize] still succeeds with [busProtocol] null and a note; the
 * next request searches again.
 *
 * Responses:
 * - CAN (protocols 6-9): headers are on, so the driver sees raw frames and does ISO-TP reassembly itself
 *   ([IsoTpReassembler]), grouping by source id. One [EcuResponse] per reassembled message.
 * - ISO 9141-2, ISO 14230-4, J1850: each printed line is one ECU message (header bytes + data + checksum);
 *   the driver returns ONE [EcuResponse] per line and does not merge lines. Multi-message answers (e.g.
 *   service 09 PID 02 VIN in five messages on ISO 9141-2) are merged by the OBD layer. [EcuResponse.sourceId]
 *   is the sender address (third header byte, e.g. 0x10 for the engine).
 * - `NO DATA` → empty list. `UNABLE TO CONNECT`, `CAN ERROR`, `BUS ERROR`, `BUFFER FULL`, `STOPPED`, `?` and
 *   other adapter errors → [LinkException]. An incomplete multi-frame answer to a physical request →
 *   [LinkException] (usually a flow-control problem).
 * - UDS "response pending" (`7F <sid> 78`): the adapter stops listening after its own timeout, so the driver
 *   re-sends the request (only read services and idempotent clears go through this port) until the final
 *   answer arrives, for at most [PENDING_BUDGET_MS].
 *
 * One request at a time (a [Mutex]): the ELM327 protocol is strictly half-duplex.
 *
 * [RequestOptions.singleResponse] is currently not used to shorten the wait: the ELM327 response-count
 * hint counts frames, not ISO-TP messages, and would cut multi-frame answers. An STN-specific optimization
 * can use [AdapterProfile] to enable it later.
 *
 * Timeouts: [RequestOptions.timeoutMs] is the transport idle timeout while waiting for the prompt. The
 * adapter itself gives up after its `ATST` time and prints `NO DATA`.
 */
class ElmLink(
    private val transport: Transport,
    private val log: TrafficLog = TrafficLog.None,
    private val clock: () -> Long = { 0L },
) : DiagnosticLink {
    private val mutex = Mutex()
    private val channel = ElmChannel(transport, log, clock)
    private val addressing = ElmAddressing()

    private var protocol: BusProtocol? = null
    private var info: AdapterInfo? = null
    private var profile: AdapterProfile? = null

    override val busProtocol: BusProtocol? get() = protocol
    override val adapterInfo: AdapterInfo? get() = info

    /** Capability object discovered at init; null before [initialize]. */
    val adapterProfile: AdapterProfile? get() = profile

    override suspend fun initialize(): AdapterInfo = mutex.withLock {
        transport.connect()
        channel.drain()
        addressing.reset()
        protocol = null

        val reset = channel.command("ATZ", RESET_TIMEOUT_MS)
        if (!reset.promptSeen) channel.drain(200)
        required("ATE0")
        optional("ATL0")
        optional("ATS0")
        val headers = optional("ATH1")
        if (!headers) throw LinkException("Adapter does not support headers (ATH1): not usable for diagnostics")
        optional("ATAT1")
        required("ATSP0")

        val identity = AdapterIdentity(
            ati = identityLine("ATI") ?: reset.lines.lastOrNull { it.contains("ELM", ignoreCase = true) },
            at1 = identityLine("AT@1"),
            sti = identityLine("STI"),
            stdi = identityLine("STDI"),
        )
        val p = AdapterProfile.classify(identity)
        val notes = ArrayList<String>()

        val search = channel.command("0100", SEARCH_TIMEOUT_MS)
        val parsed = ElmResponseParser.parse(search.lines, null)
        when {
            ElmStatus.UNABLE_TO_CONNECT in parsed.statuses -> notes += "No vehicle bus detected (UNABLE TO CONNECT): is the ignition on?"
            parsed.firstError != null -> notes += "Protocol search: ${parsed.firstError}"
            !parsed.hasData -> notes += "Protocol search: no ECU answered 0100"
        }
        refreshProtocol()
        if (protocol == null) channel.note("Protocol not detected yet", Direction.ERROR)

        val voltage = readVoltageLocked()
        profile = p
        info = p.toAdapterInfo(identity, voltage != null, headers, notes).also {
            channel.note("Adapter: ${it.identity} (${it.chip}), protocol ${protocol?.label ?: "none"}")
        }
        info!!
    }

    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> = mutex.withLock {
        val p = profile ?: throw LinkException("Adapter not initialized")
        require(payload.isNotEmpty()) { "Empty request" }
        val sid = payload[0].toInt() and 0xFF
        val proto = protocol
        if (proto == null || proto.isCan) configureCan(target, payload, options, proto, p)

        val finals = LinkedHashMap<Int?, MutableList<EcuResponse>>()
        val waiting = LinkedHashSet<Int?>()
        var firstRound = true
        var waitedMs = 0L
        while (true) {
            val reply = channel.command(payload.toHex(""), options.timeoutMs.coerceAtLeast(MIN_IDLE_TIMEOUT_MS))
            if (!reply.promptSeen) throw LinkException("Adapter did not finish answering ${payload.toHex()} within ${options.timeoutMs} ms")
            if (protocol == null) refreshProtocol()
            val parsed = ElmResponseParser.parse(reply.lines, protocol)
            parsed.firstError?.let { err ->
                if (!parsed.hasData || err != ElmStatus.DATA_ERROR && err != ElmStatus.RX_ERROR) throw LinkException(errorText(err, payload))
                channel.note("Ignoring corrupted line(s): $err", Direction.ERROR)
            }
            if (parsed.other.isNotEmpty()) channel.note("Unparsed line(s): ${parsed.other}", Direction.ERROR)

            for (m in toResponses(parsed, target)) {
                // After a re-send, the ECUs that already answered answer again: keep their first answer.
                if (!firstRound && m.sourceId !in waiting) continue
                if (isPending(m.payload, sid)) {
                    if (m.sourceId !in finals) waiting += m.sourceId
                } else {
                    waiting -= m.sourceId
                    finals.getOrPut(m.sourceId) { ArrayList() } += m
                }
            }
            firstRound = false
            if (waiting.isEmpty()) break
            val who = waiting.joinToString { it?.toString(16) ?: "?" }
            if (waitedMs >= PENDING_BUDGET_MS) throw LinkException("ECU $who still busy (response pending) after $PENDING_BUDGET_MS ms")
            channel.note("Response pending from $who: re-sending")
            delay(PENDING_RETRY_DELAY_MS)
            waitedMs += PENDING_RETRY_DELAY_MS + ADAPTER_WAIT_ESTIMATE_MS
        }
        finals.values.flatten()
    }

    override suspend fun readBatteryVoltage(): Double? = mutex.withLock { readVoltageLocked() }

    override suspend fun close() = mutex.withLock {
        try {
            transport.close()
        } finally {
            profile = null
            info = null
            protocol = null
        }
    }

    // --- internals ---------------------------------------------------------------------------------

    private suspend fun configureCan(target: EcuAddress, payload: ByteArray, options: RequestOptions, proto: BusProtocol?, p: AdapterProfile) {
        val extended = proto?.extendedId ?: target.extendedId
        for (setting in addressing.plan(target, payload, options.timeoutMs, extended, p)) {
            val cur = profile ?: p
            val reply = channel.command(setting.command, COMMAND_TIMEOUT_MS)
            val ok = reply.promptSeen && reply.lines.any { it.equals("OK", ignoreCase = true) }
            if (ok) {
                addressing.applied(setting)
                continue
            }
            addressing.forget(setting.key)
            when (setting.key) {
                ElmSetting.Key.TIMEOUT -> {
                    channel.note("Adapter rejected ${setting.command}: keeping its default timeout", Direction.ERROR)
                    profile = cur.copy(supportsTimeout = false)
                }
                ElmSetting.Key.RECEIVE_FILTER -> {
                    channel.note("Adapter rejected ${setting.command}: filtering responses in software", Direction.ERROR)
                    profile = cur.copy(supportsReceiveFilter = false)
                }
                ElmSetting.Key.PRIORITY -> {
                    channel.note("Adapter rejected ${setting.command}", Direction.ERROR)
                    profile = cur.copy(supportsCanPriority = false)
                }
                ElmSetting.Key.FC_HEADER, ElmSetting.Key.FC_DATA, ElmSetting.Key.FC_MODE -> {
                    if (setting.value != "0") {
                        profile = cur.copy(supportsFlowControl = false)
                        throw LinkException("Adapter rejected ${setting.command}: cannot reach ECU 0x${target.requestId.toString(16)} (no flow control support)")
                    }
                    channel.note("Adapter rejected ${setting.command}", Direction.ERROR)
                }
                ElmSetting.Key.HEADER -> throw LinkException("Adapter rejected ${setting.command}")
            }
        }
    }

    /** Converts parsed frames/lines to responses, filtering out senders other than the expected one. */
    private fun toResponses(parsed: ParsedReply, target: EcuAddress): List<EcuResponse> {
        val out = ArrayList<EcuResponse>()
        if (parsed.canFrames.isNotEmpty()) {
            val extended = protocol?.extendedId ?: target.extendedId
            val resolved = runCatching { ElmAddressing.resolve(target, extended) }.getOrNull()
            val expected = resolved?.responseId?.takeIf { resolved.functional.not() }
            val frames = if (expected == null) parsed.canFrames else parsed.canFrames.filter { it.id == expected }
            if (frames.size != parsed.canFrames.size) channel.note("Dropped ${parsed.canFrames.size - frames.size} frame(s) from unexpected senders")
            val result = IsoTpReassembler.reassemble(frames)
            for (inc in result.incomplete) {
                val text = "Incomplete multi-frame answer from 0x${inc.sourceId.toString(16)}: ${inc.receivedLength} of ${inc.expectedLength} bytes (${inc.reason})"
                if (resolved?.functional == false && result.messages.none { it.sourceId == inc.sourceId }) {
                    throw LinkException("$text. Flow control not accepted by the ECU?")
                }
                channel.note(text, Direction.ERROR)
            }
            result.messages.forEach { out += EcuResponse(it.sourceId, it.payload) }
        }
        parsed.serialMessages.forEach { out += EcuResponse(it.source, it.data) }
        return out
    }

    private fun isPending(payload: ByteArray, sid: Int): Boolean =
        payload.size >= 3 && (payload[0].toInt() and 0xFF) == 0x7F && (payload[1].toInt() and 0xFF) == sid && (payload[2].toInt() and 0xFF) == 0x78

    private suspend fun refreshProtocol() {
        val reply = channel.command("ATDPN", COMMAND_TIMEOUT_MS)
        // "6" (fixed) or "A6" (found by automatic search); "A0"/"0" = not determined yet.
        val code = reply.lines.lastOrNull()?.trim()?.uppercase()
        protocol = when {
            code.isNullOrEmpty() || code.length > 2 || ElmResponseParser.statusOf(code) != null -> null
            else -> BusProtocol.fromElmCode(code.last()).takeIf { it != BusProtocol.UNKNOWN }
        }
        protocol?.let { channel.note("Protocol: ${it.label}") }
    }

    private suspend fun readVoltageLocked(): Double? {
        val reply = channel.command("ATRV", COMMAND_TIMEOUT_MS)
        return reply.lines.firstNotNullOfOrNull { VOLTAGE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
    }

    private suspend fun required(command: String) {
        val reply = channel.command(command, COMMAND_TIMEOUT_MS)
        if (!reply.promptSeen) throw LinkException("No answer from the adapter to $command")
        if (reply.lines.none { it.equals("OK", ignoreCase = true) }) throw LinkException("Adapter rejected $command: ${reply.lines}")
    }

    private suspend fun optional(command: String): Boolean {
        val reply = channel.command(command, COMMAND_TIMEOUT_MS)
        val ok = reply.lines.any { it.equals("OK", ignoreCase = true) }
        if (!ok) channel.note("Adapter does not support $command", Direction.INFO)
        return ok
    }

    private suspend fun identityLine(command: String): String? {
        val reply = channel.command(command, COMMAND_TIMEOUT_MS)
        return reply.lines.firstOrNull()?.takeIf { it != "?" && ElmResponseParser.statusOf(it) == null }
    }

    private fun errorText(status: ElmStatus, payload: ByteArray): String = when (status) {
        ElmStatus.UNABLE_TO_CONNECT -> "No vehicle bus (UNABLE TO CONNECT): check the ignition"
        ElmStatus.BUS_INIT_ERROR -> "Bus initialization failed (BUS INIT: ERROR)"
        ElmStatus.CAN_ERROR -> "CAN error: wrong bus speed or wiring"
        ElmStatus.BUFFER_FULL -> "Adapter buffer full: answer to ${payload.toHex()} lost"
        ElmStatus.STOPPED -> "Adapter stopped while answering ${payload.toHex()}"
        ElmStatus.UNKNOWN_COMMAND -> "Adapter rejected request ${payload.toHex()} (?)"
        else -> "Adapter error $status for ${payload.toHex()}"
    }

    companion object {
        const val RESET_TIMEOUT_MS = 1500L
        const val COMMAND_TIMEOUT_MS = 1000L
        const val SEARCH_TIMEOUT_MS = 10_000L
        const val MIN_IDLE_TIMEOUT_MS = 500L
        const val PENDING_BUDGET_MS = 5000L
        const val PENDING_RETRY_DELAY_MS = 100L
        /** What one re-sent request costs on top of the retry delay (the adapter's default ATST wait). */
        private const val ADAPTER_WAIT_ESTIMATE_MS = 200L
        private val VOLTAGE = Regex("(\\d{1,2}(?:\\.\\d+)?)\\s*V", RegexOption.IGNORE_CASE)
    }
}
