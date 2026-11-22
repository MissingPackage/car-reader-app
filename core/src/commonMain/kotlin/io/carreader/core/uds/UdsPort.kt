package io.carreader.core.uds

import io.carreader.core.link.EcuAddress

/** UDS diagnostic sessions (ISO 14229-1 0x10). Programming (0x02) is intentionally absent: no flashing. */
enum class UdsSession(val id: Int) { DEFAULT(0x01), EXTENDED(0x03) }

/** A raw UDS DTC record from ReadDTCInformation 0x19 sub-function 0x02. */
data class UdsDtcRecord(val dtc: Int, val status: Int) {
    /** High 16 bits as SAE J2012 code ("P0301"), low byte = failure type. */
    val failureType: Int get() = dtc and 0xFF
}

/** ISO 14229 negative response, with the NRC name for logs and UI. */
class NegativeResponseException(val serviceId: Int, val nrc: Int) :
    Exception("Negative response to 0x${serviceId.toString(16)}: 0x${nrc.toString(16)} ${Nrc.name(nrc)}")

/** The ECU did not answer at all. */
class NoResponseException(val target: EcuAddress) : Exception("No response from 0x${target.requestId.toString(16)}")

object Nrc {
    private val names = mapOf(
        0x10 to "generalReject",
        0x11 to "serviceNotSupported",
        0x12 to "subFunctionNotSupported",
        0x13 to "incorrectMessageLengthOrInvalidFormat",
        0x14 to "responseTooLong",
        0x21 to "busyRepeatRequest",
        0x22 to "conditionsNotCorrect",
        0x24 to "requestSequenceError",
        0x31 to "requestOutOfRange",
        0x33 to "securityAccessDenied",
        0x35 to "invalidKey",
        0x36 to "exceedNumberOfAttempts",
        0x37 to "requiredTimeDelayNotExpired",
        0x72 to "generalProgrammingFailure",
        0x78 to "requestCorrectlyReceivedResponsePending",
        0x7E to "subFunctionNotSupportedInActiveSession",
        0x7F to "serviceNotSupportedInActiveSession",
    )
    fun name(nrc: Int): String = names[nrc] ?: "unknown"
}

/**
 * Port: UDS client bound to one link. Read services only plus ClearDiagnosticInformation, which the gated
 * link refuses outside WriteGuard. 0x2E/0x31 are added in phase 3, not before.
 */
interface UdsClient {
    suspend fun startSession(target: EcuAddress, session: UdsSession)

    suspend fun testerPresent(target: EcuAddress)

    /** 0x22: returns the data after the 2-byte DID echo. */
    suspend fun readDataByIdentifier(target: EcuAddress, did: Int): ByteArray

    /** 0x19 0x02 with [statusMask]. */
    suspend fun readDtcsByStatus(target: EcuAddress, statusMask: Int = 0xFF): List<UdsDtcRecord>

    /** 0x14 with group 0xFFFFFF (all). Must run inside WriteGuard.execute. */
    suspend fun clearDiagnosticInformation(target: EcuAddress, group: Int = 0xFFFFFF)
}
