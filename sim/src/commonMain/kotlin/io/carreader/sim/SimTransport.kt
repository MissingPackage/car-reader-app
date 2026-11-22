package io.carreader.sim

import io.carreader.core.link.BusProtocol
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-process ELM327 v2.x / STN adapter (per [SimScenario.adapter]) plugged into a simulated car.
 *
 * It interprets AT/ST commands and OBD requests and prints what a real adapter prints, byte for byte:
 * echo (until `ATE0`), CR line ends (CR LF with `ATL1`), spaces (`ATS0/1`), headers (`ATH0/1`), the
 * `SEARCHING...` line on the first request after `ATSP0`, `NO DATA`, `?`, and the `>` prompt.
 *
 * CAN: requests are routed by the current header (`ATSH`, plus `ATCP` on 29-bit buses): the functional id
 * (0x7DF / 18DB33F1) reaches every ECU with [SimEcu.answersObdFunctional] (negative answers suppressed),
 * a physical id reaches the ECU listening on it. Long answers are segmented into ISO-TP frames; answers
 * from several ECUs are interleaved frame by frame. Like a real ECU, a multi-frame answer stops after the
 * first frame when the adapter's flow control does not reach the ECU: automatic flow control (`ATFCSM0`)
 * only serves responders 0x7E8-0x7EF; outside that range `ATFCSM1` with `ATFCSH <ECU request id>` is
 * required. `ATCRA` filters what is printed.
 *
 * Serial buses (ISO 9141-2, ISO 14230-4, J1850): every OBD ECU answers; one line per message with a 3-byte
 * header and a checksum (sum of bytes, also used for J1850 where real buses use a CRC).
 *
 * Common code only (runs on Android in demo mode). [read] waits (coroutine delay) only when nothing is
 * pending.
 */
class SimTransport(val scenario: SimScenario) : Transport {
    override val description: String = "Simulator: ${scenario.name}"
    override val isSimulated: Boolean get() = true

    private val lock = Mutex()
    private val output = StringBuilder()
    private val input = StringBuilder()
    private var open = false

    private val received = ArrayList<String>()

    /** Every command the adapter received (spaces removed, upper case), for tests. */
    val commands: List<String> get() = received.toList()

    // Adapter settings (ATZ defaults).
    private var echo = true
    private var linefeeds = false
    private var spaces = true
    private var headers = false
    private var autoProtocol = true
    private var selected: BusProtocol? = null
    private var active: BusProtocol? = null
    private var busInitDone = false
    private var header11 = 0x7DF
    private var header24 = 0xDB33F1
    private var priority = 0x18
    private var receiveFilter: Int? = null
    private var fcHeader: Int? = null
    private var fcData: ByteArray? = null
    private var fcMode = 0
    private var timeout = 0x32
    private var lastCommand = ""

    override suspend fun connect() {
        lock.withLock { open = true }
    }

    override suspend fun close() {
        lock.withLock {
            open = false
            output.clear()
            input.clear()
        }
    }

