package io.carreader.core.obd2

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Reusable building blocks for [PidDefinition]s, so a new standard PID is one line in [StandardPids].
 *
 * Conventions for every decoder here:
 * - `decode(data)` receives the data bytes after the PID echo (A, B, C, ...), at least [PidDefinition.bytes] long.
 * - [LiveValue.text] is the formatted value without the unit (the unit is in [LiveValue.unit]); for enums and
 *   bit fields it is a human-readable label and [LiveValue.value] is null.
 * - [LiveValue.key] is "01.0C"-style: service and PID in upper-case hex.
 */
abstract class BasePid(
    final override val pid: Int,
    final override val name: String,
    final override val unit: String,
    final override val bytes: Int,
) : PidDefinition {
    override val key: String get() = "${service.toString(16).uppercase().padStart(2, '0')}.${pid.toString(16).uppercase().padStart(2, '0')}"

    protected fun requireLength(data: ByteArray) {
        require(data.size >= bytes) { "PID ${pid.toString(16)} needs $bytes data bytes, got ${data.size}" }
    }

    protected fun numeric(value: Double, decimals: Int): LiveValue =
        LiveValue(key = key, name = name, value = value, unit = unit, text = formatDecimal(value, decimals))

    protected fun label(text: String): LiveValue = LiveValue(key = key, name = name, value = null, unit = unit, text = text)

    override fun toString(): String = "PID $key $name"
}

/**
 * Linear PID: `value = raw * scale + offset`, where raw is the unsigned (or two's-complement when [signed])
 * big-endian integer made of [width] bytes starting at [startByte].
 * [bytes] is the full data length the ECU sends, which can exceed [width] (e.g. O2 PIDs send lambda + voltage).
 */
open class ScaledPid(
    pid: Int,
    name: String,
    unit: String,
    bytes: Int,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val decimals: Int = 0,
    val width: Int = bytes,
    val startByte: Int = 0,
    val signed: Boolean = false,
) : BasePid(pid, name, unit, bytes) {
    init {
        require(width in 1..4 && startByte + width <= bytes) { "Bad width/startByte for PID $pid" }
    }

    fun raw(data: ByteArray): Long {
        var raw = 0L
        for (i in startByte until startByte + width) raw = (raw shl 8) or (data[i].toLong() and 0xFF)
        if (signed) {
            val bits = width * 8
            if (raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        return raw
    }

    override fun decode(data: ByteArray): LiveValue {
        requireLength(data)
        return numeric(raw(data) * scale + offset, decimals)
    }
}

/** One-byte (by default) enumeration: the value maps to a label; unknown values render as "Unknown (n)". */
open class EnumPid(
    pid: Int,
    name: String,
    val labels: Map<Int, String>,
    bytes: Int = 1,
) : BasePid(pid, name, "", bytes) {
    override fun decode(data: ByteArray): LiveValue {
        requireLength(data)
        val v = data[0].toInt() and 0xFF
        return label(labels[v] ?: "Unknown ($v)")
    }
}

/**
 * Bit field: [flags] maps a bit index (0 = least significant bit of the last byte, counting across all bytes
 * big-endian) to a label; the text lists the labels whose bit is set, or [noneText].
 */
open class BitfieldPid(
    pid: Int,
    name: String,
    bytes: Int,
    val flags: Map<Int, String>,
    val noneText: String = "None",
) : BasePid(pid, name, "", bytes) {
    fun setBits(data: ByteArray): List<Int> {
        requireLength(data)
        var raw = 0L
        for (i in 0 until bytes) raw = (raw shl 8) or (data[i].toLong() and 0xFF)
        return (0 until bytes * 8).filter { raw and (1L shl it) != 0L }
    }

    override fun decode(data: ByteArray): LiveValue {
        val set = setBits(data).mapNotNull { flags[it] }
        return label(if (set.isEmpty()) noneText else set.joinToString(", "))
    }
}

/** Fixed-point formatting usable from common code (no String.format): 1726.5 with 1 decimal -> "1726.5". */
fun formatDecimal(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val factor = 10.0.pow(decimals)
    val scaled = (abs(value) * factor).roundToLong()
    val negative = value < 0 && scaled != 0L
    val digits = scaled.toString()
    val text = if (decimals == 0) {
        digits
    } else {
        val padded = digits.padStart(decimals + 1, '0')
        padded.substring(0, padded.length - decimals) + "." + padded.substring(padded.length - decimals)
    }
    return if (negative) "-$text" else text
}
