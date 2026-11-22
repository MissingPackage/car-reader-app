package io.carreader.sim

import kotlin.math.roundToInt

/** How OBD answers are shaped: CAN (ISO 15765-4, one long message) or K-line/J1850 (7-byte messages). */
enum class ObdBusStyle { CAN, SERIAL }

enum class FuelType { PETROL, DIESEL }

/**
 * SAE J1979 services 01-0A for an emission-related ECU.
 *
 * - 01: supported-PID bitmaps (00/20/40/...), readiness (PID 01) and live values from a [SignalSource];
 *   several PIDs per request are answered in one message, unsupported PIDs are skipped, and a request with
 *   no supported PID gets no answer (J1979 behaviour on functional requests).
 * - 02: freeze frame 0 (PID 02 = DTC that stored it), from [freezeFrame].
 * - 03 / 07 / 0A: stored / pending / permanent DTCs. CAN: `43 <count> <dtc>...`; serial: `43 d1 d2 d3`
 *   per message, padded with zeros.
 * - 04: clears stored and pending DTCs and the freeze frame, sets every readiness monitor to incomplete.
 *   Permanent DTCs stay (they clear only after a drive cycle without the fault).
 * - 09: VIN (02), CALID (04), CVN (06), ECU name (0A, CAN only); on serial buses the multi-message format
 *   with message counts (01/03/05), e.g. the VIN in five messages.
 */
