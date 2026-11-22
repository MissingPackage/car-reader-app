package io.carreader.core.brand

import io.carreader.core.link.EcuAddress
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.LiveValue
import io.carreader.core.uds.UdsClient
import io.carreader.core.vin.Manufacturer
import io.carreader.core.vin.Vin

/** What we know about the car when choosing and driving a brand module. */
data class VehicleContext(
    val vin: Vin?,
    val manufacturer: Manufacturer?,
    /** Model year if known (user input or decoded); brand modules may pick variants with it. */
    val modelYear: Int? = null,
    val canBus: Boolean,
)

enum class EcuProtocol { UDS, KWP2000 }

/** A control unit a brand module knows how to reach. [code] is the brand's own address label (VAG "01"). */
data class EcuDescriptor(
    val code: String,
    val name: String,
    val address: EcuAddress,
    val protocol: EcuProtocol = EcuProtocol.UDS,
)

/** A brand-specific measurement read via UDS ReadDataByIdentifier (0x22). */
interface DataItem {
    val key: String
    val name: String
    val unit: String
    val did: Int
    fun decode(data: ByteArray): LiveValue
}

/** Result of interrogating one ECU. */
data class EcuReport(
    val ecu: EcuDescriptor,
    val present: Boolean,
    val identification: List<Pair<String, String>> = emptyList(),
    val dtcs: List<Dtc> = emptyList(),
    val error: String? = null,
)

/**
 * Port: everything brand-specific. A new brand = a new implementation (usually extending [UdsBrandModule]),
 * registered in [BrandRegistry]. Brand modules never write: mutations go through the session and WriteGuard.
 */
interface BrandModule {
    val id: String
    val displayName: String

    fun supports(vehicle: VehicleContext): Boolean

    /** ECUs to probe for this vehicle, most important first. */
    fun ecus(vehicle: VehicleContext): List<EcuDescriptor>

    /** Probe one ECU: presence, identification, DTCs. Never throws for an absent ECU: returns present=false. */
    suspend fun scan(ecu: EcuDescriptor, uds: UdsClient): EcuReport

    /** Brand measurements available on [ecu]. */
    fun dataItems(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem>

    suspend fun readData(ecu: EcuDescriptor, item: DataItem, uds: UdsClient): LiveValue?

    /** Text for a manufacturer code, null when unknown. */
    fun describeDtc(ecu: EcuDescriptor, code: String, failureType: Int?): String?

    /** Notes about a whole scan (e.g. "this car probably uses VW TP2.0, not supported yet"). */
    fun scanNotes(vehicle: VehicleContext, reports: List<EcuReport>): List<String> = emptyList()
}

/** Holds every brand module; the session asks it which one fits the connected car. */
class BrandRegistry(private val modules: List<BrandModule>) {
    fun all(): List<BrandModule> = modules
    fun forVehicle(vehicle: VehicleContext): BrandModule? = modules.firstOrNull { it.supports(vehicle) }
    fun byId(id: String): BrandModule? = modules.firstOrNull { it.id == id }
}
