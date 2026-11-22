package io.carreader.core.brand

import io.carreader.core.obd2.LiveValue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How to turn the data bytes of a 0x22 response (after the DID echo) into a number. Field names follow the
 * OBDb signal-set format (`fmt`): bit offset `bix`, bit length `len`, `sign`, `blsb`, `mul`, `div`, `add`,
 * `min`, `max`, `nullmin`, `nullmax`, `unit` and the optional enumeration `map`.
 *
 * Bits are counted MSB first from the first data byte. With [littleEndian] (`blsb`) the bytes that hold
 * the value are reversed before extraction, as the OBDb reference decoder does.
 */
data class SignalFormat(
    val bitOffset: Int = 0,
    val bitLength: Int,
    val signed: Boolean = false,
    val littleEndian: Boolean = false,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val add: Double = 0.0,
    val min: Double? = null,
    val max: Double? = null,
    val nullMin: Double? = null,
    val nullMax: Double? = null,
    /** OBDb unit name ("celsius", "kilometersPerHour", "offon", ...). */
    val unit: String = "scalar",
    /** Raw value → label, for enumerations (gear, status). */
    val enumMap: Map<Long, String>? = null,
) {
    init {
        require(bitLength in 1..64) { "Bit length out of range: $bitLength" }
        require(bitOffset >= 0) { "Negative bit offset" }
    }

    /** Raw integer before scaling; throws [IllegalArgumentException] when [data] is too short. */
    fun extractRaw(data: ByteArray): Long {
        val end = bitOffset + bitLength
        require(end <= data.size * 8) { "Not enough data: need $end bits, have ${data.size * 8}" }
        val bytes = if (littleEndian && bitLength > 8) {
            val copy = data.copyOf()
            val start = bitOffset / 8
            val count = (bitLength + 7) / 8
            val stop = minOf(start + count, copy.size)
            copy.copyOfRange(start, stop).reversedArray().copyInto(copy, start)
            copy
        } else data
        var raw = 0L
        for (bit in bitOffset until end) {
            val set = (bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1
            raw = (raw shl 1) or set.toLong()
        }
        if (signed && bitLength < 64 && (raw shr (bitLength - 1)) and 1L == 1L) raw -= 1L shl bitLength
        return raw
    }

    /** Scaled value, clamped to [min, max] like OBDb does; null when it falls on a null bound. */
    fun scale(raw: Long): Double? {
        var value = raw * mul / div + add
        val lo = min ?: 0.0
        if (max != null && max > lo) value = value.coerceIn(lo, max)
        if (nullMin != null && value <= nullMin + EPSILON) return null
        if (nullMax != null && value >= nullMax - EPSILON) return null
        return value
    }

    /** Decimal places that show one step of resolution (mul/div), at most 3. */
    val decimals: Int
        get() {
            val step = abs(mul / div)
            if (step >= 1.0 || step == 0.0) return 0
            return ceil(-log10(step) - 1e-9).toInt().coerceIn(0, 3)
        }

    private companion object {
        const val EPSILON = 2.220446049250313E-16
    }
}

/**
 * A brand measurement decoded by a [SignalFormat]. Any brand table (OBDb-derived or hand written) can
 * produce these; [requiredSession] is the UDS session the ECU must be in (0x03 for many VAG values).
 */
class ScaledDataItem(
    override val key: String,
    override val name: String,
    override val did: Int,
    val format: SignalFormat,
    /** Grouping for the UI ("Engine", "Transmission", ...). */
    val group: String? = null,
    val requiredSession: Int? = null,
    /** Free-text provenance ("OBDb Volkswagen-Passat"), shown in details. */
    val source: String? = null,
) : DataItem {
    override val unit: String = SignalUnits.symbol(format.unit)

    override fun decode(data: ByteArray): LiveValue {
        val raw = format.extractRaw(data)
        format.enumMap?.let { map ->
            val label = map[raw] ?: "Unknown ($raw)"
            return LiveValue(key, name, raw.toDouble(), unit, label)
        }
        val value = format.scale(raw)
        val text = when {
            value == null -> "not available"
            SignalUnits.isBoolean(format.unit) -> SignalUnits.booleanText(format.unit, value != 0.0)
            else -> formatNumber(value, format.decimals) + if (unit.isEmpty()) "" else " $unit"
        }
        return LiveValue(key, name, value, unit, text)
    }

    override fun toString(): String = "ScaledDataItem($key, did=${did.toString(16)})"
}

/** OBDb unit names → display symbols. */
object SignalUnits {
    private val symbols = mapOf(
        "ampereHours" to "Ah", "amps" to "A", "bars" to "bar", "celsius" to "°C", "centimeters" to "cm",
        "degrees" to "°", "fahrenheit" to "°F", "gravity" to "g", "hours" to "h", "kilometers" to "km",
        "kilometersPerHour" to "km/h", "kilopascal" to "kPa", "kilowattHours" to "kWh", "kilowatts" to "kW",
        "liters" to "l", "litersPerHour" to "l/h", "meters" to "m", "metersPerSecond" to "m/s",
        "metersPerSecondSquared" to "m/s²", "miles" to "mi", "milesPerHour" to "mph", "milliamps" to "mA",
        "milliseconds" to "ms", "millimeters" to "mm", "milliohms" to "mΩ", "millivolts" to "mV",
        "minutes" to "min", "newtonMeters" to "Nm", "ohms" to "Ω", "percent" to "%", "psi" to "psi",
        "radians" to "rad", "rpm" to "rpm", "seconds" to "s", "volts" to "V", "watts" to "W",
        "gramsPerSecond" to "g/s", "kilogramsPerHour" to "kg/h", "hertz" to "Hz",
    )
    private val booleans = setOf("offon", "onoff", "noyes", "yesno")

    fun symbol(unit: String): String = if (unit in booleans) "" else symbols[unit] ?: ""
    fun isBoolean(unit: String): Boolean = unit in booleans
    fun booleanText(unit: String, set: Boolean): String = when (unit) {
        "noyes", "yesno" -> if (set) "Yes" else "No"
        else -> if (set) "On" else "Off"
    }
}

/** Fixed-point formatting without platform String.format (common code). */
internal fun formatNumber(value: Double, decimals: Int): String {
    if (decimals <= 0) return value.roundToLong().toString()
    val factor = 10.0.pow(decimals)
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).toString().padStart(decimals, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign$whole.$frac"
}
