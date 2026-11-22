package io.carreader.core.obd2

import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.LinkException

/**
 * SAE J1979 / ISO 15031-5 services over a [DiagnosticLink], always addressed functionally
 * ([EcuAddress.OBD_FUNCTIONAL]); every answering ECU is taken into account.
 *
 * Responses are validated (positive response id, PID echo, length); negative responses and malformed
 * answers are ignored rather than thrown. [LinkException]s from the link propagate, except in
 * [identification], which tolerates ECUs or PIDs that fail.
 */
class Obd2Service(private val link: DiagnosticLink) : Obd2Port {

    private val target = EcuAddress.OBD_FUNCTIONAL

    private suspend fun send(vararg bytes: Int): List<EcuResponse> =
        link.request(target, ByteArray(bytes.size) { bytes[it].toByte() })
            .filter { !it.isNegative && it.payload.isNotEmpty() }
            // Engine ECU (lowest id, 7E8) first; unknown sources last.
            .sortedBy { it.sourceId ?: Int.MAX_VALUE }

    private fun EcuResponse.u8(i: Int): Int = payload[i].toInt() and 0xFF

    // ---------------------------------------------------------------- service 01

    override suspend fun supportedPids(): Set<Int> = readBitmaps(service = 0x01, frame = null)

    /**
     * Walks the "PIDs supported" bitmaps (00, 20, 40, ... C0) of [service], merged across ECUs.
     * Bit 7 of byte A is PID base+1, bit 0 of byte D is PID base+0x20, which also says the next range exists.
     * [frame] is the freeze-frame number for service 02 (its responses carry it after the PID echo).
     */
    private suspend fun readBitmaps(service: Int, frame: Int?): Set<Int> {
        val supported = LinkedHashSet<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val responses = if (frame == null) send(service, base) else send(service, base, frame)
            val header = if (frame == null) 2 else 3
            var next = false
            for (r in responses) {
                if (r.payload.size < header + 4) continue
                if (r.u8(0) != service + 0x40 || r.u8(1) != base) continue
                if (frame != null && r.u8(2) != frame) continue
                var bits = 0L
                for (i in 0 until 4) bits = (bits shl 8) or r.u8(header + i).toLong()
                for (i in 0 until 32) {
                    if (bits and (1L shl (31 - i)) != 0L) supported += base + i + 1
                }
                if (bits and 1L != 0L) next = true
            }
            if (!next) break
            base += 0x20
        }
        return supported.sorted().toSet()
    }

    override suspend fun readPid(pid: PidDefinition): List<LiveValue> =
        send(pid.service, pid.pid).mapNotNull { r ->
            if (r.payload.size < 2 + pid.bytes) return@mapNotNull null
            if (r.u8(0) != pid.service + 0x40 || r.u8(1) != pid.pid) return@mapNotNull null
            decodeOrNull(pid, r.payload.copyOfRange(2, 2 + pid.bytes))?.copy(ecu = ecuLabel(r.sourceId))
        }

    override suspend fun readiness(): ReadinessReport? {
        val reports = send(0x01, 0x01).mapNotNull { r ->
            if (r.payload.size < 6 || r.u8(0) != 0x41 || r.u8(1) != 0x01) null
            else Readiness.decode(r.payload.copyOfRange(2, 6))
        }
        return Readiness.merge(reports)
    }

    // ---------------------------------------------------------------- services 03 / 07 / 0A

    override suspend fun readDtcs(source: DtcSource): List<Dtc> {
        val service = when (source) {
            DtcSource.STORED -> 0x03
            DtcSource.PENDING -> 0x07
            DtcSource.PERMANENT -> 0x0A
            DtcSource.ECU -> return emptyList()
        }
        val canBus = link.busProtocol?.isCan
        val result = LinkedHashSet<Dtc>()
        for (r in send(service)) {
            if (r.u8(0) != service + 0x40) continue
            val ecu = ecuLabel(r.sourceId)
            for (raw in parseDtcPayload(r.payload, canBus)) {
                val code = DtcCodec.format(raw)
                result += Dtc(code = code, source = source, description = DtcDescriptions.describe(code), ecu = ecu)
            }
        }
        return result.toList()
    }

    // ---------------------------------------------------------------- service 02

    override suspend fun freezeFrame(frame: Int): FreezeFrame? {
        val trigger = send(0x02, 0x02, frame).firstNotNullOfOrNull { r ->
            if (r.payload.size >= 5 && r.u8(0) == 0x42 && r.u8(1) == 0x02 && r.u8(2) == frame) {
                (r.u8(3) shl 8) or r.u8(4)
            } else null
        }
        // No answer, or 0000: no freeze frame stored.
        if (trigger == null || trigger == 0) return null

        val values = ArrayList<LiveValue>()
        val pids = readBitmaps(service = 0x02, frame = frame)
        for (pidNumber in pids) {
            if (pidNumber == 0x02 || pidNumber % 0x20 == 0) continue
            val def = StandardPids.byPid(pidNumber) ?: continue
            for (r in send(0x02, pidNumber, frame)) {
                if (r.payload.size < 3 + def.bytes) continue
                if (r.u8(0) != 0x42 || r.u8(1) != pidNumber || r.u8(2) != frame) continue
                decodeOrNull(def, r.payload.copyOfRange(3, 3 + def.bytes))?.let { values += it.copy(ecu = ecuLabel(r.sourceId)) }
            }
        }
        return FreezeFrame(frameIndex = frame, triggerDtc = DtcCodec.format(trigger), values = values)
    }

    // ---------------------------------------------------------------- service 09

    override suspend fun identification(): VehicleIdentification {
        val supported = tolerant { readBitmaps(service = 0x09, frame = null) } ?: emptySet()
        // If 09 00 got no answer, still try each PID: some ECUs skip the bitmap.
        fun wanted(pid: Int) = supported.isEmpty() || pid in supported

        val vin = if (wanted(0x02)) tolerant { readVin() } else null
        val calIds = if (wanted(0x04)) tolerant { readItems(0x04, 16).map(::asciiText) } ?: emptyList() else emptyList()
        val cvns = if (wanted(0x06)) tolerant { readItems(0x06, 4).map(::hexText) } ?: emptyList() else emptyList()
        val names = if (wanted(0x0A)) tolerant { readItems(0x0A, 20).map(::asciiText) } ?: emptyList() else emptyList()
        return VehicleIdentification(
            vin = vin,
            calibrationIds = calIds.filter { it.isNotEmpty() },
            cvns = cvns,
            ecuNames = names.filter { it.isNotEmpty() },
        )
    }

    private suspend fun readVin(): String? {
        val candidates = mergedDataPerEcu(0x02).map { bytes ->
            bytes.map { (it.toInt() and 0xFF).toChar() }
                .filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }
                .joinToString("")
                .uppercase()
                .let { if (it.length > 17) it.takeLast(17) else it }
        }
        return candidates.firstOrNull { io.carreader.core.vin.Vin.isWellFormed(it) }
            ?: candidates.firstOrNull { it.isNotEmpty() }
    }

    /** Reads a service 09 PID whose items have a fixed [itemSize] and splits every ECU's data into items. */
    private suspend fun readItems(pid: Int, itemSize: Int): List<ByteArray> =
        mergedDataPerEcu(pid).flatMap { data ->
            (0 until data.size / itemSize).map { data.copyOfRange(it * itemSize, (it + 1) * itemSize) }
        }

    /**
     * Service 09 data of [pid], one byte array per ECU.
     * CAN: one reassembled message per ECU, `49 pid count data...`.
     * Non-CAN: several 7-byte messages per ECU, `49 pid seq d1 d2 d3 d4`, merged by sequence number.
     */
    private suspend fun mergedDataPerEcu(pid: Int): List<ByteArray> {
        val responses = send(0x09, pid).filter { it.payload.size >= 3 && it.u8(0) == 0x49 && it.u8(1) == pid }
        val canBus = link.busProtocol?.isCan
        return responses.groupBy { it.sourceId }.values.map { group ->
            val segmented = when (canBus) {
                true -> false
                false -> true
                null -> group.size > 1 || group.all { it.payload.size == 7 }
            }
            if (!segmented) {
                group.first().payload.let { it.copyOfRange(3, it.size) }
            } else {
                group.filter { it.payload.size >= 7 }
                    .associateBy { it.u8(2) } // dedupe repeated sequence numbers
                    .entries
                    .sortedBy { it.key }
                    .map { it.value }
                    .flatMap { it.payload.copyOfRange(3, 7).toList() }
                    .toByteArray()
            }
        }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- service 04

    override suspend fun clearEmissionDtcs(): Boolean = send(0x04).any { it.u8(0) == 0x44 }

    // ---------------------------------------------------------------- helpers

    private inline fun <T> tolerant(block: () -> T): T? =
        try {
            block()
        } catch (e: LinkException) {
            null
        }

    companion object {
        /** "7E8" for 11-bit ids, "18DAF110" for 29-bit ids. */
        fun ecuLabel(sourceId: Int?): String? = sourceId?.let {
            if (it <= 0x7FF) it.toString(16).uppercase().padStart(3, '0') else it.toString(16).uppercase().padStart(8, '0')
        }

        /**
         * Raw 2-byte DTCs of one service 03/07/0A response (service echo included).
         * CAN responses carry a count byte after the echo; non-CAN responses carry three DTC slots, padded
         * with 0000. [canBus] null means unknown: an even payload length then implies a count byte.
         * 0000 entries are dropped either way.
         */
        fun parseDtcPayload(payload: ByteArray, canBus: Boolean?): List<Int> {
            val hasCount = canBus ?: (payload.size % 2 == 0)
            val start = if (hasCount) 2 else 1
            if (payload.size < start) return emptyList()
            var pairs = (payload.size - start) / 2
            if (hasCount) pairs = minOf(pairs, payload[1].toInt() and 0xFF)
            return (0 until pairs).map { i ->
                val hi = payload[start + 2 * i].toInt() and 0xFF
                val lo = payload[start + 2 * i + 1].toInt() and 0xFF
                (hi shl 8) or lo
            }.filter { it != 0 }
        }

        private fun decodeOrNull(pid: PidDefinition, data: ByteArray): LiveValue? =
            try {
                pid.decode(data)
            } catch (e: IllegalArgumentException) {
                null
            }

        internal fun asciiText(bytes: ByteArray): String =
            bytes.map { (it.toInt() and 0xFF) }
                .filter { it != 0x00 }
                .map { if (it in 0x20..0x7E) it.toChar() else ' ' }
                .joinToString("")
                .trim()
                .replace(Regex(" {2,}"), " ")

        internal fun hexText(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
    }
}
