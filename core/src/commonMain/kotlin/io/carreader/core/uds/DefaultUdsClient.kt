package io.carreader.core.uds

import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import io.carreader.core.util.toHex
import io.carreader.core.util.u8
import kotlinx.coroutines.delay

/** The ECU answered, but the answer does not fit the request (wrong service id, wrong echo, too short). */
class UdsProtocolException(message: String) : Exception(message)

/**
 * Optional capability of a [UdsClient]: a copy of the client that waits at most [timeoutMs] per request.
 * Brand modules use it to probe for absent ECUs quickly; clients without it are used as they are.
 */
interface UdsTimeoutAdjustable {
    fun withTimeout(timeoutMs: Long): UdsClient
}

/**
 * ISO 14229-1 client over a [DiagnosticLink]. Physical requests only, one answering ECU per request.
 * Read services plus ClearDiagnosticInformation (which the gated link refuses outside WriteGuard).
 * WriteDataByIdentifier, RoutineControl and SecurityAccess are deliberately not implemented.
 */
class DefaultUdsClient(
    private val link: DiagnosticLink,
    private val timeoutMs: Long = 2000,
    /** Retries when "response pending" (0x78) or "busy, repeat request" (0x21) still reaches this layer. */
    private val maxRetries: Int = 3,
    private val busyRetryDelayMs: Long = 100,
) : UdsClient, UdsTimeoutAdjustable {

    override fun withTimeout(timeoutMs: Long): UdsClient = DefaultUdsClient(link, timeoutMs, maxRetries, busyRetryDelayMs)

    override suspend fun startSession(target: EcuAddress, session: UdsSession) {
        val response = exchange(target, byteArrayOf(SID_SESSION.toByte(), session.id.toByte()))
        expectEcho(response, 1, session.id, "session")
    }

    override suspend fun testerPresent(target: EcuAddress) {
        val response = exchange(target, byteArrayOf(SID_TESTER_PRESENT.toByte(), 0x00))
        expectEcho(response, 1, 0x00, "tester present sub-function")
    }

    override suspend fun readDataByIdentifier(target: EcuAddress, did: Int): ByteArray {
        val response = exchange(target, byteArrayOf(SID_READ_DID.toByte(), (did shr 8).toByte(), did.toByte()))
        if (response.size < 3) throw UdsProtocolException("Response to 22 ${did.toString(16)} too short: ${response.toHex()}")
        val echo = (response.u8(1) shl 8) or response.u8(2)
        if (echo != (did and 0xFFFF)) {
            throw UdsProtocolException("Response echoes DID ${echo.toString(16)}, expected ${did.toString(16)}")
        }
        return response.copyOfRange(3, response.size)
    }

    override suspend fun readDtcsByStatus(target: EcuAddress, statusMask: Int): List<UdsDtcRecord> {
        val response = exchange(target, byteArrayOf(SID_READ_DTC.toByte(), REPORT_DTC_BY_STATUS_MASK.toByte(), statusMask.toByte()))
        expectEcho(response, 1, REPORT_DTC_BY_STATUS_MASK, "ReadDTCInformation sub-function")
        return parseDtcRecords(response)
    }

    override suspend fun clearDiagnosticInformation(target: EcuAddress, group: Int) {
        exchange(
            target,
            byteArrayOf(SID_CLEAR_DTC.toByte(), (group shr 16).toByte(), (group shr 8).toByte(), group.toByte()),
        )
    }

    /** Sends [payload], returns the positive response (service id + 0x40 first). */
    private suspend fun exchange(target: EcuAddress, payload: ByteArray): ByteArray {
        val sid = payload.u8(0)
        var attempt = 0
        while (true) {
            val responses = link.request(target, payload, RequestOptions(timeoutMs = timeoutMs, singleResponse = true))
            val response = pick(target, responses) ?: throw NoResponseException(target)
            val data = response.payload
            if (data.isEmpty()) throw NoResponseException(target)
            if (data.u8(0) == NEGATIVE_RESPONSE) {
                if (data.size < 3) throw UdsProtocolException("Truncated negative response: ${data.toHex()}")
                if (data.u8(1) != sid) {
                    throw UdsProtocolException("Negative response for service ${data.u8(1).toString(16)}, expected ${sid.toString(16)}")
                }
                val nrc = data.u8(2)
                if ((nrc == NRC_RESPONSE_PENDING || nrc == NRC_BUSY_REPEAT) && attempt < maxRetries) {
                    attempt++
                    if (nrc == NRC_BUSY_REPEAT) delay(busyRetryDelayMs)
                    continue
                }
                throw NegativeResponseException(sid, nrc)
            }
            if (data.u8(0) != sid + POSITIVE_OFFSET) {
                throw UdsProtocolException("Unexpected response ${data.toHex()} to service ${sid.toString(16)}")
            }
            return data
        }
    }

    private fun pick(target: EcuAddress, responses: List<EcuResponse>): EcuResponse? {
        if (responses.isEmpty()) return null
        val matching = responses.filter { target.responseId == null || it.sourceId == null || it.sourceId == target.responseId }
        val candidates = matching.ifEmpty { responses }
        // A final answer wins over a pending notice that slipped through the driver.
        return candidates.firstOrNull { !isPending(it) } ?: candidates.first()
    }

    private fun isPending(r: EcuResponse): Boolean =
        r.payload.size >= 3 && r.payload.u8(0) == NEGATIVE_RESPONSE && r.payload.u8(2) == NRC_RESPONSE_PENDING

    private fun expectEcho(response: ByteArray, index: Int, expected: Int, what: String) {
        if (response.size <= index) throw UdsProtocolException("Response ${response.toHex()} lacks the $what echo")
        if (response.u8(index) != (expected and 0xFF)) {
            throw UdsProtocolException("Response ${response.toHex()} echoes $what ${response.u8(index).toString(16)}, expected ${expected.toString(16)}")
        }
    }

    companion object {
        const val SID_SESSION = 0x10
        const val SID_CLEAR_DTC = 0x14
        const val SID_READ_DTC = 0x19
        const val SID_READ_DID = 0x22
        const val SID_TESTER_PRESENT = 0x3E
        const val REPORT_DTC_BY_STATUS_MASK = 0x02
        private const val NEGATIVE_RESPONSE = 0x7F
        private const val POSITIVE_OFFSET = 0x40
        private const val NRC_BUSY_REPEAT = 0x21
        private const val NRC_RESPONSE_PENDING = 0x78

        /**
         * Parses a positive 0x59 0x02 response: `59 02 <availability mask> (<DTC hi> <DTC mid> <DTC lo> <status>)*`.
         * A trailing incomplete record (some ECUs pad or truncate) is ignored.
         */
        fun parseDtcRecords(response: ByteArray): List<UdsDtcRecord> {
            if (response.size < 3) throw UdsProtocolException("ReadDTCInformation response too short: ${response.toHex()}")
            val records = ArrayList<UdsDtcRecord>()
            var i = 3
            while (i + 4 <= response.size) {
                val dtc = (response.u8(i) shl 16) or (response.u8(i + 1) shl 8) or response.u8(i + 2)
                records += UdsDtcRecord(dtc, response.u8(i + 3))
                i += 4
            }
            return records
        }
    }
}