    override suspend fun write(data: ByteArray) {
        lock.withLock {
            if (!open) throw TransportException("Simulator not connected")
            for (b in data) {
                when (val c = (b.toInt() and 0xFF).toChar()) {
                    '\r' -> { process(input.toString()); input.clear() }
                    '\n', '\u0000' -> Unit
                    else -> input.append(c)
                }
            }
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        lock.withLock {
            if (!open) throw TransportException("Simulator not connected")
            if (output.isNotEmpty()) {
                val out = output.toString().encodeToByteArray()
                output.clear()
                return out
            }
        }
        delay(timeoutMs)
        return ByteArray(0)
    }

    // --- command interpreter -----------------------------------------------------------------------

    private val eol get() = if (linefeeds) "\r\n" else "\r"

    private fun process(raw: String) {
        var cmd = raw.filter { !it.isWhitespace() }.uppercase()
        if (cmd.isEmpty()) cmd = lastCommand else lastCommand = cmd
        if (echo) output.append(raw).append(eol)
        if (cmd.isEmpty()) {
            output.append(">")
            return
        }
        received += cmd
        val lines = execute(cmd)
        for (l in lines) output.append(l).append(eol)
        output.append(eol).append('>')
    }

    private fun execute(cmd: String): List<String> {
        if (scenario.adapter.unsupported.any { cmd.startsWith(it) }) return listOf("?")
        return when {
            cmd.startsWith("AT") -> at(cmd.substring(2))
            cmd.startsWith("ST") -> st(cmd)
            else -> request(cmd)
        }
    }

    private fun reset() {
        echo = true; linefeeds = false; spaces = true; headers = false
        autoProtocol = true; selected = null; active = null; busInitDone = false
        header11 = 0x7DF; header24 = 0xDB33F1; priority = 0x18
        receiveFilter = null; fcHeader = null; fcData = null; fcMode = 0; timeout = 0x32
    }

    private fun at(c: String): List<String> {
        val ok = listOf("OK")
        val flag = Regex("^(E|L|S|H|M|D|R|CAF|AT|AL|NL|V|CFC|JS|JE)([0-2])$").find(c)
        if (flag != null) {
            val on = flag.groupValues[2] != "0"
            when (flag.groupValues[1]) {
                "E" -> echo = on
                "L" -> linefeeds = on
                "S" -> spaces = on
                "H" -> headers = on
            }
            return ok
        }
        return when {
            c == "Z" || c == "WS" -> { reset(); listOf("", scenario.adapter.ati) }
            c == "D" -> {
                headers = false; spaces = true; receiveFilter = null; fcMode = 0; timeout = 0x32
                ok
            }
            c == "I" -> listOf(scenario.adapter.ati)
            c == "@1" -> listOf(scenario.adapter.at1 ?: "?")
            c == "RV" -> listOf(formatVolts())
            c == "DPN" -> listOf(dpn())
            c == "DP" -> listOf(dp())
            c == "PC" -> ok
            c == "AR" -> { receiveFilter = null; ok }
            c == "CRA" -> { receiveFilter = null; ok }
            c.startsWith("CRA") -> hexArg(c, 3, listOf(3, 8))?.let { receiveFilter = it; ok } ?: listOf("?")
            c.startsWith("SPA") || c.startsWith("TPA") -> setProtocol(c.substring(3), auto = true)
            c.startsWith("SP") || c.startsWith("TP") -> setProtocol(c.substring(2), auto = false)
            c.startsWith("SH") -> setHeader(c.substring(2))
            c.startsWith("CP") -> hexArg(c, 2, listOf(2))?.let { priority = it and 0x1F; ok } ?: listOf("?")
            c.startsWith("FCSH") -> hexArg(c, 4, listOf(3, 6, 8))?.let { v ->
                fcHeader = if (c.length - 4 == 6) ((priority and 0x1F) shl 24) or v else v
                ok
            } ?: listOf("?")
            c.startsWith("FCSD") -> {
                val arg = c.substring(4)
                if (arg.isEmpty() || arg.length % 2 != 0 || arg.length > 10 || !isHex(arg)) listOf("?")
                else { fcData = parseHexBytes(arg); ok }
            }
            c.startsWith("FCSM") -> when (c.substring(4)) {
                "0" -> { fcMode = 0; ok }
                "1" -> if (fcHeader != null && fcData != null) { fcMode = 1; ok } else listOf("?")
                "2" -> if (fcData != null) { fcMode = 2; ok } else listOf("?")
                else -> listOf("?")
            }
            c.startsWith("ST") -> hexArg(c, 2, listOf(2))?.let { timeout = it; ok } ?: listOf("?")
            c.startsWith("CF") || c.startsWith("CM") || c.startsWith("SR") -> ok
            else -> listOf("?")
        }
    }

    private fun st(cmd: String): List<String> {
        val a = scenario.adapter
        if (a.sti == null) return listOf("?")
        return when (cmd) {
            "STI" -> listOf(a.sti)
            "STDI" -> listOf(a.stdi ?: "?")
            else -> listOf("?")
        }
    }

    private fun setProtocol(arg: String, auto: Boolean): List<String> {
        if (arg.length != 1) return listOf("?")
        val code = arg[0]
        if (code == '0') {
            autoProtocol = true; selected = null
        } else {
            val p = BusProtocol.fromElmCode(code)
            if (p == BusProtocol.UNKNOWN) return listOf("?")
            autoProtocol = auto; selected = p
        }
        active = null
        busInitDone = false
        return listOf("OK")
    }

    private fun setHeader(arg: String): List<String> {
        if (!isHex(arg)) return listOf("?")
        when (arg.length) {
            3 -> header11 = arg.toInt(16) and 0x7FF
            6 -> { header24 = arg.toInt(16); header11 = header24 and 0x7FF }
            8 -> { val v = arg.toLong(16).toInt(); priority = (v ushr 24) and 0x1F; header24 = v and 0xFFFFFF }
            else -> return listOf("?")
        }
        return listOf("OK")
    }

    private fun dpn(): String {
        val p = active ?: selected
        val code = p?.elmCode ?: '0'
        return if (autoProtocol) "A$code" else "$code"
    }

    private fun dp(): String {
        val p = active ?: selected ?: return "AUTO"
        return (if (autoProtocol) "AUTO, " else "") + p.label
    }

    private fun formatVolts(): String {
        val v = scenario.vehicle.let { if (it.engineRunning && it.ignitionOn) it.restingBatteryVolts + 1.6 else it.restingBatteryVolts }
        val tenths = kotlin.math.round(v * 10).toInt()
        return "${tenths / 10}.${tenths % 10}V"
    }

    // --- vehicle requests --------------------------------------------------------------------------

    private fun request(cmd: String): List<String> {
        if (!isHex(cmd)) return listOf("?")
        var hex = cmd
        var maxResponses = Int.MAX_VALUE
        if (hex.length % 2 == 1) { // trailing response-count digit (ELM327 v1.3+)
            maxResponses = hex.last().digitToInt(16).takeIf { it > 0 } ?: Int.MAX_VALUE
            hex = hex.dropLast(1)
        }
        val payload = parseHexBytes(hex)
        if (payload.isEmpty() || payload.size > 7) return listOf("?")

        val prefix = ArrayList<String>()
        val vehicle = scenario.vehicle
        if (active == null) {
            if (autoProtocol && selected == null) {
                prefix += "SEARCHING..."
                if (!vehicle.ignitionOn) return prefix + "UNABLE TO CONNECT"
                active = scenario.protocol
            } else {
                val wanted = selected ?: scenario.protocol
                if (!wanted.isCan && !busInitDone) {
                    if (wanted != scenario.protocol || !vehicle.ignitionOn) return listOf("BUS INIT: ...ERROR")
                    prefix += "BUS INIT: ...OK"
                    busInitDone = true
                }
                if (wanted.isCan && (wanted != scenario.protocol || !vehicle.ignitionOn)) return listOf("CAN ERROR")
                active = wanted
            }
        }
        if (!vehicle.ignitionOn) return prefix + "NO DATA"
        val proto = active!!
        val lines = if (proto.isCan) canExchange(payload, proto.extendedId, maxResponses) else serialExchange(payload, proto)
        return prefix + lines.ifEmpty { listOf("NO DATA") }
    }

    private class Frame(val id: Int, val data: ByteArray)

    private fun canExchange(payload: ByteArray, extended: Boolean, maxResponses: Int): List<String> {
        val target = if (extended) ((priority and 0x1F) shl 24) or header24 else header11
        val functional = if (extended) target == 0x18DB33F1 else target == 0x7DF
        val ecus = if (functional) scenario.ecus.filter { it.answersObdFunctional } else scenario.ecus.filter { it.requestId == target }

        val perEcu = ArrayList<Pair<SimEcu, List<List<Frame>>>>()
        for (ecu in ecus) {
            if (receiveFilter != null && receiveFilter != ecu.responseId) continue
            var answers = ecu.handle(payload)
            if (functional) answers = answers.filter { it.isEmpty() || it[0] != 0x7F.toByte() }
            if (answers.isEmpty()) continue
            val messages = ArrayList<List<Frame>>()
            for (a in answers) {
                val frames = segment(a).map { Frame(ecu.responseId, it) }
                if (frames.size > 1 && !flowControlReaches(ecu, extended)) {
                    messages += frames.take(1) // the ECU waits for a flow control frame that never comes
                    break
                }
                messages += frames
            }
            perEcu += ecu to messages
        }
        if (!headers) return formatWithoutHeaders(perEcu)

        // Interleave ECUs frame by frame, as on a real bus where they answer concurrently.
        val queues = perEcu.map { (_, msgs) -> ArrayDeque(msgs.flatten()) }
        val lines = ArrayList<String>()
        while (queues.any { it.isNotEmpty() } && lines.size < maxResponses) {
            for (q in queues) {
                val f = q.removeFirstOrNull() ?: continue
                lines += formatId(f.id, extended) + sep() + bytesText(f.data)
                if (lines.size >= maxResponses) break
            }
        }
        return lines
    }

    private fun flowControlReaches(ecu: SimEcu, extended: Boolean): Boolean = when {
        fcMode == 1 -> fcHeader == ecu.requestId
        extended -> true // automatic flow control handles 18DAF1xx responders
        else -> ecu.responseId in 0x7E8..0x7EF && ecu.requestId == ecu.responseId - 8
    }

    /** ISO 15765-2 segmentation into 8-byte frames, padded with [SimScenario.canPadding]. */
    private fun segment(payload: ByteArray): List<ByteArray> {
        val pad = scenario.canPadding.toByte()
        fun frame(vararg parts: ByteArray): ByteArray {
            val f = parts.fold(ByteArray(0)) { a, b -> a + b }
            return f + ByteArray(8 - f.size) { pad }
        }
        if (payload.size <= 7) return listOf(frame(byteArrayOf(payload.size.toByte()), payload))
        val out = ArrayList<ByteArray>()
        val len = payload.size
        out += frame(byteArrayOf((0x10 or ((len shr 8) and 0x0F)).toByte(), (len and 0xFF).toByte()), payload.copyOfRange(0, 6))
        var pos = 6
        var seq = 1
        while (pos < len) {
            val end = minOf(pos + 7, len)
            out += frame(byteArrayOf((0x20 or seq).toByte()), payload.copyOfRange(pos, end))
            pos = end
            seq = (seq + 1) and 0x0F
        }
        return out
    }

    /** CAN auto-formatting with headers off: single frames as bare data, multi-frame as `LLL` + `N: ...`. */
    private fun formatWithoutHeaders(perEcu: List<Pair<SimEcu, List<List<Frame>>>>): List<String> {
        val lines = ArrayList<String>()
        for ((_, messages) in perEcu) for (frames in messages) {
            val first = frames.first().data
            val pci = first[0].toInt() and 0xFF
            if (pci shr 4 == 0) {
                lines += bytesText(first.copyOfRange(1, 1 + (pci and 0x0F)))
                continue
            }
            val total = ((pci and 0x0F) shl 8) or (first[1].toInt() and 0xFF)
            lines += total.toString(16).uppercase().padStart(3, '0')
            var remaining = total
            frames.forEachIndexed { i, f ->
                val body = if (i == 0) f.data.copyOfRange(2, 8) else f.data.copyOfRange(1, minOf(8, 1 + remaining))
                remaining -= body.size
                lines += "${(i and 0x0F).toString(16).uppercase()}:" + sep() + bytesText(body)
            }
        }
        return lines
    }

    private fun serialExchange(payload: ByteArray, proto: BusProtocol): List<String> {
        val lines = ArrayList<String>()
        for (ecu in scenario.ecus.filter { it.answersObdFunctional }) {
            for (msg in ecu.handle(payload)) {
                if (msg.isNotEmpty() && msg[0] == 0x7F.toByte()) continue
                if (!headers) { lines += bytesText(msg); continue }
                val header = when (proto) {
                    BusProtocol.KWP_5BAUD, BusProtocol.KWP_FAST -> bytesOf(0x80 or msg.size, 0xF1, ecu.serialAddress)
                    BusProtocol.J1850_PWM -> bytesOf(0x41, 0x6B, ecu.serialAddress)
                    else -> bytesOf(0x48, 0x6B, ecu.serialAddress)
                }
                val body = header + msg
                val checksum = body.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFF }
                lines += bytesText(body + byteArrayOf(checksum.toByte()))
            }
        }
        return lines
    }

    // --- formatting helpers ------------------------------------------------------------------------

    private fun sep() = if (spaces) " " else ""

    private fun bytesText(b: ByteArray): String =
        b.joinToString(sep()) { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') } + (if (spaces) " " else "")

    private fun formatId(id: Int, extended: Boolean): String {
        if (!extended) return id.toString(16).uppercase().padStart(3, '0')
        val bytes = listOf(id ushr 24, id ushr 16, id ushr 8, id).map { (it and 0xFF).toString(16).uppercase().padStart(2, '0') }
        return bytes.joinToString(sep())
    }

    private fun hexArg(c: String, prefixLen: Int, lengths: List<Int>): Int? {
        val arg = c.substring(prefixLen)
        if (arg.length !in lengths || !isHex(arg)) return null
        return arg.toLong(16).toInt()
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    private fun parseHexBytes(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
