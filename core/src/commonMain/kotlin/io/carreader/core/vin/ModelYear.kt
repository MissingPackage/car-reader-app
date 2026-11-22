package io.carreader.core.vin

/**
 * Model-year code at VIN position 10 (ISO 3779 / North American practice). The code repeats every 30 years,
 * and EU regulation does not require manufacturers to use it, so the result is only a hint for display.
 */
object ModelYear {
    /** Codes in order from 1980 (A) to 2009 (9); 2010 starts again at A. I, O, Q, U, Z and 0 are never used. */
    private const val CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /** Every year from 1980 up to [maxYear] that position 10 could stand for, oldest first. */
    fun candidates(vin: Vin, maxYear: Int = 2039): List<Int> {
        val index = CODES.indexOf(vin.value[9])
        if (index < 0) return emptyList()
        return generateSequence(1980 + index) { it + 30 }.takeWhile { it <= maxYear }.toList()
    }

    /**
     * Most plausible year: the latest candidate not after [currentYear] + 1 (model years run ahead of the
     * calendar). Null when position 10 is not a year code. Treat as a hint: European VINs may use it freely.
     */
    fun hint(vin: Vin, currentYear: Int): Int? = candidates(vin, currentYear + 1).lastOrNull()
}
