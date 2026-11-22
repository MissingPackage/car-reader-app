package io.carreader.core.obd2

/** Where a trouble code came from. */
enum class DtcSource { STORED, PENDING, PERMANENT, ECU }

/**
 * A diagnostic trouble code in SAE J2012 display form ("P0301", "U0100").
 * [failureType] is the UDS failure-type byte (third DTC byte), null for 2-byte OBD codes.
 * [statusByte] is the UDS DTC status mask (ISO 14229 bit field), null when not reported.
 */
data class Dtc(
    val code: String,
    val source: DtcSource,
    val description: String? = null,
    val failureType: Int? = null,
    val statusByte: Int? = null,
    /** Which ECU reported it, e.g. "7E8" or "01 Engine". */
    val ecu: String? = null,
) {
    val isConfirmed: Boolean? get() = statusByte?.let { it and 0x08 != 0 }
    val isTestFailed: Boolean? get() = statusByte?.let { it and 0x01 != 0 }
    val isMilOn: Boolean? get() = statusByte?.let { it and 0x80 != 0 }
}

/** One live value, decoded. [value] is null when the ECU answered but the value is not numeric (enums, bit fields). */
data class LiveValue(
    val key: String,
    val name: String,
    val value: Double?,
    val unit: String,
    val text: String,
    val ecu: String? = null,
)

/** Readiness monitor (service 01 PID 01). */
data class MonitorStatus(val name: String, val supported: Boolean, val complete: Boolean)

data class ReadinessReport(
    val milOn: Boolean,
    val storedDtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<MonitorStatus>,
)

data class FreezeFrame(val frameIndex: Int, val triggerDtc: String?, val values: List<LiveValue>)

/** Service 09 identification. */
data class VehicleIdentification(
    val vin: String?,
    val calibrationIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
    val ecuNames: List<String> = emptyList(),
)

/** A decodable standard PID. [bytes] is the expected data length after the PID echo. */
interface PidDefinition {
    val service: Int get() = 0x01
    val pid: Int
    val key: String
    val name: String
    val unit: String
    val bytes: Int
    fun decode(data: ByteArray): LiveValue
}