class ObdEngineEcu(
    private val signals: SignalSource,
    val fuel: FuelType = FuelType.PETROL,
    val supportedPids: Set<Int> = if (fuel == FuelType.DIESEL) DIESEL_PIDS else PETROL_PIDS,
    val vin: String? = null,
    storedDtcs: List<String> = emptyList(),
    pendingDtcs: List<String> = emptyList(),
    permanentDtcs: List<String> = emptyList(),
    val calibrationIds: List<String> = emptyList(),
    /** Calibration verification numbers, 8 hex digits each. */
    val cvns: List<String> = emptyList(),
    /** J1979 ECU name: acronym (up to 4) and text (up to 15), e.g. "ECM" / "EngineControl". */
    val ecuName: Pair<String, String>? = null,
    var freezeFrame: SignalSource? = FrozenSignals(),
    /** OBD standard, PID 1C (0x06 = EOBD, 0x09 = EOBD + OBD + OBD-II...). */
    val obdStandard: Int = 0x06,
    /** Readiness monitors (PID 01 byte C bits) reported as not complete. */
    var incompleteMonitors: Int = 0,
    val style: ObdBusStyle = ObdBusStyle.CAN,
) : EcuBehavior {
    val stored = storedDtcs.toMutableList()
    val pending = pendingDtcs.toMutableList()
    val permanent = permanentDtcs.toMutableList()
    var freezeFrameDtc: String? = storedDtcs.firstOrNull()
    var distanceSinceClearKm = 1830
    var warmUpsSinceClear = 41
    var clearCount = 0
        private set

    override fun accepts(request: ByteArray): Boolean = request.isNotEmpty() && request.u8(0) in 0x01..0x0A

    override fun respond(request: ByteArray): List<ByteArray> = when (request.u8(0)) {
        0x01 -> listOfNotNull(service01(request))
        0x02 -> listOfNotNull(service02(request))
        0x03 -> dtcResponse(0x43, stored)
        0x07 -> dtcResponse(0x47, pending)
        0x0A -> dtcResponse(0x4A, permanent)
        0x04 -> { clear(); listOf(bytesOf(0x44)) }
        0x09 -> service09(request)
        else -> emptyList()
    }

    fun clear() {
        stored.clear()
        pending.clear()
        freezeFrame = null
        freezeFrameDtc = null
        distanceSinceClearKm = 0
        warmUpsSinceClear = 0
        incompleteMonitors = monitorMask()
        clearCount++
    }

    // --- service 01 / 02 ---------------------------------------------------------------------------

    private fun service01(request: ByteArray): ByteArray? {
        val out = ArrayList<Byte>()
        out += 0x41.toByte()
        for (i in 1 until request.size) {
            val pid = request.u8(i)
            val data = pidData(pid, signals) ?: continue
            out += pid.toByte()
            out.addAll(data.toList())
        }
        return if (out.size > 1) out.toByteArray() else null
    }

    private fun service02(request: ByteArray): ByteArray? {
        if (request.size < 2) return null
        val pid = request.u8(1)
        val frame = if (request.size >= 3) request.u8(2) else 0
        if (frame != 0) return null
        val ff = freezeFrame
        val data: ByteArray = when {
            pid == 0x02 -> (freezeFrameDtc?.let(::dtcCode) ?: 0).u16()
            pid % 0x20 == 0 -> bitmap(pid, if (ff == null) setOf(0x02) else supportedPids.filter { it != 0x01 && it != 0x1C }.toSet() + 0x02)
            ff == null || pid !in supportedPids || pid == 0x01 -> return null
            else -> pidData(pid, ff) ?: return null
        }
        return byteArrayOf(0x42, pid.toByte(), frame.toByte()) + data
    }

    private fun pidData(pid: Int, s: SignalSource): ByteArray? {
        if (pid % 0x20 == 0) {
            // PID 0x20 exists only if a PID above 0x20 does (bit 0x20 of the previous bitmap), and so on.
            if (pid != 0 && supportedPids.none { it > pid }) return null
            return bitmap(pid, supportedPids)
        }
        if (pid !in supportedPids) return null
        return when (pid) {
            0x01 -> readiness()
            0x03 -> bytesOf(if (fuel == FuelType.PETROL && s.rpm > 0) 0x02 else 0x00, 0x00)
            0x04 -> b1(s.engineLoadPct * 255 / 100)
            0x05 -> b1(s.coolantC + 40)
            0x06 -> b1(s.shortTrimPct * 128 / 100 + 128)
            0x07 -> b1(s.longTrimPct * 128 / 100 + 128)
            0x0B -> b1(s.manifoldKpa)
            0x0C -> b2(s.rpm * 4)
            0x0D -> b1(s.speedKmh)
            0x0F -> b1(s.intakeC + 40)
            0x10 -> b2(s.mafGs * 100)
            0x11 -> b1(s.throttlePct * 255 / 100)
            0x13 -> bytesOf(0x03)
            0x14 -> bytesOf(clamp(s.o2Volts * 200, 0xFF), clamp(s.shortTrimPct * 128 / 100 + 128, 0xFF))
            0x15 -> bytesOf(clamp(0.7 * 200, 0xFF), 0xFF)
            0x1C -> bytesOf(obdStandard)
            0x1F -> b2(s.runTimeS)
            0x21 -> b2(if (stored.isNotEmpty()) 214.0 else 0.0)
            0x23 -> b2(s.railPressureKpa / 10)
            0x2F -> b1(s.fuelLevelPct * 255 / 100)
            0x30 -> b1(warmUpsSinceClear.toDouble())
            0x31 -> b2(distanceSinceClearKm.toDouble())
            0x33 -> b1(s.baroKpa)
            0x42 -> b2(s.moduleVolts * 1000)
            0x46 -> b1(s.ambientC + 40)
            0x5C -> b1(s.oilC + 40)
            else -> null
        }
    }

    /** PID 01: MIL + DTC count, common monitors (misfire, fuel, components), engine-specific monitors. */
    private fun readiness(): ByteArray {
        val a = (if (stored.isNotEmpty()) 0x80 else 0) or minOf(stored.size, 0x7F)
        val compression = if (fuel == FuelType.DIESEL) 0x08 else 0x00
        val commonIncomplete = if (incompleteMonitors == monitorMask()) 0x40 else 0x00 // components monitor
        val b = compression or 0x07 or commonIncomplete
        return bytesOf(a, b, monitorMask(), incompleteMonitors and monitorMask())
    }

    /** Petrol: catalyst, evap, O2 sensor, O2 heater. Diesel: NMHC cat, NOx/SCR, boost, exhaust sensor, PM filter, EGR. */
    private fun monitorMask(): Int = if (fuel == FuelType.DIESEL) 0xEB else 0x65

    private fun bitmap(base: Int, pids: Set<Int>): ByteArray {
        var bits = 0L
        for (p in pids) if (p in (base + 1)..(base + 0x20)) bits = bits or (1L shl (32 - (p - base)))
        // The "next range supported" bit (base + 0x20) is set when any higher PID is supported.
        if (pids.any { it > base + 0x20 }) bits = bits or 1L
        return bytesOf(((bits shr 24) and 0xFF).toInt(), ((bits shr 16) and 0xFF).toInt(), ((bits shr 8) and 0xFF).toInt(), (bits and 0xFF).toInt())
    }

    // --- DTC services ------------------------------------------------------------------------------

    private fun dtcResponse(positive: Int, codes: List<String>): List<ByteArray> {
        val raw = codes.flatMap { dtcCode(it).u16().toList() }
        if (style == ObdBusStyle.CAN) return listOf(byteArrayOf(positive.toByte(), codes.size.toByte()) + raw.toByteArray())
        if (raw.isEmpty()) return listOf(byteArrayOf(positive.toByte(), 0, 0, 0, 0, 0, 0))
        return raw.chunked(6).map { chunk -> byteArrayOf(positive.toByte()) + (chunk + List(6 - chunk.size) { 0.toByte() }).toByteArray() }
    }

    // --- service 09 --------------------------------------------------------------------------------

    private fun service09(request: ByteArray): List<ByteArray> {
        if (request.size < 2) return emptyList()
        val pid = request.u8(1)
        val vinBytes = vin?.encodeToByteArray()
        val calBytes = calibrationIds.map { it.encodeToByteArray().copyOf(16) }
        val cvnBytes = cvns.map { parseCvn(it) }
        val supported = buildSet {
            if (vinBytes != null) { add(0x02); if (style == ObdBusStyle.SERIAL) add(0x01) }
            if (calBytes.isNotEmpty()) { add(0x04); if (style == ObdBusStyle.SERIAL) add(0x03) }
            if (cvnBytes.isNotEmpty()) { add(0x06); if (style == ObdBusStyle.SERIAL) add(0x05) }
            if (ecuName != null && style == ObdBusStyle.CAN) add(0x0A)
        }
        if (pid == 0x00) return listOf(byteArrayOf(0x49, 0x00) + bitmap(0, supported))
        if (pid !in supported) return emptyList()
        if (style == ObdBusStyle.CAN) {
            val body: ByteArray = when (pid) {
                0x02 -> byteArrayOf(1) + vinBytes!!
                0x04 -> byteArrayOf(calBytes.size.toByte()) + calBytes.fold(ByteArray(0)) { a, b -> a + b }
                0x06 -> byteArrayOf(cvnBytes.size.toByte()) + cvnBytes.fold(ByteArray(0)) { a, b -> a + b }
                0x0A -> byteArrayOf(1) + ecuNameBytes(ecuName!!)
                else -> return emptyList()
            }
            return listOf(byteArrayOf(0x49, pid.toByte()) + body)
        }
        // Serial buses: 4 data bytes per message, numbered from 1.
        val stream: ByteArray = when (pid) {
            0x01 -> return listOf(bytesOf(0x49, 0x01, 0x05))
            0x03 -> return listOf(bytesOf(0x49, 0x03, calBytes.size * 4))
            0x05 -> return listOf(bytesOf(0x49, 0x05, cvnBytes.size))
            0x02 -> ByteArray(3) + vinBytes!!
            0x04 -> calBytes.fold(ByteArray(0)) { a, b -> a + b }
            0x06 -> cvnBytes.fold(ByteArray(0)) { a, b -> a + b }
            else -> return emptyList()
        }
        return stream.toList().chunked(4).mapIndexed { i, c -> byteArrayOf(0x49, pid.toByte(), (i + 1).toByte()) + c.toByteArray() }
    }

    private fun ecuNameBytes(name: Pair<String, String>): ByteArray {
        val acronym = name.first.take(4).padEnd(4, '\u0000')
        val text = name.second.take(15).padEnd(15, '\u0000')
        return "$acronym-$text".encodeToByteArray()
    }

    private fun parseCvn(hex: String): ByteArray {
        require(hex.length == 8) { "CVN must be 8 hex digits: $hex" }
        return ByteArray(4) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun clamp(v: Double, max: Int): Int = v.roundToInt().coerceIn(0, max)
    private fun b1(v: Double): ByteArray = bytesOf(clamp(v, 0xFF))
    private fun b2(v: Double): ByteArray = clamp(v, 0xFFFF).u16()

    companion object {
        val PETROL_PIDS: Set<Int> = setOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x1F,
            0x21, 0x2F, 0x30, 0x31, 0x33, 0x42, 0x46, 0x5C,
        )
        val DIESEL_PIDS: Set<Int> = setOf(
            0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x21, 0x23, 0x2F, 0x30, 0x31, 0x33,
            0x42, 0x46, 0x5C,
        )
        /** A 2003-era petrol engine: no PIDs above 0x20. */
        val LEGACY_PETROL_PIDS: Set<Int> = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15, 0x1C)
    }
}
