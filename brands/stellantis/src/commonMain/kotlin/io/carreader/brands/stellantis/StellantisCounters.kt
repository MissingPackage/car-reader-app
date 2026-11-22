package io.carreader.brands.stellantis

import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.mileage.CounterCatalog
import io.carreader.core.mileage.CounterKind
import io.carreader.core.mileage.CounterOrigin
import io.carreader.core.mileage.CounterRole
import io.carreader.core.mileage.CounterSource

/**
 * Mileage counters of Stellantis cars, all from the bundled OBDb tables (CC BY-SA 4.0, see ATTRIBUTION.md).
 * An entry names a unit and a DID; it is offered only when the car's platform probes that unit and every
 * bundled table that has the row decodes it the same way (a DID read with two layouts is too doubtful to
 * compare). DID, scaling and name come from the OBDb row unchanged.
 *
 * Deliberately left out:
 * - The instrument cluster: no source names its address on either platform, so there is no dashboard value;
 *   the check compares the module totals with each other (and with OBD-II PID A6 where the car has it).
 * - FCA cars: OBDb's Fiat odometer rows are still "dbg"; the North American Jeep engine odometer (7E0/043B,
 *   8.192-mile steps) comes from Jeep tables only and the same WMIs also cover Ram, Dodge and Chrysler
 *   models no source covers, so it is not offered.
 *
 * OBDb marks the PSA rows as verified for some model years only ("dbgfilter") and the session does not know
 * the year, so [UNVERIFIED_NOTE] says so. With no dashboard source the worst verdict is DIFFERENCES.
 * - Mileage-since-service, distance-to-oil-change and particulate-filter distances: not lifetime totals.
 */
class StellantisCounters(private val module: StellantisBrandModule) : CounterCatalog {

    internal data class Entry(val ecu: EcuDescriptor, val did: Int, val platform: ObdbBackedPlatform)

    internal val entries: List<Entry> = listOf(
        // PSA: engine and gearbox keep their own totals (OBDb Peugeot 208/308/3008), the electric units of the e-208 too.
        Entry(PsaPlatform.ENGINE, 0xD49C, PsaPlatform),
        Entry(PsaPlatform.GEARBOX, 0xD802, PsaPlatform),
        Entry(PsaPlatform.TRACTION_BATTERY, 0xD49C, PsaPlatform),
        Entry(PsaPlatform.ELECTRIC_POWERTRAIN, 0xD49C, PsaPlatform),
        Entry(PsaPlatform.ELECTRIC_MOTOR, 0xD49C, PsaPlatform),
    )

    override fun notes(vehicle: VehicleContext): List<String> {
        val fca = module.platformsFor(vehicle).any { it.id == StellantisPlatformId.FCA }
        return listOf(CLUSTER_NOTE, if (fca) FCA_NOTE else UNVERIFIED_NOTE)
    }

    override fun sources(vehicle: VehicleContext): List<CounterSource> {
        val probed = module.ecuCatalog(vehicle).map { it.address }.toSet()
        return entries.mapNotNull { entry ->
            if (entry.ecu.address !in probed) return@mapNotNull null
            val rows = entry.platform.allSignals().filter { it.header == entry.ecu.address.requestId && it.did == entry.did && it.appliesTo(vehicle.modelYear) }
            val signal = rows.firstOrNull() ?: return@mapNotNull null
            if (rows.any { it.format != signal.format || it.session != signal.session }) return@mapNotNull null
            CounterSource(
                id = "stellantis.${entry.ecu.code}.${entry.did.hex(4)}",
                kind = CounterKind.DISTANCE,
                role = CounterRole.TOTAL,
                label = signal.name,
                origin = CounterOrigin.BrandData(entry.ecu, signal.toDataItem()),
                provenance = "${rows.map { it.source }.distinct().joinToString(", ")}, ${entry.ecu.code}/${entry.did.hex(4)}",
                ceiling = signal.format.max,
            )
        }
    }

    private fun Int.hex(width: Int) = toString(16).uppercase().padStart(width, '0')

    companion object {
        const val CLUSTER_NOTE = "The instrument cluster's mileage is not read: no public source gives its address on Stellantis cars, so module totals are compared with each other."
        const val FCA_NOTE = "On FCA-platform cars no public mileage identifier is verified for the engine or other units yet, so only the OBD-II odometer can be read."
        const val UNVERIFIED_NOTE = "The module totals come from community data verified for some model years only: a difference between them is a reason to look closer, not a proof."
    }
}
