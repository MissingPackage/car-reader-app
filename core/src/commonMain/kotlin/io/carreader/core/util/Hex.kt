package io.carreader.core.util

/** Hex helpers shared by every layer. Formatting is upper-case, space separated ("01 0C"). */
object Hex {
    fun encode(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

    /** Parses "01 0C", "010C" or "01-0c"; ignores whitespace. Throws on odd length or bad digits. */
    fun decode(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != '-' && it != ':' }
        require(clean.length % 2 == 0) { "Odd hex length: '$text'" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

fun ByteArray.toHex(separator: String = " "): String = Hex.encode(this, separator)
fun String.hexToBytes(): ByteArray = Hex.decode(this)
fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
fun Int.hex2(): String = toString(16).uppercase().padStart(2, '0')
fun Int.hex3(): String = toString(16).uppercase().padStart(3, '0')
fun Int.hex4(): String = toString(16).uppercase().padStart(4, '0')
