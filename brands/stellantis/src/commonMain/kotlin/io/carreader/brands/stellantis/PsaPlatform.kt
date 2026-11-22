package io.carreader.brands.stellantis

import io.carreader.brands.stellantis.data.ObdbPeugeot208
import io.carreader.brands.stellantis.data.ObdbPeugeot3008
import io.carreader.brands.stellantis.data.ObdbPeugeot308
import io.carreader.brands.stellantis.data.ObdbPeugeotE208
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.link.EcuAddress

/**
 * PSA architecture on 11-bit CAN, UDS. Every address here comes from an OBDb Peugeot signal set (request
 * header and "rax" response id: 0x6xx units answer on request - 0x20, 0x7xx units on request - 0x100). Units that no source names
 * (ABS/ESP, airbag, instrument cluster, ...) are not probed: their addresses are unknown to this app.
 *
 * Measurements: the Peugeot 208, 308, 3008 and e-208 tables merged; the same engine, gearbox and battery
 * units serve the other PSA-platform models, which have no OBDb table yet, so values that do not apply to
 * a car simply read as not available.
 */
object PsaPlatform : ObdbBackedPlatform(listOf(ObdbPeugeot208, ObdbPeugeot308, ObdbPeugeot3008, ObdbPeugeotE208)) {
    override val id = StellantisPlatformId.PSA

    private fun ecu(name: String, request: Int, response: Int) =
        EcuDescriptor(request.toString(16).uppercase(), name, EcuAddress(request, response))

    val ENGINE = ecu("Engine", 0x6A8, 0x688)
    val BODY_COMPUTER = ecu("Body computer (BSI)", 0x752, 0x652)
    val GEARBOX = ecu("Automatic gearbox", 0x6A9, 0x689)
    val POWER_STEERING = ecu("Power steering", 0x6B5, 0x695)
    val GEAR_SELECTOR = ecu("Gear selector", 0x74D, 0x64D)
    val TRACTION_BATTERY = ecu("High-voltage battery", 0x6B4, 0x694)
    val ELECTRIC_POWERTRAIN = ecu("Electric powertrain control", 0x6A2, 0x682)
    val ELECTRIC_MOTOR = ecu("Electric motor", 0x6A6, 0x686)

    val all: List<EcuDescriptor> = listOf(
        ENGINE, BODY_COMPUTER, GEARBOX, POWER_STEERING, GEAR_SELECTOR, TRACTION_BATTERY, ELECTRIC_POWERTRAIN, ELECTRIC_MOTOR,
    )

    /** PSA cars diagnose on 11-bit CAN only. */
    override fun ecus(vehicle: VehicleContext): List<EcuDescriptor> =
        if (vehicle.canBus && !vehicle.extendedCan) all else emptyList()

    override val openUnits: Set<Int> = (0x7E0..0x7E7).toSet()
}
