package io.carreader.brands.stellantis

import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.GatewayFinding
import io.carreader.core.brand.GenericDtcText
import io.carreader.core.brand.IdentificationDid
import io.carreader.core.brand.SecureGatewayCheck
import io.carreader.core.brand.UdsBrandModule
import io.carreader.core.brand.VehicleContext
import io.carreader.core.mileage.CounterCatalog

/**
 * Stellantis group: Fiat, Abarth, Alfa Romeo, Lancia, Jeep (FCA architecture) and Peugeot, Citroën, DS,
 * Opel/Vauxhall (PSA architecture), read-only over UDS. The platform is chosen per car:
 * - a 29-bit CAN bus means the FCA architecture;
 * - an 11-bit bus with a PSA-group VIN means the PSA architecture;
 * - an 11-bit bus with a North American VIN means FCA units on 11-bit CAN (Jeep Wrangler, Grand Cherokee);
 * - an 11-bit bus with another FCA-brand VIN means a PSA-based model (Jeep Avenger, Fiat 600, Lancia Ypsilon),
 *   probed as PSA; see [choosePlatforms].
 *
 * Secure gateway: FCA fitted a Secure Gateway Module to most vehicles from model year 2018 (basic reads
 * pass, clearing DTCs and active tests need an authenticated tool); recent PSA-platform cars have a
 * gateway that may block unauthenticated requests altogether. [scanNotes] reports what the scan saw:
 * explicit access refusals, or only the OBD units answering.
 *
 * Opel/Vauxhall cars of the General Motors era (WMI W0L) are not Stellantis platforms and are not served here.
 */
