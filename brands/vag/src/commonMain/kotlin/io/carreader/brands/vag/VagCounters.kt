package io.carreader.brands.vag

import io.carreader.brands.vag.data.ObdbSignal
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.mileage.CounterCatalog
import io.carreader.core.mileage.CounterKind
import io.carreader.core.mileage.CounterOrigin
import io.carreader.core.mileage.CounterRole
import io.carreader.core.mileage.CounterSource

/**
 * Mileage and operating-time counters of VW group cars, all taken from the bundled OBDb signal sets
 * (CC BY-SA 4.0, see ATTRIBUTION.md): each entry names the OBDb signal id per model table, and the DID,
 * session and scaling come from that row unchanged. A counter is offered for a car only when its model table
 * (or, for an unknown model, either table) has the row.
 *
 * Deliberately left out:
 * - Gateway 710/2A0A "PASSAT_ODO_V2": its row has offset -40 and minimum -40 (a temperature pattern), and the
 *   Audi Q5 set uses the same DID for a battery temperature. Too doubtful to compare.
 * - PQ35-prefixed rows (the platform before MQB, e.g. the 16-bit little-endian clutch kiss-point stamps: a byte
 *   order wrong for a given gearbox turns 12 000 km into 450 600 km and would raise a false alarm) and the "Odometer with ECU installed" row (unit metres in a
 *   24-bit field, at most 16 777 m, so not a lifetime total).
 * - Airbag, door and other units: no OBDb row stores mileage there.
 * - The mileage stamps stored with UDS fault codes (DTC snapshot / extended data): VAG's record layout is not
 *   public, so it is named in [notes] instead of being guessed.
 */
object VagCounters : CounterCatalog {

    internal data class Entry(
        val role: CounterRole,
        val ecu: EcuDescriptor,
        val passatId: String?,
        val q5Id: String?,
    ) {
        val kind: CounterKind get() = if (role == CounterRole.OPERATING_TIME) CounterKind.OPERATING_TIME else CounterKind.DISTANCE
    }

    internal val entries: List<Entry> = listOf(
        // Instrument cluster: the dashboard total. High resolution first, the 03-session copy second.
        Entry(CounterRole.DASHBOARD, VagEcus.INSTRUMENTS, "PASSAT_ODO_MILEAGE_HR", "Q5_ODO_MILEAGE_HR"),
        Entry(CounterRole.DASHBOARD, VagEcus.INSTRUMENTS, "PASSAT_ODO_DASH", null),
        // Running copies in other units.
        Entry(CounterRole.TOTAL, VagEcus.ENGINE, "PASSAT_ODO", "Q5_ODO"),
        Entry(CounterRole.TOTAL, VagEcus.ENGINE, "PASSAT_ODO_HD", "Q5_ODO_HD"),
        Entry(CounterRole.TOTAL, VagEcus.TRANSMISSION, "PASSAT_ODO_V1", null),
        Entry(CounterRole.TOTAL, VagEcus.TRANSMISSION, "PASSAT_MQB_TCM_ODO_2", null),
        // Mileage recorded at past events.
        Entry(CounterRole.EVENT_STAMP, VagEcus.ENGINE, "PASSAT_CHAIN_ELONGATION", "Q5_CHAIN_ELONGATION"),
        Entry(CounterRole.EVENT_STAMP, VagEcus.ENGINE, "PASSAT_CHAIN_ELONGATION_INLET", "Q5_CHAIN_ELONGATION_INLET"),
        Entry(CounterRole.EVENT_STAMP, VagEcus.BRAKES, "PASSAT_BDD_ODO", null),
        Entry(CounterRole.EVENT_STAMP, VagEcus.BRAKES, "PASSAT_TPMS_ODO_RDG_AT_LAST_ADAPT", null),
        Entry(CounterRole.EVENT_STAMP, VagEcus.BRAKES, "PASSAT_TPMS_ODO_RDG_AT_LAST_WARN", null),
        Entry(CounterRole.EVENT_STAMP, VagEcus.BRAKES, "PASSAT_CNT_STEER_SUPPORT_ODO_RDG_OF_LAST_ACT", null),
        // Operating time: gearbox drive time, a better match for distance than engine hours (no idling).
        Entry(CounterRole.OPERATING_TIME, VagEcus.TRANSMISSION, "PASSAT_DRIVE_TIME", "Q5_DRIVE_TIME"),
    )

    const val SNAPSHOT_NOTE = "Mileage stamps stored with fault codes are not read: the VW group record layout is not public."
    const val UNITS_NOTE = "No public identifier is known for a mileage value in the airbag, gateway, door or comfort units."

    override fun notes(vehicle: VehicleContext): List<String> = listOf(SNAPSHOT_NOTE, UNITS_NOTE)

    override fun sources(vehicle: VehicleContext): List<CounterSource> {
        val model = VagData.modelOf(vehicle)
        val passat = VagData.passat.associateBy { it.id }
        val q5 = VagData.q5.associateBy { it.id }
        return entries.mapNotNull { entry ->
            val signal: ObdbSignal = when (model) {
                VagModel.PASSAT -> entry.passatId?.let(passat::get)
                VagModel.Q5 -> entry.q5Id?.let(q5::get)
                // Unknown model (no VIN, or Golf, Tiguan, A4...): only rows both tables share with the same
                // layout. Model-specific DIDs could decode to nonsense on another gearbox or ESC unit.
                VagModel.UNKNOWN -> {
                    val p = entry.passatId?.let(passat::get)
                    val q = entry.q5Id?.let(q5::get)
                    p.takeIf { p != null && q != null && p.header == q.header && p.did == q.did && p.format == q.format && p.session == q.session }
                }
            } ?: return@mapNotNull null
            if (signal.header != entry.ecu.address.requestId || !signal.appliesTo(vehicle.modelYear)) return@mapNotNull null
            CounterSource(
                id = "vag.${signal.id}",
                kind = entry.kind,
                role = entry.role,
                label = signal.name,
                origin = CounterOrigin.BrandData(entry.ecu, signal.toDataItem()),
                provenance = "${signal.source}, ${signal.header.hex(3)}/${signal.did.hex(4)}",
                ceiling = signal.format.max,
            )
        }
    }

    private fun Int.hex(width: Int) = toString(16).uppercase().padStart(width, '0')
}
