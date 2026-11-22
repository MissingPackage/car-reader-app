package io.carreader.core.brand.obdb

import kotlin.test.Test
import kotlin.test.assertEquals

class ObdbSignalTableTest {
    // hdr|rax|did|din|fyFrom|fyTo|fyYears|id|path|name|bix|len|sign|blsb|mul|div|add|min|max|nullmin|nullmax|unit|map
    private fun row(id: String, name: String, bix: Int, len: Int, div: String = "", unit: String = "volts", from: String = "", to: String = "", did: String = "1004") =
        "18DA40F1|18DAF140|$did||$from|$to||$id|Battery|$name|$bix|$len|0|0||$div|||||| $unit|".replace("| $unit|", "|$unit|")

    private fun set(source: String, vararg rows: String) = rows.map { ObdbSignalTable.parseLine(it, source) }

    private fun names(vararg sets: List<ObdbSignal>) = ObdbSignalTable.merged(sets.toList(), 0x18DA40F1, null).map { it.name }

    @Test
    fun parsesFullIds() {
        val s = ObdbSignalTable.parseLine(row("A", "V", 0, 8), "OBDb X-1")
        assertEquals(0x18DA40F1, s.header)
        assertEquals(0x18DAF140, s.responseId)
        assertEquals(true, s.extendedId)
    }

    @Test
    fun identicalLayoutsCollapseAndKeepTheirName() {
        assertEquals(listOf("Battery voltage"), names(set("OBDb Fiat-500X", row("A", "Battery voltage", 0, 8, "10")), set("OBDb Jeep-Renegade", row("B", "Battery voltage, BCM", 0, 8, "10"))))
    }

    @Test
    fun conflictingLayoutsAreTaggedWithModelAndYears() {
        val fiat500 = set("OBDb Fiat-500", row("A", "Battery voltage", 0, 8, "10", to = "2019"), row("B", "Battery voltage", 0, 16, "1000", from = "2020"))
        val x = set("OBDb Fiat-500X", row("C", "Battery voltage", 0, 8, "20"))
        assertEquals(
            listOf("Battery voltage (Fiat 500, up to 2019)", "Battery voltage (Fiat 500, from 2020)", "Battery voltage (Fiat 500X)"),
            names(fiat500, x),
        )
    }

    @Test
    fun sameLayoutButOtherUnitIsAConflict() {
        assertEquals(
            listOf("Speed (Jeep Renegade)", "Speed (Jeep Compass)"),
            names(set("OBDb Jeep-Renegade", row("A", "Speed", 0, 8, unit = "kilometersPerHour")), set("OBDb Jeep-Compass", row("B", "Speed", 0, 8, unit = "scalar"))),
        )
    }

    @Test
    fun fieldsOfOneDidAreNotConflicts() {
        val tires = set("OBDb Jeep-Wrangler", row("A", "Front left", 0, 8, unit = "kilopascal"), row("B", "Front right", 8, 8, unit = "kilopascal"))
        assertEquals(listOf("Front left", "Front right"), names(tires))
    }

    @Test
    fun duplicateNamesGetTheirDid() {
        val t = set("OBDb Peugeot-308", row("A", "Same", 0, 8, did = "D604"), row("B", "Same", 0, 8, did = "D605"))
        assertEquals(listOf("Same (DID D604)", "Same (DID D605)"), names(t))
    }
}