class StellantisBrandModule(
    genericText: GenericDtcText = GenericDtcText.Sae,
    private val platforms: List<StellantisPlatform> = listOf(FcaPlatform, PsaPlatform),
) : UdsBrandModule(genericText) {
    override val id: String = ID
    override val displayName: String = "Stellantis (Fiat, Alfa Romeo, Lancia, Jeep, Abarth, Peugeot, Citroën, DS, Opel/Vauxhall)"

    override val counterCatalog: CounterCatalog by lazy { StellantisCounters(this) }

    override fun supports(vehicle: VehicleContext): Boolean {
        val wmi = vehicle.vin?.wmi
        if (wmi in GM_ERA_OPEL_WMIS) return false
        vehicle.manufacturer?.let { return it.groupId == ID }
        return wmi != null && (wmi in PSA_WMIS || wmi in FCA_WMIS)
    }

    /** The platforms to probe and whether the choice is certain (decides how a silent scan is explained). */
    data class PlatformChoice(val platforms: List<StellantisPlatform>, val certain: Boolean)

    /**
     * - 29-bit bus: FCA (PSA cars diagnose on 11-bit CAN);
     * - PSA-group WMI: PSA;
     * - North American WMI (1-5...): FCA on 11-bit CAN only. PSA request ids are never sent there: on those
     *   cars the OBD port can sit on a production bus, where foreign ids are not harmless;
     * - other FCA-brand WMI on 11-bit CAN: PSA (recent PSA-based Fiat, Jeep, Lancia, Alfa Romeo models), not
     *   certain: older FCA-platform cars on 11-bit CAN use addresses no source names;
     * - no VIN on 11-bit CAN: none. Without the VIN the platform cannot be told, and each platform's ids could
     *   land on the other's production bus; [scanNotes] says so.
     */
    fun choosePlatforms(vehicle: VehicleContext): PlatformChoice {
        if (!vehicle.canBus) return PlatformChoice(emptyList(), true)
        val wmi = vehicle.vin?.wmi
        val (wanted, certain) = when {
            vehicle.extendedCan -> listOf(StellantisPlatformId.FCA) to true
            wmi == null -> emptyList<StellantisPlatformId>() to false
            wmi in PSA_WMIS -> listOf(StellantisPlatformId.PSA) to true
            wmi.first() in NORTH_AMERICA -> listOf(StellantisPlatformId.FCA) to true
            else -> listOf(StellantisPlatformId.PSA) to false
        }
        return PlatformChoice(platforms.filter { it.id in wanted && it.ecus(vehicle).isNotEmpty() }, certain)
    }

    /** Platforms worth probing for this car, in probing order. */
    fun platformsFor(vehicle: VehicleContext): List<StellantisPlatform> = choosePlatforms(vehicle).platforms

    public override fun ecuCatalog(vehicle: VehicleContext): List<EcuDescriptor> =
        platformsFor(vehicle).flatMap { it.ecus(vehicle) }.distinctBy { it.address }

    /** ISO 14229-1 identification DIDs; units that lack one answer 0x31 and the value is skipped. */
    override val identificationDids: List<IdentificationDid> = listOf(
        IdentificationDid("Part number", 0xF187),
        IdentificationDid("Supplier", 0xF18A),
        IdentificationDid("Hardware number", 0xF191),
        IdentificationDid("Supplier software number", 0xF194),
        IdentificationDid("Supplier software version", 0xF195),
        IdentificationDid("System name", 0xF197),
        IdentificationDid("Serial number", 0xF18C),
        IdentificationDid("VIN", 0xF190),
    )

    override fun dataCatalog(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem> =
        platformsFor(vehicle).firstNotNullOfOrNull { p -> p.dataItems(ecu, vehicle).ifEmpty { null } } ?: emptyList()

    override fun scanNotes(vehicle: VehicleContext, reports: List<EcuReport>): List<String> {
        if (!vehicle.canBus) return listOf(NO_CAN_NOTE)
        // Forced onto a car of another group: silence says nothing about a Stellantis gateway.
        if (vehicle.vin == null) return listOf(NO_VIN_NOTE)
        if (!supports(vehicle)) return emptyList()
        val choice = choosePlatforms(vehicle)
        val open = choice.platforms.flatMap { it.openUnits }.toSet()
        return when (SecureGatewayCheck.assess(reports, open, obdReachable = vehicle.vin != null)) {
            GatewayFinding.ACCESS_DENIED -> listOf(GATEWAY_DENIED_NOTE)
            GatewayFinding.ONLY_OPEN_UNITS_ANSWERED -> listOf(if (choice.certain) GATEWAY_SILENT_NOTE else UNKNOWN_UNITS_NOTE)
            GatewayFinding.NONE -> emptyList()
        }
    }

    companion object {
        const val ID = "stellantis"

        const val GATEWAY_DENIED_NOTE = "Some control units refused to be read (security access denied or " +
            "authentication required). This car probably has the Stellantis Secure Gateway (fitted from " +
            "about 2018): without a registered, authenticated tool it blocks those requests. What is shown was read " +
            "normally; the refused values are missing. This app cannot authenticate to the gateway, and clearing " +
            "fault codes will probably be refused too."

        const val GATEWAY_SILENT_NOTE = "Only the standard OBD-II units answered; every other control unit stayed " +
            "silent. Recent Stellantis cars have a Secure Gateway that can drop unauthenticated requests from the " +
            "OBD port, which looks exactly like this. It can also mean the ignition is not fully on or that this " +
            "model uses addresses the app does not know yet. Standard OBD-II data above is still valid."

        const val UNKNOWN_UNITS_NOTE = "None of the Stellantis control units this app knows answered. Older " +
            "Fiat, Alfa Romeo and Lancia platforms use addresses no public source names, and a Secure Gateway can " +
            "also block these requests. Standard OBD-II data above is still valid."

        const val NO_VIN_NOTE = "The VIN could not be read, so the app cannot tell which Stellantis platform this " +
            "car uses and did not probe its control units. Standard OBD-II data is still available."

        const val NO_CAN_NOTE = "This car does not use CAN for diagnostics: Stellantis control units on K-line are " +
            "not supported yet. Standard OBD-II data is still available."

        /** Groupe PSA world manufacturer identifiers (Peugeot, Citroën, DS, Stellantis-era Opel). */
        val PSA_WMIS: Set<String> = setOf("VF3", "VR3", "VF7", "VR7", "VR1", "VXK", "W0V")

        /** FCA-brand identifiers (Fiat, Alfa Romeo, Lancia, Maserati, Jeep, Chrysler). */
        val FCA_WMIS: Set<String> = setOf(
            "ZFA", "ZFC", "9BD", "SUF", "NM4", "ZAR", "ZLA", "ZAM", "ZAC", "1C4", "1C6", "1J4", "1J8", "3C3", "3C4",
        )

        /** First VIN character of North American plants (United States, Canada, Mexico). */
        private val NORTH_AMERICA = setOf('1', '2', '3', '4', '5')

        /** Opel/Vauxhall built on General Motors platforms: a different architecture. */
        val GM_ERA_OPEL_WMIS: Set<String> = setOf("W0L")
    }
}
