package io.carreader.core.uds

/**
 * Plain-English text for the UDS failure-type byte (third DTC byte), following the category layout of
 * SAE J2012 / ISO 15031-6: the high nibble is the category, the low nibble the sub-type.
 */
object FailureTypes {
    private val exact = mapOf(
        0x00 to "no sub-type information",
        0x01 to "general electrical failure",
        0x02 to "general signal failure",
        0x03 to "frequency or PWM signal failure",
        0x04 to "internal system failure",
        0x05 to "programming failure",
        0x06 to "algorithm-based failure",
        0x07 to "mechanical failure",
        0x08 to "bus signal or message failure",
        0x09 to "component failure",
        // 0x1X electrical circuit
        0x11 to "circuit short to ground",
        0x12 to "circuit short to battery",
        0x13 to "circuit open",
        0x14 to "circuit short to ground or open",
        0x15 to "circuit short to battery or open",
        0x16 to "circuit voltage below threshold",
        0x17 to "circuit voltage above threshold",
        0x18 to "circuit current below threshold",
        0x19 to "circuit current above threshold",
        0x1A to "circuit resistance below threshold",
        0x1B to "circuit resistance above threshold",
        0x1C to "circuit voltage out of range",
        0x1D to "circuit current out of range",
        0x1E to "circuit resistance out of range",
        0x1F to "circuit intermittent",
        // 0x2X signal level
        0x21 to "signal amplitude too low",
        0x22 to "signal amplitude too high",
        0x23 to "signal stuck low",
        0x24 to "signal stuck high",
        0x25 to "signal shape or waveform wrong",
        0x26 to "signal changes too slowly",
        0x27 to "signal changes too quickly",
        0x28 to "signal bias or zero point out of range",
        0x29 to "signal invalid",
        0x2A to "signal stuck in range",
        0x2B to "signals cross-coupled",
        0x2F to "signal erratic",
        // 0x3X frequency / pulse signals
        0x31 to "no signal",
        0x32 to "signal low time too short",
        0x33 to "signal low time too long",
        0x34 to "signal high time too short",
        0x35 to "signal high time too long",
        0x36 to "signal frequency too low",
        0x37 to "signal frequency too high",
        0x38 to "signal frequency wrong",
        0x39 to "too few pulses",
        0x3A to "too many pulses",
        // 0x4X internal control unit failures
        0x41 to "checksum failure",
        0x42 to "memory failure",
        0x43 to "special memory failure",
        0x44 to "data memory failure",
        0x45 to "program memory failure",
        0x46 to "calibration or parameter memory failure",
        0x47 to "watchdog or safety processor failure",
        0x48 to "supervision software failure",
        0x49 to "internal electronic failure",
        0x4A to "wrong component installed",
        0x4B to "over temperature",
        // 0x5X programming / configuration
        0x51 to "not programmed",
        0x52 to "not activated",
        0x53 to "deactivated",
        0x54 to "calibration missing",
        0x55 to "not configured",
        0x56 to "configuration invalid or incompatible",
        0x57 to "software component invalid or incompatible",
        // 0x6X algorithm based
        0x61 to "signal calculation failure",
        0x62 to "signal comparison failure",
        0x63 to "circuit or component protection time-out",
        0x64 to "signal implausible",
        0x65 to "too few signal transitions",
        0x66 to "too many signal transitions",
        0x67 to "signal wrong after event",
        0x68 to "event information",
        // 0x7X mechanical
        0x71 to "actuator stuck",
        0x72 to "actuator stuck open",
        0x73 to "actuator stuck closed",
        0x74 to "actuator slipping",
        0x75 to "emergency position not reachable",
        0x76 to "wrong mounting position",
        0x77 to "commanded position not reachable",
        0x78 to "alignment or adjustment incorrect",
        0x79 to "mechanical linkage failure",
        0x7A to "fluid leak or seal failure",
        0x7B to "fluid level low",
        // 0x8X bus / message
        0x81 to "invalid serial data received",
        0x82 to "message counter wrong or not updated",
        0x83 to "message protection (checksum) wrong",
        0x84 to "signal below allowed range",
        0x85 to "signal above allowed range",
        0x86 to "invalid message",
        0x87 to "missing message",
        0x88 to "bus off",
        0x8F to "bus signal erratic",
        // 0x9X component / system operation
        0x91 to "parametric failure",
        0x92 to "performance or incorrect operation",
        0x93 to "no operation",
        0x94 to "unexpected operation",
        0x95 to "incorrect assembly",
        0x96 to "component internal failure",
        0x97 to "component or system blocked",
        0x98 to "component or system over temperature",
    )

    private fun category(high: Int): String? = when (high) {
        0x1 -> "circuit failure"
        0x2 -> "signal level failure"
        0x3 -> "frequency or pulse signal failure"
        0x4 -> "internal control unit failure"
        0x5 -> "programming or configuration failure"
        0x6 -> "algorithm-based failure"
        0x7 -> "mechanical failure"
        0x8 -> "bus or message failure"
        0x9 -> "component or system operation failure"
        0xF -> "manufacturer-specific failure"
        else -> null
    }

    /** Short description of [ftb], or null for reserved values without a known category. */
    fun describe(ftb: Int): String? {
        val v = ftb and 0xFF
        exact[v]?.let { return it }
        val cat = category(v shr 4) ?: return null
        return "$cat (sub-type 0x${v.toString(16).uppercase().padStart(2, '0')})"
    }
}
