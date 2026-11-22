package io.carreader.core.obd2

/**
 * Decoding of service 01 PID 01 (monitor status since DTCs cleared), SAE J1979.
 *
 * Byte A: bit 7 MIL, bits 0-6 stored emission DTC count.
 * Byte B: bit 3 ignition type (0 spark, 1 compression); bits 0-2 continuous monitors supported
 *         (misfire, fuel system, components); bits 4-6 the same monitors *incomplete*.
 * Bytes C/D: non-continuous monitors supported (C) and incomplete (D); their meaning depends on the ignition type.
 */
object Readiness {
    private val continuous = listOf("Misfire", "Fuel system", "Comprehensive components")

    /** Bit index in C/D -> monitor name, spark ignition. Bit 4 was "A/C refrigerant" in early revisions. */
    private val spark = mapOf(
        0 to "Catalyst",
        1 to "Heated catalyst",
        2 to "Evaporative system",
        3 to "Secondary air system",
        4 to "Gasoline particulate filter",
        5 to "Oxygen sensor",
        6 to "Oxygen sensor heater",
        7 to "EGR / VVT system",
    )

    /** Bit index in C/D -> monitor name, compression ignition (bits 2 and 4 are reserved). */
    private val compression = mapOf(
        0 to "NMHC catalyst",
        1 to "NOx / SCR aftertreatment",
        3 to "Boost pressure",
        5 to "Exhaust gas sensor",
        6 to "Particulate filter",
        7 to "EGR / VVT system",
    )

    /** [data] is A, B, C, D (after the `41 01` echo). */
    fun decode(data: ByteArray): ReadinessReport {
        require(data.size >= 4) { "PID 01 needs 4 data bytes, got ${data.size}" }
        val a = data[0].toInt() and 0xFF
        val b = data[1].toInt() and 0xFF
        val c = data[2].toInt() and 0xFF
        val d = data[3].toInt() and 0xFF
        val diesel = b and 0x08 != 0
        val monitors = ArrayList<MonitorStatus>()
        continuous.forEachIndexed { i, name ->
            val supported = b and (1 shl i) != 0
            val incomplete = b and (1 shl (i + 4)) != 0
            monitors += MonitorStatus(name, supported, supported && !incomplete)
        }
        val table = if (diesel) compression else spark
        for ((bit, name) in table) {
            val supported = c and (1 shl bit) != 0
            val incomplete = d and (1 shl bit) != 0
            monitors += MonitorStatus(name, supported, supported && !incomplete)
        }
        return ReadinessReport(
            milOn = a and 0x80 != 0,
            storedDtcCount = a and 0x7F,
            compressionIgnition = diesel,
            monitors = monitors,
        )
    }

    /**
     * Merges the reports of several ECUs: MIL on if any ECU lights it, DTC counts summed, a monitor supported if
     * any ECU supports it and complete only when every ECU that supports it reports it complete.
     * The ignition type (and so the monitor names) comes from the first report, which callers order engine first.
     */
    fun merge(reports: List<ReadinessReport>): ReadinessReport? {
        if (reports.isEmpty()) return null
        val first = reports.first()
        val same = reports.filter { it.compressionIgnition == first.compressionIgnition }
        val monitors = first.monitors.map { m ->
            val all = same.mapNotNull { r -> r.monitors.firstOrNull { it.name == m.name } }
            val supporting = all.filter { it.supported }
            MonitorStatus(m.name, supporting.isNotEmpty(), supporting.isNotEmpty() && supporting.all { it.complete })
        }
        return ReadinessReport(
            milOn = reports.any { it.milOn },
            storedDtcCount = reports.sumOf { it.storedDtcCount },
            compressionIgnition = first.compressionIgnition,
            monitors = monitors,
        )
    }
}

/** PID 01 as a PidDefinition: not a number, the text summarises MIL and DTC count; use [Readiness] for detail. */
object MonitorStatusPid : BasePid(0x01, "Monitor status since DTCs cleared", "", 4) {
    override fun decode(data: ByteArray): LiveValue {
        requireLength(data)
        val r = Readiness.decode(data)
        val incomplete = r.monitors.count { it.supported && !it.complete }
        val text = "MIL ${if (r.milOn) "on" else "off"}, ${r.storedDtcCount} DTC, " +
            "${if (r.compressionIgnition) "compression" else "spark"} ignition, $incomplete monitor(s) incomplete"
        return label(text)
    }
}
