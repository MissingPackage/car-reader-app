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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [DiagnosticLink] for ELM327-compatible adapters (genuine, clones) and STN chips (OBDLink).
 *
 * Init: `ATZ` (banner awaited up to [RESET_TIMEOUT_MS], then silence), `ATE0` (retried once), `ATL0`, `ATS0`,
 * `ATH1`, `ATAT1`, `ATSP0`, identity (`ATI`, `AT@1`, `STI`, `STDI`), then `0100` to trigger the automatic
 * protocol search and `ATDPN` to read the result, and `ATRV` for voltage. If no bus answers (ignition
 * off), [initialize] still succeeds with [busProtocol] null and a note; the next request searches again.
 *
 * Staying in step with the adapter (the ELM327 protocol has no request ids: a missed prompt means the next
 * read gets the previous answer):
 * - every exchange checks that the `>` prompt arrived; [ElmChannel.dirty] stays set after a missed prompt,
 *   a timeout, bytes after the prompt, `STOPPED`, a transport error or a cancellation in mid-exchange;
 * - before the next command a dirty channel is resynchronized ([resync]): discard pending output, then
 *   `ATE0` rounds until one ends with `OK`, a prompt and silence. A start-up banner, `LV RESET` or the echo
 *   coming back on means the adapter restarted: its settings are re-applied and the addressing cache reset;
 * - a protocol search (`SEARCHING...`, `BUS INIT: ...`) may stay silent for seconds (5-baud K-line inits):
 *   the driver keeps waiting for the prompt up to [SEARCH_BUDGET_MS];
 * - an adapter that restarted mid-session (echo on again, `LV RESET`, banner, header-less CAN data) makes
 *   the request fail with a [LinkException]; the adapter is re-initialized before the next request.
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
 *   [LinkException] (usually a flow-control problem). `BUFFER FULL` with explicit flow control on a read
 *   request is retried once with a larger STmin.
 * - UDS "response pending" (`7F <sid> 78`): the adapter stops listening after its own timeout. Read-only
 *   requests ([isRepeatable]) are re-sent until the final answer arrives, for at most [PENDING_BUDGET_MS] of
 *   elapsed time (dispatcher time: real time in the app, virtual time in tests). Any other service (clears,
 *   writes, routines...) is never repeated: the request fails with a [LinkException] saying its result is
 *   unknown.
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

    /** The adapter restarted (or may have): settings must be re-applied before the next request. */
    private var needsReinit = false
    /** ATE0 demonstrably turned echo off, so an echo seen later means the adapter was reset. */
    private var echoOffVerified = false
    private var initializing = false
    private var reinitDepth = 0

    override val busProtocol: BusProtocol? get() = protocol
    override val adapterInfo: AdapterInfo? get() = info

    /** Capability object discovered at init; null before [initialize]. */
    val adapterProfile: AdapterProfile? get() = profile

    override suspend fun initialize(): AdapterInfo = mutex.withLock {
        profile = null
        info = null
        protocol = null
        needsReinit = false
        echoOffVerified = false
        initializing = true
        try {
            transport.connect()
            channel.drainText()
            channel.markClean()
            addressing.reset()

            // Slow clones and Wi-Fi adapters print the banner after ~2 s and ignore input while rebooting.
            val reset = channel.command("ATZ", RESET_TIMEOUT_MS)
            if (!reset.promptSeen || channel.drainText(SETTLE_MS).isNotBlank() || channel.dirty) {
                channel.markDirty()
                resync() // a banner here is expected: no re-init needed, the settings come next anyway
            }
            val headers = applySettings()

            val ati = identityLine("ATI")
            val at1 = identityLine("AT@1")
            val sti = identityLine("STI")
            val stdi = identityLine("STDI")
            val identity = AdapterIdentity(
                ati = ati ?: reset.lines.lastOrNull { it.contains("ELM", ignoreCase = true) },
                at1 = at1,
                sti = sti,
                stdi = stdi,
            )
            val p = AdapterProfile.classify(identity, transport.isSimulated)
            val notes = ArrayList<String>()
            if (sti != null && !AdapterProfile.isStnId(sti)) notes += "STI answered '$sti': not an STN firmware id, treated as ELM327"

            val search = cmd("0100", SEARCH_TIMEOUT_MS, SEARCH_BUDGET_MS)
            if (!search.promptSeen) {
                notes += "Protocol search did not finish within ${SEARCH_BUDGET_MS / 1000} s"
            } else {
                val parsed = ElmResponseParser.parse(search.lines, null)
                when {
                    ElmStatus.UNABLE_TO_CONNECT in parsed.statuses -> notes += "No vehicle bus detected (UNABLE TO CONNECT): is the ignition on?"
                    parsed.firstError != null -> notes += "Protocol search: ${parsed.firstError}"
                    !parsed.hasData -> notes += "Protocol search: no ECU answered 0100"
                }
            }
            refreshProtocol()
            if (protocol == null) channel.note("Protocol not detected yet", Direction.ERROR)

            val voltage = readVoltageLocked()
            profile = p
            info = p.toAdapterInfo(identity, voltage != null, headers, notes).also {
                channel.note("Adapter: ${it.identity} (${it.chip}), protocol ${protocol?.label ?: "none"}")
            }
            info!!
        } finally {
            initializing = false
        }
    }

    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> = mutex.withLock {
        if (profile == null) throw LinkException("Adapter not initialized")
        require(payload.isNotEmpty()) { "Empty request" }
        ensureReady()
        val sid = payload[0].toInt() and 0xFF
        val idle = options.timeoutMs.coerceAtLeast(MIN_IDLE_TIMEOUT_MS)
        configureIfCan(target, payload, options)

        var parsed = exchange(payload, idle)
        if (parsed.firstError == ElmStatus.BUFFER_FULL && canRetryBufferFull(target, payload)) {
            channel.note("BUFFER FULL: asking for STmin ${addressing.flowControlStmin} ms and repeating ${payload.toHex()} once", Direction.ERROR)
            configureIfCan(target, payload, options)
            parsed = exchange(payload, idle)
        }
        checkErrors(parsed, payload)

        val finals = LinkedHashMap<Int?, MutableList<EcuResponse>>()
        val waiting = LinkedHashSet<Int?>()
        collect(parsed, target, sid, firstRound = true, finals, waiting)
        if (waiting.isEmpty()) return@withLock finals.values.flatten()

        val who = { waiting.joinToString { it?.toString(16) ?: "?" } }
        if (!isRepeatable(payload)) {
            throw LinkException(
                "ECU ${who()} answered 'response pending' to service 0x${sid.toString(16).uppercase()} and the adapter " +
                    "stopped listening before the final answer. The request is not repeated because it can change " +
                    "the ECU: its result is unknown",
            )
        }
        // Budget in elapsed (dispatcher) time, not in an estimate of what each round costs.
        val done = withTimeoutOrNull(PENDING_BUDGET_MS) {
            while (waiting.isNotEmpty()) {
                channel.note("Response pending from ${who()}: re-sending")
                delay(PENDING_RETRY_DELAY_MS)
                val again = exchange(payload, idle)
                checkErrors(again, payload)
                collect(again, target, sid, firstRound = false, finals, waiting)
            }
            true
        }
        if (done == null) throw LinkException("ECU ${who()} still busy (response pending) after $PENDING_BUDGET_MS ms")
        finals.values.flatten()
    }

    override suspend fun readBatteryVoltage(): Double? = mutex.withLock {
        try {
            if (profile != null) ensureReady()
            readVoltageLocked()
        } catch (e: LinkException) {
            channel.note("Voltage not read: ${e.message}", Direction.ERROR)
            null
        }
    }

    override suspend fun close() = mutex.withLock {
        try {
            transport.close()
        } finally {
            profile = null
            info = null
            protocol = null
        }
    }

    // --- staying in step ---------------------------------------------------------------------------

    /** Before a request: resynchronize a dirty channel and re-apply settings after an adapter reset. */
    private suspend fun ensureReady() {
        if (channel.dirty && resync()) needsReinit = true
        if (needsReinit) reinit()
    }

    /** Every command goes through here: a dirty channel is resynchronized before anything is written. */
    private suspend fun cmd(command: String, timeoutMs: Long, searchBudgetMs: Long = 0): ElmReply {
        if (channel.dirty && resync()) {
            if (reinitDepth > 0) throw LinkException("The adapter restarted again while being re-initialized")
            reinit()
            if (!initializing) {
                throw LinkException("The adapter restarted before '$command' could be sent; its settings were restored, retry the request")
            }
        }
        return channel.command(command, timeoutMs, searchBudgetMs)
    }

    /**
     * Brings the adapter back to a clean prompt: discards pending output, then sends `ATE0` (harmless, and
     * never a bare CR, which would repeat the last command) until a round ends with `OK`, the prompt and
     * [SETTLE_MS] of silence. A byte written while the adapter is still busy aborts its current operation
     * (`STOPPED`), so the first round may be lost; [RESYNC_ATTEMPTS] rounds bound the time spent.
     * Returns true when the output showed that the adapter restarted.
     */
    private suspend fun resync(): Boolean {
        channel.note("Adapter out of step (missed prompt, timeout or cancelled request): resynchronizing", Direction.ERROR)
        val seen = StringBuilder(channel.drainText(SETTLE_MS))
        var echoSeen = false
        repeat(RESYNC_ATTEMPTS) {
            val r = channel.command("ATE0", COMMAND_TIMEOUT_MS)
            val trailing = channel.drainText(SETTLE_MS)
            seen.append(r.raw).append(trailing)
            if (ElmChannel.splitLines(r.raw).any { ElmChannel.norm(it) == "ATE0" }) echoSeen = true
            if (r.promptSeen && trailing.isBlank() && r.lines.lastOrNull()?.equals("OK", ignoreCase = true) == true) {
                channel.markClean()
                val reason = resetReason(seen.toString(), echoSeen && echoOffVerified)
                if (reason != null) channel.note("Adapter restart detected while resynchronizing ($reason)", Direction.ERROR)
                return reason != null
            }
        }
        channel.markDirty()
        throw LinkException("The adapter does not answer cleanly (no prompt after $RESYNC_ATTEMPTS attempts): reconnect it")
    }

    /** Re-applies the init settings after an adapter restart and forgets every cached setting. */
    private suspend fun reinit() {
        channel.note("Re-applying adapter settings after a restart", Direction.ERROR)
        needsReinit = false
        addressing.reset()
        protocol = null
        reinitDepth++
        var ok = false
        try {
            applySettings()
            ok = true
        } finally {
            reinitDepth--
            if (!ok) needsReinit = true
        }
    }

    /** `ATE0` (retried once), `ATL0`, `ATS0`, `ATH1`, `ATAT1`, `ATSP0`. Returns whether headers are on. */
    private suspend fun applySettings(): Boolean {
        disableEcho()
        val l0 = cmd("ATL0", COMMAND_TIMEOUT_MS)
        echoOffVerified = l0.promptSeen && !l0.echoed
        if (l0.echoed) channel.note("Adapter still echoes after ATE0: restarts cannot be detected from the echo", Direction.ERROR)
        if (!isOk(l0)) channel.note("Adapter does not support ATL0", Direction.INFO)
        optional("ATS0")
        val headers = optional("ATH1")
        if (!headers) throw LinkException("Adapter does not support headers (ATH1): not usable for diagnostics")
        optional("ATAT1")
        required("ATSP0")
        return headers
    }

    private suspend fun disableEcho() {
        val first = cmd("ATE0", COMMAND_TIMEOUT_MS)
        if (first.promptSeen && isOk(first)) return
        channel.note("ATE0 not acknowledged (${first.lines}): retrying once", Direction.ERROR)
        if (first.promptSeen) channel.drainText(SETTLE_MS)
        val second = cmd("ATE0", COMMAND_TIMEOUT_MS) // resynchronizes first if the prompt was missed
        if (!second.promptSeen) throw LinkException("No answer from the adapter to ATE0")
        if (!isOk(second)) throw LinkException("Adapter rejected ATE0: ${second.lines}")
    }

    private fun resetReason(text: String, echoBack: Boolean): String? = when {
        echoBack -> "echo back on"
        text.uppercase().contains("LV RESET") -> "LV RESET"
        BANNER.containsMatchIn(text) -> "start-up banner"
        else -> null
    }

    // --- request internals ---------------------------------------------------------------------------

    private suspend fun configureIfCan(target: EcuAddress, payload: ByteArray, options: RequestOptions) {
        val proto = protocol
        val p = profile ?: throw LinkException("Adapter not initialized")
        if (proto == null || proto.isCan) configureCan(target, payload, options, proto, p)
    }

    /** One request/answer round with prompt and restart checks; errors are left to [checkErrors]. */
    private suspend fun exchange(payload: ByteArray, idleMs: Long): ParsedReply {
        val reply = cmd(payload.toHex(""), idleMs, SEARCH_BUDGET_MS)
        if (!reply.promptSeen) {
            throw LinkException("Adapter did not finish answering ${payload.toHex()} within $idleMs ms; it will be resynchronized before the next request")
        }
        resetReason(reply.lines.joinToString("\n"), reply.echoed && echoOffVerified)?.let { adapterRestarted(it, payload) }
        if (protocol == null) refreshProtocol()
        val parsed = ElmResponseParser.parse(reply.lines, protocol)
        if (protocol?.isCan == true && !parsed.hasData && parsed.other.isNotEmpty() && parsed.other.all { looksHeaderless(it, payload) }) {
            adapterRestarted("answer without CAN headers", payload)
        }
        if (ElmStatus.STOPPED in parsed.statuses) channel.markDirty()
        if (parsed.other.isNotEmpty()) channel.note("Unparsed line(s): ${parsed.other}", Direction.ERROR)
        return parsed
    }

    private fun adapterRestarted(reason: String, payload: ByteArray): Nothing {
        needsReinit = true
        channel.markDirty()
        channel.note("Adapter restart detected ($reason)", Direction.ERROR)
        throw LinkException(
            "The adapter restarted ($reason, power dip?) and lost its settings: the answer to ${payload.toHex()} is unusable. " +
                "It is re-initialized before the next request",
        )
    }

    /** A data line printed with headers off: the service's positive (sid + 0x40) or negative (7F sid) answer. */
    private fun looksHeaderless(line: String, payload: ByteArray): Boolean {
        val hex = line.filter { it != ' ' }
        if (hex.length < 2 || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return false
        val sid = payload[0].toInt() and 0xFF
        val first = hex.substring(0, 2).toInt(16)
        return first == sid + 0x40 || (first == 0x7F && hex.length >= 4 && hex.substring(2, 4).toInt(16) == sid)
    }

    private fun checkErrors(parsed: ParsedReply, payload: ByteArray) {
        parsed.firstError?.let { err ->
            if (!parsed.hasData || err != ElmStatus.DATA_ERROR && err != ElmStatus.RX_ERROR) throw LinkException(errorText(err, payload))
            channel.note("Ignoring corrupted line(s): $err", Direction.ERROR)
        }
    }

    private fun canRetryBufferFull(target: EcuAddress, payload: ByteArray): Boolean {
        val proto = protocol
        if (proto != null && !proto.isCan) return false
        if (!isRepeatable(payload)) return false
        val extended = proto?.extendedId ?: target.extendedId
        return addressing.usesExplicitFlowControl(target, extended) && addressing.raiseStmin()
    }

    private fun collect(
        parsed: ParsedReply,
        target: EcuAddress,
        sid: Int,
        firstRound: Boolean,
        finals: MutableMap<Int?, MutableList<EcuResponse>>,
        waiting: MutableSet<Int?>,
    ) {
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
    }

    private suspend fun configureCan(target: EcuAddress, payload: ByteArray, options: RequestOptions, proto: BusProtocol?, p: AdapterProfile) {
        val extended = proto?.extendedId ?: target.extendedId
        for (setting in addressing.plan(target, payload, options.timeoutMs, extended, p)) {
            val cur = profile ?: p
            val reply = cmd(setting.command, COMMAND_TIMEOUT_MS)
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
        val reply = cmd("ATDPN", COMMAND_TIMEOUT_MS)
        if (!reply.promptSeen) {
            protocol = null
            return
        }
        // "6" (fixed) or "A6" (found by automatic search); "A0"/"0" = not determined yet.
        val code = reply.lines.lastOrNull()?.trim()?.uppercase()
        protocol = when {
            code.isNullOrEmpty() || code.length > 2 || ElmResponseParser.statusOf(code) != null -> null
            else -> BusProtocol.fromElmCode(code.last()).takeIf { it != BusProtocol.UNKNOWN }
        }
        protocol?.let { channel.note("Protocol: ${it.label}") }
    }

    /** `ATRV`: exactly one answer line like `12.4V`, between 5 and 20 V; anything else is null. */
    private suspend fun readVoltageLocked(): Double? {
        val reply = cmd("ATRV", COMMAND_TIMEOUT_MS)
        if (!reply.promptSeen) return null
        resetReason(reply.lines.joinToString("\n"), reply.echoed && echoOffVerified)?.let {
            needsReinit = true
            channel.note("Adapter restart detected in the ATRV answer ($it)", Direction.ERROR)
            return null
        }
        val line = reply.lines.singleOrNull()?.trim() ?: return null
        if (!VOLTAGE.matches(line)) return null
        return line.dropLast(1).toDoubleOrNull()?.takeIf { it in MIN_VOLTAGE..MAX_VOLTAGE }
    }

    private fun isOk(reply: ElmReply) = reply.lines.any { it.equals("OK", ignoreCase = true) }

    private suspend fun required(command: String) {
        val reply = cmd(command, COMMAND_TIMEOUT_MS)
        if (!reply.promptSeen) throw LinkException("No answer from the adapter to $command")
        if (!isOk(reply)) throw LinkException("Adapter rejected $command: ${reply.lines}")
    }

    private suspend fun optional(command: String): Boolean {
        val reply = cmd(command, COMMAND_TIMEOUT_MS)
        val ok = reply.promptSeen && isOk(reply)
        if (!ok) channel.note("Adapter does not support $command" + if (reply.promptSeen) "" else " (no prompt)", Direction.INFO)
        return ok
    }

    /** One identity answer, or null for `?`, a status line or a missed prompt (never a guess from late text). */
    private suspend fun identityLine(command: String): String? {
        val reply = cmd(command, COMMAND_TIMEOUT_MS)
        if (!reply.promptSeen) return null
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
        /** How long the `ATZ` banner may take (slow clones and Wi-Fi adapters: about 2 s). */
        const val RESET_TIMEOUT_MS = 3000L
        const val COMMAND_TIMEOUT_MS = 1000L
        /** Idle timeout of the init `0100`. */
        const val SEARCH_TIMEOUT_MS = 10_000L
        /** Total silence tolerated while the adapter reports a protocol search in progress. */
        const val SEARCH_BUDGET_MS = 20_000L
        const val MIN_IDLE_TIMEOUT_MS = 500L
        const val PENDING_BUDGET_MS = 5000L
        const val PENDING_RETRY_DELAY_MS = 250L
        /** Silence that proves the adapter has finished printing. */
        const val SETTLE_MS = 150L
        const val RESYNC_ATTEMPTS = 4
        const val MIN_VOLTAGE = 5.0
        const val MAX_VOLTAGE = 20.0
        private val VOLTAGE = Regex("^\\d{1,2}(\\.\\d{1,2})?V$", RegexOption.IGNORE_CASE)
        private val BANNER = Regex("ELM327\\s*v?\\d", RegexOption.IGNORE_CASE)

        /**
         * Requests that only read, so repeating them after `7F <sid> 78` is safe: 0x19, 0x22, 0x3E, 0x10 with
         * the default (01) or extended (03) session, OBD services 0x01-0x0A except 0x04 (clear). Never 0x14,
         * 0x04, 0x2E, 0x31 or any other service.
         */
        fun isRepeatable(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            return when (val sid = payload[0].toInt() and 0xFF) {
                0x19, 0x22, 0x3E -> true
                0x10 -> payload.size >= 2 && (payload[1].toInt() and 0x7F) in setOf(0x01, 0x03)
                in 0x01..0x0A -> sid != 0x04
                else -> false
            }
        }
    }
}
