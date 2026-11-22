package io.carreader.brands.stellantis

import io.carreader.brands.stellantis.data.ObdbFiat500
import io.carreader.brands.stellantis.data.ObdbFiat500X
import io.carreader.brands.stellantis.data.ObdbFiatPanda
import io.carreader.brands.stellantis.data.ObdbJeepCherokee
import io.carreader.brands.stellantis.data.ObdbJeepCompass
import io.carreader.brands.stellantis.data.ObdbJeepGladiator
import io.carreader.brands.stellantis.data.ObdbJeepGrandCherokee
import io.carreader.brands.stellantis.data.ObdbJeepRenegade
import io.carreader.brands.stellantis.data.ObdbJeepWrangler
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.link.EcuAddress

/**
 * FCA architecture, UDS. Two addressings, both taken from OBDb Fiat and Jeep signal sets:
 * - 29-bit CAN (European Fiat, Jeep Renegade/Compass/Cherokee...): physical 18DA<unit>F1, reply 18DAF1<unit>;
 * - 11-bit CAN (North American Jeep Wrangler/Gladiator/Grand Cherokee): 7E0/7E1 plus body units whose
 *   replies are not request + 8 (OBDb "rax").
 * The bus the car chose for OBD-II decides which list is probed. Unit names other than engine,
 * transmission and body computer describe what OBDb reads there; FCA's own labels are not public.
 */
object FcaPlatform : ObdbBackedPlatform(
    listOf(
        ObdbFiat500X, ObdbFiat500, ObdbFiatPanda, ObdbJeepRenegade, ObdbJeepCompass, ObdbJeepCherokee,
        ObdbJeepGrandCherokee, ObdbJeepWrangler, ObdbJeepGladiator,
    ),
) {
    override val id = StellantisPlatformId.FCA

    private fun unit29(name: String, unit: Int) = EcuDescriptor(
        unit.toString(16).uppercase().padStart(2, '0'), name,
        EcuAddress(0x18DA00F1 or (unit shl 8), 0x18DAF100 or unit, extendedId = true),
    )

    private fun unit11(name: String, request: Int, response: Int) =
        EcuDescriptor(request.toString(16).uppercase(), name, EcuAddress(request, response))

    val ENGINE_29 = unit29("Engine", 0x10)
    val TRANSMISSION_29 = unit29("Transmission", 0x18)
    val BODY_COMPUTER_29 = unit29("Body computer (BCM)", 0x40)
    val CHASSIS_29 = unit29("Chassis unit (steering angle, wheel speeds)", 0x30)
    val TIRES_BODY_29 = unit29("Tire pressure and body unit", 0xC7)

    val ENGINE_11 = unit11("Engine", 0x7E0, 0x7E8)
    val TRANSMISSION_11 = unit11("Transmission", 0x7E1, 0x7E9)
    val TIRE_PRESSURE_11 = unit11("Tire pressure monitoring", 0x740, 0x4C0)
    val TIRE_PRESSURE_OLD_11 = unit11("Tire pressure monitoring (older models)", 0x742, 0x4C2)
    val LIGHTING_11 = unit11("Lighting and wipers unit", 0x763, 0x4E3)
    val TELEMATICS_11 = unit11("Telematics / GPS", 0x7AB, 0x52B)

    val extended: List<EcuDescriptor> = listOf(ENGINE_29, TRANSMISSION_29, BODY_COMPUTER_29, CHASSIS_29, TIRES_BODY_29)
    val standard: List<EcuDescriptor> = listOf(ENGINE_11, TRANSMISSION_11, TIRE_PRESSURE_11, TIRE_PRESSURE_OLD_11, LIGHTING_11, TELEMATICS_11)

    override fun ecus(vehicle: VehicleContext): List<EcuDescriptor> = when {
        !vehicle.canBus -> emptyList()
        vehicle.extendedCan -> extended
        else -> standard
    }

    override val openUnits: Set<Int> = (0x7E0..0x7E7).toSet() + setOf(ENGINE_29.address.requestId, TRANSMISSION_29.address.requestId)
}
