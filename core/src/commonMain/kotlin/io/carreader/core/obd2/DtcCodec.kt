package io.carreader.core.obd2

/**
 * SAE J2012 / ISO 15031-6 two-byte DTC encoding, shared by OBD-II (2-byte codes) and UDS (3-byte codes,
 * whose upper 16 bits use the same layout).
 * Bits 15-14: system (P, C, B, U); bits 13-12: first digit (0-3); bits 11-0: three hex digits.
 */
object DtcCodec {
    private val systems = charArrayOf('P', 'C', 'B', 'U')

    fun format(twoBytes: Int): String {
        val v = twoBytes and 0xFFFF
        val system = systems[(v shr 14) and 0x03]
        val first = (v shr 12) and 0x03
        val rest = (v and 0x0FFF).toString(16).uppercase().padStart(3, '0')
        return "$system$first$rest"
    }

    /** "P0301" -> 0x0301. Returns null for malformed input. */
    fun parse(code: String): Int? {
        if (code.length != 5) return null
        val system = systems.indexOf(code[0].uppercaseChar())
        val first = code[1].digitToIntOrNull() ?: return null
        val digits = code.substring(2)
        if (!digits.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        val rest = digits.toInt(16)
        if (system < 0 || first !in 0..3) return null
        return (system shl 14) or (first shl 12) or rest
    }

    /** UDS 3-byte DTC: SAE code from the upper 16 bits. */
    fun formatUds(threeBytes: Int): String = format((threeBytes shr 8) and 0xFFFF)
}
