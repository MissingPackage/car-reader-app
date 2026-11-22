package io.carreader.brands.vag

import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.GenericDtcText
import io.carreader.core.brand.IdFormat
import io.carreader.core.brand.IdentificationDid
import io.carreader.core.brand.UdsBrandModule
import io.carreader.core.brand.VehicleContext
import io.carreader.core.mileage.CounterCatalog

/**
 * Volkswagen group (VW, Audi, Skoda, Seat, Cupra) on UDS over 11-bit CAN: MQB and MLB-evo cars, e.g.
 * Passat B8 (2015+) and Q5 FY (2017+). Older cars (Passat B6/B7, Q5 8R) reach most modules only through
 * KWP2000 over VW TP2.0, which ELM327 adapters cannot do; [scanNotes] says so when it looks like that case.
 *
 * The UI can force this module for any car through [io.carreader.core.brand.BrandRegistry.byId] ("vag").
 */
class VagBrandModule(genericText: GenericDtcText = GenericDtcText.Sae) : UdsBrandModule(genericText) {
    override val id: String = ID
    override val displayName: String = "Volkswagen Group (VW, Audi, Skoda, Seat, Cupra)"

    override fun supports(vehicle: VehicleContext): Boolean {
        vehicle.manufacturer?.let { return it.groupId == ID }
        // No manufacturer resolved: fall back to the group's own WMIs.
        val wmi = vehicle.vin?.wmi ?: return false
        return wmi in VAG_WMIS
    }

    override fun ecuCatalog(vehicle: VehicleContext): List<EcuDescriptor> = VagEcus.all

    override val identificationDids: List<IdentificationDid> = listOf(
        IdentificationDid("Part number", 0xF187),
        IdentificationDid("Software version", 0xF189),
        IdentificationDid("Hardware number", 0xF191),
        IdentificationDid("Hardware version", 0xF1A3),
        IdentificationDid("System name", 0xF197),
        IdentificationDid("Serial number", 0xF18C),
        IdentificationDid("VIN", 0xF190),
        IdentificationDid("Coding", 0x0600, IdFormat.HEX),
    )

    override val counterCatalog: CounterCatalog get() = VagCounters

    override fun dataCatalog(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem> =
        VagData.itemsFor(ecu.address.requestId, vehicle)

    override fun absentNote(ecu: EcuDescriptor): String? =
        if (ecu.address.requestId == VagEcus.GATEWAY.address.requestId) TP20_NOTE else null

    override fun scanNotes(vehicle: VehicleContext, reports: List<EcuReport>): List<String> {
        if (!vehicle.canBus) return listOf(KLINE_NOTE)
        val present = reports.filter { it.present }.map { it.ecu.address.requestId }
        if (reports.size > 1 && present.isNotEmpty() && present.all { it in VagEcus.obdRange }) return listOf(TP20_NOTE)
        return emptyList()
    }

    companion object {
        const val ID = "vag"

        const val TP20_NOTE = "Only the engine-side modules answered on UDS. This car probably reaches its other " +
            "modules through VW TP2.0 (KWP2000), used before MQB/MLB-evo (e.g. Passat B6/B7, Q5 8R). ELM327 " +
            "adapters cannot speak TP2.0, so those modules are not supported yet."

        const val KLINE_NOTE = "This car does not use CAN for diagnostics: VAG modules on K-line (KW1281/KWP2000) " +
            "are not supported yet."

        /** WMIs of the group (VW, VW commercial, Audi, Skoda, Seat/Cupra), used only when no manufacturer is known. */
        val VAG_WMIS = setOf(
            "WVW", "WV1", "WV2", "WV3", "1VW", "3VW", "9BW", "AAV", "XW8",
            "WAU", "WA1", "WUA", "TRU",
            "TMB", "VSS", "VS6",
        )
    }
}
