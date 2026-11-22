package io.carreader.brands.stellantis

import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.brand.obdb.ObdbSignal
import io.carreader.core.brand.obdb.ObdbSignalTable
import io.carreader.core.brand.obdb.ObdbTable

/** The two electrical architectures inside Stellantis. They differ in addressing, DIDs and tables. */
enum class StellantisPlatformId {
    /** Groupe PSA architecture: Peugeot, Citroën, DS, Opel/Vauxhall since 2017-2019, and PSA-based Fiat/Jeep/Lancia/Alfa models. */
    PSA,

    /** FCA architecture: Fiat, Abarth, Alfa Romeo, Lancia, Jeep, Chrysler, Dodge, Ram. */
    FCA,
}

/**
 * Port inside the Stellantis adapter: one implementation per platform supplies the units it can reach on the
 * connected bus and the measurements for each. The scan algorithm is the shared [io.carreader.core.brand.UdsBrandModule].
 */
interface StellantisPlatform {
    val id: StellantisPlatformId

    /** Units to probe on this car's bus, most important first. Empty when the platform cannot be reached on it. */
    fun ecus(vehicle: VehicleContext): List<EcuDescriptor>

    fun dataItems(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem>

    /** Request ids every car answers without gateway authentication (the OBD emission units). */
    val openUnits: Set<Int>
}

/** A platform whose measurements come from bundled OBDb tables, merged when the exact model is unknown. */
abstract class ObdbBackedPlatform(private val tables: List<ObdbTable>) : StellantisPlatform {
    internal val signals: List<List<ObdbSignal>> by lazy { tables.map { ObdbSignalTable.parse(it) } }

    /** Every signal of every table, for tests and catalogs. */
    internal fun allSignals(): List<ObdbSignal> = signals.flatten()

    override fun dataItems(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem> {
        if (ecus(vehicle).none { it.address == ecu.address }) return emptyList()
        return ObdbSignalTable.merged(signals, ecu.address.requestId, vehicle.modelYear)
    }
}
