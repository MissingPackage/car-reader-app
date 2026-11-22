package io.carreader.sim

/** A UDS DTC as stored in the ECU: 3-byte code (SAE 2 bytes + failure type) and ISO 14229 status byte. */
data class SimUdsDtc(val code: String, val failureType: Int = 0x00, val status: Int = 0x2F) {
    val dtc3: Int get() = (dtcCode(code) shl 8) or (failureType and 0xFF)
}

/**
 * ISO 14229 read services plus ClearDiagnosticInformation for one ECU.
 *
 * - 0x10 01/03: default / extended session (`50 xx 00 32 01 F4`); 02 (programming) → 0x22.
 * - 0x3E 00: tester present; the suppress-positive-response bit (0x80) is honoured for 0x10 and 0x3E.
 * - 0x22: one or more DIDs from [dids] (static) and [extraDids] (computed per read, e.g. live values);
 *   unknown DID → 0x31; a DID in [extendedSessionDids] read in the default session → 0x31.
 * - 0x19 01 / 02 / 0A: DTC count, DTCs by status mask, all DTCs.
 * - 0x14 FFFFFF (or a group): clears [dtcs].
 * - 0x2E, 0x31, 0x27: refused, 0x7F in the default session, 0x33 in the extended one (no writes here).
 * - anything else → 0x11.
 *
 * Response pending: [pendingCount] `7F <sid> 78` messages precede each answer in the same exchange;
 * the first [pendingRepeats] requests get ONLY `7F <sid> 78` (the answer comes on a later re-send),
 * which exercises a driver that must re-issue the request.
 */
class UdsEcu(
    val dids: Map<Int, ByteArray> = emptyMap(),
    val extraDids: Map<Int, () -> ByteArray> = emptyMap(),
    val extendedSessionDids: Set<Int> = emptySet(),
    dtcs: List<SimUdsDtc> = emptyList(),
    val availabilityMask: Int = 0xFF,
    var pendingCount: Int = 0,
    var pendingRepeats: Int = 0,
) : EcuBehavior {
    val dtcs = dtcs.toMutableList()
    var session = 0x01
        private set
    var clearCount = 0
        private set

    override fun accepts(request: ByteArray): Boolean = request.isNotEmpty() && request.u8(0) !in 0x01..0x0A

    override fun respond(request: ByteArray): List<ByteArray> {
        val sid = request.u8(0)
        if (pendingRepeats > 0 && sid != 0x3E) {
            pendingRepeats--
            return listOf(negative(sid, Nrcs.RESPONSE_PENDING))
        }
        val answer = answer(request) ?: return emptyList()
        if (sid == 0x3E || pendingCount == 0) return listOf(answer)
        return List(pendingCount) { negative(sid, Nrcs.RESPONSE_PENDING) } + listOf(answer)
    }

    private fun answer(request: ByteArray): ByteArray? {
        val sid = request.u8(0)
        return when (sid) {
            0x10 -> sessionControl(request)
            0x3E -> {
                if (request.size != 2) return negative(sid, Nrcs.INCORRECT_LENGTH)
                if (request.u8(1) and 0x80 != 0) null else bytesOf(0x7E, request.u8(1))
            }
            0x22 -> readData(request)
            0x19 -> readDtcInformation(request)
            0x14 -> {
                if (request.size != 4) return negative(sid, Nrcs.INCORRECT_LENGTH)
                dtcs.clear()
                clearCount++
                bytesOf(0x54)
            }
            0x2E, 0x31, 0x27 ->
                negative(sid, if (session == 0x01) Nrcs.SERVICE_NOT_SUPPORTED_IN_SESSION else Nrcs.SECURITY_ACCESS_DENIED)
            else -> negative(sid, Nrcs.SERVICE_NOT_SUPPORTED)
        }
    }

    private fun sessionControl(request: ByteArray): ByteArray? {
        if (request.size != 2) return negative(0x10, Nrcs.INCORRECT_LENGTH)
        val sub = request.u8(1) and 0x7F
        val suppress = request.u8(1) and 0x80 != 0
        return when (sub) {
            0x01, 0x03 -> {
                session = sub
                if (suppress) null else bytesOf(0x50, sub, 0x00, 0x32, 0x01, 0xF4)
            }
            0x02 -> negative(0x10, Nrcs.CONDITIONS_NOT_CORRECT)
            else -> negative(0x10, Nrcs.SUB_FUNCTION_NOT_SUPPORTED)
        }
    }

    private fun readData(request: ByteArray): ByteArray {
        if (request.size < 3 || (request.size - 1) % 2 != 0) return negative(0x22, Nrcs.INCORRECT_LENGTH)
        var out = bytesOf(0x62)
        for (i in 1 until request.size step 2) {
            val did = (request.u8(i) shl 8) or request.u8(i + 1)
            if (did in extendedSessionDids && session == 0x01) return negative(0x22, Nrcs.REQUEST_OUT_OF_RANGE)
            val data = extraDids[did]?.invoke() ?: dids[did] ?: return negative(0x22, Nrcs.REQUEST_OUT_OF_RANGE)
            out += did.u16() + data
        }
        return out
    }

    private fun readDtcInformation(request: ByteArray): ByteArray {
        if (request.size < 2) return negative(0x19, Nrcs.INCORRECT_LENGTH)
        return when (request.u8(1)) {
            0x01 -> {
                if (request.size != 3) return negative(0x19, Nrcs.INCORRECT_LENGTH)
                val n = dtcs.count { it.status and request.u8(2) != 0 }
                bytesOf(0x59, 0x01, availabilityMask, 0x01, (n shr 8) and 0xFF, n and 0xFF)
            }
            0x02 -> {
                if (request.size != 3) return negative(0x19, Nrcs.INCORRECT_LENGTH)
                records(dtcs.filter { it.status and availabilityMask and request.u8(2) != 0 }, 0x02)
            }
            0x0A -> records(dtcs, 0x0A)
            else -> negative(0x19, Nrcs.SUB_FUNCTION_NOT_SUPPORTED)
        }
    }

    private fun records(list: List<SimUdsDtc>, sub: Int): ByteArray {
        var out = bytesOf(0x59, sub, availabilityMask)
        for (d in list) out += bytesOf((d.dtc3 shr 16) and 0xFF, (d.dtc3 shr 8) and 0xFF, d.dtc3 and 0xFF, d.status and 0xFF)
        return out
    }
}
