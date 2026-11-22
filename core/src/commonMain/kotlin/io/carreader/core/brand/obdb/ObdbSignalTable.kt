package io.carreader.core.brand.obdb

import io.carreader.core.brand.ScaledDataItem
import io.carreader.core.brand.SignalFormat

/**
 * One OBDb Mode 0x22 signal as flattened by `tools/obdb/generate_obdb_table.py` (format 2, 23 columns:
 * `hdr|rax|did|din|fyFrom|fyTo|fyYears|id|path|name|bix|len|sign|blsb|mul|div|add|min|max|nullmin|nullmax|unit|map`).
 *
 * [header] is the full CAN request id (3 hex digits for 11-bit, 8 for 29-bit, e.g. 18DA10F1) and
 * [responseId] the full response id when OBDb names it, so brands whose replies are not request + 8 work.
 */
data class ObdbSignal(
    val header: Int,
    val responseId: Int?,
    val extendedId: Boolean,
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
        // from <= to is an inclusive range; otherwise the bounds are alternatives (OBDb reference semantics).
        if (yearFrom != null && yearTo != null && yearFrom <= yearTo) return modelYear in yearFrom..yearTo
        if (yearFrom != null && modelYear >= yearFrom) return true
        if (yearTo != null && modelYear <= yearTo) return true
        return false
    }

    /**
     * Key that treats two signals as the same measurement: same DID, bit layout, scaling, unit and enumeration.
     * Names are left out on purpose, so one value is not listed twice when two models' tables word it differently.
     */
    val layoutKey: String
        get() = with(format) { "$header:$did:$bitOffset:$bitLength:$signed:$littleEndian:$mul:$div:$add:$unit:$enumMap" }

    /** Bits of the answer this signal reads. */
    val bits: IntRange get() = format.bitOffset until format.bitOffset + format.bitLength

    /** "up to 2019", "from 2020", "2021, 2023": the model-year filter in words, null without one. */
    val yearText: String?
        get() = when {
            years.isNotEmpty() && yearFrom == null && yearTo == null -> years.sorted().joinToString(", ")
            yearFrom != null && yearTo != null && yearFrom <= yearTo -> "$yearFrom-$yearTo"
            yearFrom != null && yearTo != null -> "up to $yearTo or from $yearFrom"
            yearFrom != null -> "from $yearFrom"
            yearTo != null -> "up to $yearTo"
            else -> null
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

/** A bundled OBDb table: generated text chunks plus provenance. */
interface ObdbTable {
    val source: String
    val url: String
    val chunks: Array<String>
}

object ObdbSignalTable {
    const val FIELDS = 23

    fun parse(table: ObdbTable): List<ObdbSignal> = parse(table.chunks, table.source)

    fun parse(chunks: Array<String>, source: String): List<ObdbSignal> =
        chunks.flatMap { chunk -> chunk.lineSequence().filter { it.isNotBlank() }.map { parseLine(it, source) }.toList() }

    fun parseLine(line: String, source: String): ObdbSignal {
        val f = line.split('|')
        require(f.size == FIELDS) { "Bad OBDb row (${f.size} fields): $line" }
        fun d(i: Int): Double? = f[i].takeIf { it.isNotEmpty() }?.toDouble()
        fun n(i: Int): Int? = f[i].takeIf { it.isNotEmpty() }?.toInt()
        val map = f[22].takeIf { it.isNotEmpty() }?.split(';')?.associate {
            val eq = it.indexOf('=')
            it.substring(0, eq).toLong() to it.substring(eq + 1)
        }
        val header = f[0]
        require(header.length == 3 || header.length == 8) { "Header must be 3 or 8 hex digits: $line" }
        return ObdbSignal(
            header = header.toLong(16).toInt(),
            responseId = f[1].takeIf { it.isNotEmpty() }?.toLong(16)?.toInt(),
            extendedId = header.length == 8,
            did = f[2].toInt(16),
            session = f[3].takeIf { it.isNotEmpty() }?.toInt(16),
            yearFrom = n(4),
            yearTo = n(5),
            years = f[6].takeIf { it.isNotEmpty() }?.split(',')?.map { it.toInt() }?.toSet() ?: emptySet(),
            id = f[7],
            path = f[8],
            name = f[9],
            format = SignalFormat(
                bitOffset = n(10) ?: 0,
                bitLength = n(11)!!,
                signed = f[12] == "1",
                littleEndian = f[13] == "1",
                mul = d(14) ?: 1.0,
                div = d(15) ?: 1.0,
                add = d(16) ?: 0.0,
                min = d(17),
                max = d(18),
                nullMin = d(19),
                nullMax = d(20),
                unit = f[21].ifEmpty { "scalar" },
                enumMap = map,
            ),
            source = source,
        )
    }

    /**
     * Signals for one request id from several tables, earlier tables first, de-duplicated by
     * [ObdbSignal.layoutKey] and by OBDb id. Used when the exact model or year is unknown and the tables share
     * most DIDs.
     *
     * When kept signals read overlapping bits of the same DID with different layouts, no single answer is right
     * for every car: each variant stays and its name gets the model of its table, plus its model years when two
     * variants come from the same table ("Battery voltage, BCM (Fiat 500, up to 2019)"). Two kept signals that
     * still share a name get their DID appended. The user can tell every entry apart instead of trusting a value
     * decoded with another car's layout.
     */
    fun merged(sets: List<List<ObdbSignal>>, header: Int, modelYear: Int?): List<ScaledDataItem> {
        val seen = HashSet<String>()
        val kept = ArrayList<ObdbSignal>()
        for (set in sets) for (s in set) {
            if (s.header != header || !s.appliesTo(modelYear)) continue
            if (seen.add(s.layoutKey) && seen.add("id:${s.id}")) kept += s
        }
        val byDid = kept.groupBy { it.did }
        val names = kept.associateWith { s ->
            val rivals = byDid.getValue(s.did).filter { it !== s && it.layoutKey != s.layoutKey && it.bits.overlaps(s.bits) }
            if (rivals.isEmpty()) return@associateWith s.name
            val sameTable = rivals.any { it.source == s.source }
            val tag = listOfNotNull(modelOf(s.source), s.yearText.takeIf { sameTable }).joinToString(", ")
            "${s.name} ($tag)"
        }
        val counts = names.values.groupingBy { it }.eachCount()
        return kept.map { s ->
            val name = names.getValue(s)
            val unique = if ((counts[name] ?: 0) > 1) "$name (DID ${s.did.toString(16).uppercase().padStart(4, '0')})" else name
            s.toDataItem().let { if (unique == s.name) it else it.renamed(unique) }
        }
    }

    private fun IntRange.overlaps(other: IntRange) = first <= other.last && other.first <= last

    /** "OBDb Fiat-500X" → "Fiat 500X". */
    fun modelOf(source: String): String = source.removePrefix("OBDb ").replace('-', ' ')

    private fun ScaledDataItem.renamed(newName: String) =
        ScaledDataItem(key, newName, did, format, group, requiredSession, source)
}
