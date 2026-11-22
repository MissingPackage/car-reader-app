package io.carreader.core.link.elm

import io.carreader.core.link.BusProtocol

/** Status lines an ELM327/STN prints instead of (or around) data. */
enum class ElmStatus(val isError: Boolean) {
    NO_DATA(false),
    SEARCHING(false),
    BUS_INIT_OK(false),
    OK(false),
    BUS_INIT_ERROR(true),
    UNABLE_TO_CONNECT(true),
    CAN_ERROR(true),
    BUS_ERROR(true),
    BUS_BUSY(true),
    FB_ERROR(true),
    DATA_ERROR(true),
    RX_ERROR(true),
    BUFFER_FULL(true),
    STOPPED(true),
    UNKNOWN_COMMAND(true),
    LOW_VOLTAGE_RESET(true),
    ACTIVITY_ALERT(true),
    INTERNAL_ERROR(true),
}

/** A non-CAN line with headers on: 3 (or 4) header bytes, data, checksum. [source] is the sender address. */
class SerialMessage(val source: Int, val target: Int, val data: ByteArray)

/** What one adapter reply contained. Lines that are neither data nor a known status end up in [other]. */
class ParsedReply(
    val canFrames: List<CanFrame>,
    val serialMessages: List<SerialMessage>,
    val statuses: List<ElmStatus>,
    val other: List<String>,
) {
    val firstError: ElmStatus? get() = statuses.firstOrNull { it.isError }
    val hasData: Boolean get() = canFrames.isNotEmpty() || serialMessages.isNotEmpty()
}

/**
 * Pure parser for the lines of one ELM327/STN reply (prompt, echo and blank lines already removed by
 * [ElmChannel]). Assumes headers on (`ATH1`). Works with or without spaces between bytes (`ATS0`/`ATS1`).
 *
 * CAN: `7E8 06 41 00 BE 3F A8 13 00` or `7E8064100BE3FA81300` (11-bit id = 3 hex digits),
 * `18 DA F1 10 06 41 00 ...` or `18DAF110064100...` (29-bit id = 8 hex digits).
 * Non-CAN (ISO 9141-2, ISO 14230-4, J1850): `48 6B 10 41 0C 1A F8 C8`: 3 header bytes (priority/format,
 * target, source), data, checksum. ISO 14230 with a separate length byte (format byte low bits = 0) has a
 * 4-byte header.
 */
object ElmResponseParser {
    fun statusOf(line: String): ElmStatus? {
        val l = line.trim().uppercase()
        return when {
            l == "NO DATA" || l == "NODATA" -> ElmStatus.NO_DATA
            l.startsWith("SEARCHING") -> ElmStatus.SEARCHING
            l.startsWith("BUS INIT") -> if (l.endsWith("OK")) ElmStatus.BUS_INIT_OK else ElmStatus.BUS_INIT_ERROR
            l == "OK" -> ElmStatus.OK
            l.startsWith("UNABLE TO CONNECT") -> ElmStatus.UNABLE_TO_CONNECT
            l.startsWith("CAN ERROR") -> ElmStatus.CAN_ERROR
            l.startsWith("BUS ERROR") -> ElmStatus.BUS_ERROR
            l.startsWith("BUS BUSY") -> ElmStatus.BUS_BUSY
            l.startsWith("FB ERROR") -> ElmStatus.FB_ERROR
            l.contains("DATA ERROR") -> ElmStatus.DATA_ERROR
            l.contains("RX ERROR") -> ElmStatus.RX_ERROR
            l.startsWith("BUFFER FULL") -> ElmStatus.BUFFER_FULL
            l.startsWith("STOPPED") -> ElmStatus.STOPPED
            l == "?" -> ElmStatus.UNKNOWN_COMMAND
            l.startsWith("LV RESET") -> ElmStatus.LOW_VOLTAGE_RESET
            l.startsWith("ACT ALERT") -> ElmStatus.ACTIVITY_ALERT
            l.matches(Regex("ERR\\d+")) -> ElmStatus.INTERNAL_ERROR
            else -> null
        }
    }

    fun parse(lines: List<String>, protocol: BusProtocol?): ParsedReply {
        val frames = ArrayList<CanFrame>()
        val serial = ArrayList<SerialMessage>()
        val statuses = ArrayList<ElmStatus>()
        val other = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val status = statusOf(line)
            if (status != null) {
                statuses += status
                continue
            }
            val parsed = when {
                protocol == null -> parseCan(line, guessExtended(line)) ?: parseSerial(line, false)
                protocol.isCan -> parseCan(line, protocol.extendedId)
                else -> parseSerial(line, protocol == BusProtocol.KWP_5BAUD || protocol == BusProtocol.KWP_FAST)
            }
            when (parsed) {
                is CanFrame -> frames += parsed
                is SerialMessage -> serial += parsed
                else -> other += line
            }
        }
        return ParsedReply(frames, serial, statuses, other)
    }

    /** Used only before the protocol is known: a 3-character first token means an 11-bit CAN id. */
    private fun guessExtended(line: String): Boolean {
        val first = line.trim().split(' ').first()
        return first.length != 3 && line.filter { it != ' ' }.length % 2 == 0
    }

    fun parseCan(line: String, extended: Boolean): CanFrame? {
        val hex = line.filter { it != ' ' }
        if (!isHex(hex)) return null
        val idLen = if (extended) 8 else 3
        if (hex.length < idLen + 2 || (hex.length - idLen) % 2 != 0) return null
        val id = hex.substring(0, idLen).toLong(16).toInt()
        return CanFrame(id, hexBytes(hex.substring(idLen)) ?: return null)
    }

    fun parseSerial(line: String, kwp: Boolean): SerialMessage? {
        val hex = line.filter { it != ' ' }
        if (!isHex(hex) || hex.length % 2 != 0) return null
        val bytes = hexBytes(hex) ?: return null
        val format = bytes.getOrNull(0)?.toInt()?.and(0xFF) ?: return null
        val headerLen = if (kwp && (format and 0x3F) == 0 && (format and 0x80) != 0) 4 else 3
        if (bytes.size < headerLen + 2) return null // header + at least one data byte + checksum
        return SerialMessage(
            source = bytes[2].toInt() and 0xFF,
            target = bytes[1].toInt() and 0xFF,
            data = bytes.copyOfRange(headerLen, bytes.size - 1),
        )
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    private fun hexBytes(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
