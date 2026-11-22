package io.carreader.core.link

/**
 * Port: an adapter driver that delivers diagnostic requests to ECUs and returns their reassembled responses.
 * ELM327/STN is the first implementation; J2534 or native CAN interfaces can implement it later.
 *
 * Payloads never include transport framing (no ISO-TP PCI, no CAN header): a request is the service id plus
 * its parameters (e.g. `01 0C`), a response is the positive or negative response (e.g. `41 0C 1A F8`).
 */
interface DiagnosticLink {
    /** Opens the transport and initializes the adapter (reset, echo off, protocol search...). */
    suspend fun initialize(): AdapterInfo

    /** The bus protocol the adapter settled on. Null before the first successful exchange. */
    val busProtocol: BusProtocol?

    val adapterInfo: AdapterInfo?

    /**
     * Sends [payload] to [target] and returns one [EcuResponse] per answering ECU (functional requests can get
     * several). An empty list means no ECU answered ("NO DATA"). Implementations reassemble multi-frame
     * responses and wait through UDS "response pending" (7F xx 78) negative responses.
     */
    suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions = RequestOptions()): List<EcuResponse>

    /** Battery voltage measured at the OBD connector (pin 16), or null if the adapter cannot measure it. */
    suspend fun readBatteryVoltage(): Double?

    suspend fun close()
}

data class RequestOptions(
    val timeoutMs: Long = 2000,
    /** Expect a single answering ECU: lets the driver stop early. */
    val singleResponse: Boolean = false,
)

/**
 * Where a request goes. [requestId] is the CAN identifier (11 or 29 bit) used on CAN buses; on K-line/J1850
 * buses the driver maps the default functional address itself and ignores the ids.
 * [responseId] is the expected reply id, null to accept any.
 */
data class EcuAddress(
    val requestId: Int,
    val responseId: Int? = null,
    val extendedId: Boolean = false,
) {
    companion object {
        /** ISO 15765-4 functional broadcast for OBD-II on 11-bit CAN. */
        val OBD_FUNCTIONAL = EcuAddress(0x7DF)
        /** Engine ECU, physical addressing on 11-bit CAN. */
        val OBD_ENGINE = EcuAddress(0x7E0, 0x7E8)
    }
}

/** One ECU's answer. [sourceId] is the CAN id of the sender when known (e.g. 0x7E8). */
class EcuResponse(val sourceId: Int?, val payload: ByteArray) {
    val isNegative: Boolean get() = payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x7F
    override fun toString(): String = "EcuResponse(${sourceId?.toString(16)}: ${payload.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }})"
}

/** The 12 ELM327 protocol codes (AT SP h), plus UNKNOWN for adapters that report something else. */
enum class BusProtocol(val elmCode: Char, val label: String, val isCan: Boolean, val extendedId: Boolean = false) {
    J1850_PWM('1', "SAE J1850 PWM 41.6k", false),
    J1850_VPW('2', "SAE J1850 VPW 10.4k", false),
    ISO_9141_2('3', "ISO 9141-2 5-baud init", false),
    KWP_5BAUD('4', "ISO 14230-4 KWP 5-baud init", false),
    KWP_FAST('5', "ISO 14230-4 KWP fast init", false),
    CAN_11_500('6', "ISO 15765-4 CAN 11-bit 500k", true),
    CAN_29_500('7', "ISO 15765-4 CAN 29-bit 500k", true, true),
    CAN_11_250('8', "ISO 15765-4 CAN 11-bit 250k", true),
    CAN_29_250('9', "ISO 15765-4 CAN 29-bit 250k", true, true),
    J1939('A', "SAE J1939 CAN 29-bit 250k", true, true),
    USER1('B', "USER1 CAN", true),
    USER2('C', "USER2 CAN", true),
    UNKNOWN('?', "Unknown", false);

    companion object {
        fun fromElmCode(code: Char): BusProtocol = entries.firstOrNull { it.elmCode == code.uppercaseChar() } ?: UNKNOWN
    }
}

/** What the adapter is and can do, discovered at init. Drives feature gating (e.g. writes need [certified]). */
data class AdapterInfo(
    val identity: String,
    val firmware: String?,
    val chip: AdapterChip,
    val supportsStCommands: Boolean,
    val supportsVoltage: Boolean,
    val supportsHeaders: Boolean,
    /** Allowed to perform mutating operations (genuine STN/OBDLink-class adapters). */
    val certified: Boolean,
    val notes: List<String> = emptyList(),
)

enum class AdapterChip { ELM327_GENUINE, STN, ELM327_CLONE, SIMULATOR, UNKNOWN }

class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
