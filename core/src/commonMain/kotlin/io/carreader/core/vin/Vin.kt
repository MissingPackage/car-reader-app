package io.carreader.core.vin

/** ISO 3779 vehicle identification number: WMI (1-3), VDS (4-9), VIS (10-17). */
data class Vin(val value: String) {
    init {
        require(isWellFormed(value)) { "Not a VIN: '$value'" }
    }

    val wmi: String get() = value.substring(0, 3)
    val vds: String get() = value.substring(3, 9)
    val vis: String get() = value.substring(9, 17)

    companion object {
        private val ALLOWED = Regex("^[A-HJ-NPR-Z0-9]{17}$")
        fun isWellFormed(text: String): Boolean = ALLOWED.matches(text)
        fun parseOrNull(text: String?): Vin? = text?.trim()?.uppercase()?.takeIf(::isWellFormed)?.let(::Vin)
    }
}

/** Who built the vehicle, resolved from the WMI. [groupId] keys the brand module (e.g. "vag"). */
data class Manufacturer(val name: String, val brand: String, val groupId: String?, val country: String? = null)

/** Port: WMI lookup, so the table can grow or be replaced by a downloadable dataset. */
interface WmiResolver {
    fun resolve(vin: Vin): Manufacturer?
}
