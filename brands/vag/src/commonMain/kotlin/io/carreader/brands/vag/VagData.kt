package io.carreader.brands.vag

import io.carreader.brands.vag.data.ObdbAudiQ5
import io.carreader.brands.vag.data.ObdbSignal
import io.carreader.brands.vag.data.ObdbSignalTable
import io.carreader.brands.vag.data.ObdbVolkswagenPassat
import io.carreader.core.brand.ScaledDataItem
import io.carreader.core.brand.VehicleContext

/** Which OBDb signal set fits a car. */
enum class VagModel { PASSAT, Q5, UNKNOWN }

/** VAG live data from the bundled OBDb tables (CC BY-SA 4.0, see ATTRIBUTION.md). */
object VagData {
    internal val passat: List<ObdbSignal> by lazy { ObdbSignalTable.parse(ObdbVolkswagenPassat.chunks, ObdbVolkswagenPassat.SOURCE) }
    internal val q5: List<ObdbSignal> by lazy { ObdbSignalTable.parse(ObdbAudiQ5.chunks, ObdbAudiQ5.SOURCE) }

    // VAG VINs carry the model code in positions 7-8 (European layout).
    private val passatCodes = setOf("3C", "3G", "A3", "3H")
    private val q5Codes = setOf("8R", "FY")

    fun modelOf(vehicle: VehicleContext): VagModel {
        val code = vehicle.vin?.value?.substring(6, 8) ?: return VagModel.UNKNOWN
        return when (code) {
            in passatCodes -> VagModel.PASSAT
            in q5Codes -> VagModel.Q5
            else -> VagModel.UNKNOWN
        }
    }

    /**
     * Items for one request header. Known model: its own set. Unknown VAG car: both sets, de-duplicated by
     * DID and bit layout (most engine/gearbox DIDs are shared across the group).
     */
    fun itemsFor(header: Int, vehicle: VehicleContext): List<ScaledDataItem> {
        val sets = when (modelOf(vehicle)) {
            VagModel.PASSAT -> listOf(passat)
            VagModel.Q5 -> listOf(q5)
            VagModel.UNKNOWN -> listOf(passat, q5)
        }
        val seen = HashSet<String>()
        val out = ArrayList<ScaledDataItem>()
        for (set in sets) for (s in set) {
            if (s.header != header || !s.appliesTo(vehicle.modelYear)) continue
            val dedupe = "${s.did}:${s.format.bitOffset}:${s.format.bitLength}:${s.name.lowercase()}"
            if (seen.add(dedupe) && seen.add("id:${s.id}")) out += s.toDataItem()
        }
        return out
    }
}
