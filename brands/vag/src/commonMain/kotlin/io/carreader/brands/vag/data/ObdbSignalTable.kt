package io.carreader.brands.vag.data

import io.carreader.core.brand.ScaledDataItem
import io.carreader.core.brand.SignalFormat

/** One OBDb Mode 22 signal, as flattened by tools/generate_obdb_table.py. */
internal data class ObdbSignal(
    val header: Int,
    val did: Int,
    /** Diagnostic session the ECU must be in ("din"), null for the default session. */
    val session: Int?,
    val yearFrom: Int?,
    val yearTo: Int?,
    val years: Set<Int>,
    val id: String,
    val path: String,
    val name: String,
    val format: SignalFormat,
    val source: String,
) {
    /** OBDb filter semantics: no filter = every year; otherwise from/to ranges or listed years match. */
    fun appliesTo(modelYear: Int?): Boolean {
        if (modelYear == null) return true
        if (yearFrom == null && yearTo == null && years.isEmpty()) return true
        if (modelYear in years) return true
        if (yearFrom != null && modelYear >= yearFrom) return true
        if (yearTo != null && modelYear <= yearTo) return true
        return false
    }

    fun toDataItem(): ScaledDataItem = ScaledDataItem(
        key = id,
        name = name,
        did = did,
        format = format,
        group = path.ifEmpty { null },
        requiredSession = session,
        source = source,
    )
}

internal object ObdbSignalTable {
    fun parse(chunks: Array<String>, source: String): List<ObdbSignal> =
        chunks.flatMap { chunk -> chunk.lineSequence().filter { it.isNotBlank() }.map { parseLine(it, source) }.toList() }

    fun parseLine(line: String, source: String): ObdbSignal {
        val f = line.split('|')
        require(f.size == 22) { "Bad OBDb row (${f.size} fields): $line" }
        fun d(i: Int): Double? = f[i].takeIf { it.isNotEmpty() }?.toDouble()
        fun n(i: Int): Int? = f[i].takeIf { it.isNotEmpty() }?.toInt()
        val map = f[21].takeIf { it.isNotEmpty() }?.split(';')?.associate {
            val eq = it.indexOf('=')
            it.substring(0, eq).toLong() to it.substring(eq + 1)
        }
        return ObdbSignal(
            header = f[0].toInt(16),
            did = f[1].toInt(16),
            session = f[2].takeIf { it.isNotEmpty() }?.toInt(16),
            yearFrom = n(3),
            yearTo = n(4),
            years = f[5].takeIf { it.isNotEmpty() }?.split(',')?.map { it.toInt() }?.toSet() ?: emptySet(),
            id = f[6],
            path = f[7],
            name = f[8],
            format = SignalFormat(
                bitOffset = n(9) ?: 0,
                bitLength = n(10)!!,
                signed = f[11] == "1",
                littleEndian = f[12] == "1",
                mul = d(13) ?: 1.0,
                div = d(14) ?: 1.0,
                add = d(15) ?: 0.0,
                min = d(16),
                max = d(17),
                nullMin = d(18),
                nullMax = d(19),
                unit = f[20].ifEmpty { "scalar" },
                enumMap = map,
            ),
            source = source,
        )
    }
}
