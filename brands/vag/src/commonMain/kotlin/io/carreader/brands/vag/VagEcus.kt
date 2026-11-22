package io.carreader.brands.vag

import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.link.EcuAddress

/**
 * VAG control units on 11-bit CAN (MQB / MLB-evo, UDS). Response id = request id + 0x6A, except the
 * OBD range (0x7E0-0x7E7, response +8).
 *
 * The first block is docs/CONTRACTS.md. The second block comes from headers seen in OBDb signal sets
 * (Volkswagen-Passat, Audi-Q5); their address labels are the usual VAG ones, still to confirm on a car.
 */
object VagEcus {
    private fun ecu(code: String, name: String, request: Int, response: Int = if (request in 0x7E0..0x7E7) request + 8 else request + 0x6A) =
        EcuDescriptor(code, name, EcuAddress(request, response))

    val ENGINE = ecu("01", "Engine", 0x7E0)
    val TRANSMISSION = ecu("02", "Transmission", 0x7E1)
    val BRAKES = ecu("03", "Brakes (ABS/ESC)", 0x713)
    val AIR_CONDITIONING = ecu("08", "Air conditioning", 0x746)
    val CENTRAL_ELECTRONICS = ecu("09", "Central electronics", 0x70E)
    val ADAPTIVE_CRUISE = ecu("13", "Adaptive cruise control", 0x757)
    val AIRBAG = ecu("15", "Airbag", 0x715)
    val STEERING_COLUMN = ecu("16", "Steering column electronics", 0x70C)
    val INSTRUMENTS = ecu("17", "Instruments", 0x714)
    val GATEWAY = ecu("19", "Gateway", 0x710)
    val DOOR_DRIVER = ecu("42", "Door electronics, driver", 0x74A)
    val STEERING_ASSIST = ecu("44", "Steering assist", 0x712)
    val DOOR_PASSENGER = ecu("52", "Door electronics, passenger", 0x74B)
    val INFORMATION_ELECTRONICS = ecu("5F", "Information electronics", 0x773)
    val PARK_ASSIST = ecu("76", "Park assist", 0x70A)

    // Seen in OBDb headers, not in CONTRACTS.md.
    val ALL_WHEEL_DRIVE = ecu("22", "All-wheel drive", 0x70F)
    val TELEMATICS = ecu("75", "Telematics / emergency call", 0x767)
    val HYBRID_BATTERY = ecu("8C", "Hybrid battery management", 0x7E5)
    val BATTERY_CHARGER = ecu("C6", "High-voltage battery charger", 0x744)
    /** OBDb (Audi-Q5) reads outside temperature and water pump values here; the VAG address label is unknown. */
    val THERMAL_742 = ecu("?742", "Thermal management (unconfirmed)", 0x742)

    /** Most important first: engine, gateway (tells MQB from TP2.0 cars early), then the rest. */
    val all: List<EcuDescriptor> = listOf(
        ENGINE, GATEWAY, TRANSMISSION, BRAKES, AIRBAG, INSTRUMENTS, CENTRAL_ELECTRONICS, AIR_CONDITIONING,
        STEERING_ASSIST, STEERING_COLUMN, ADAPTIVE_CRUISE, PARK_ASSIST, DOOR_DRIVER, DOOR_PASSENGER,
        INFORMATION_ELECTRONICS, ALL_WHEEL_DRIVE, TELEMATICS, HYBRID_BATTERY, BATTERY_CHARGER, THERMAL_742,
    )

    /** Units an ELM327 can reach even on a pre-MQB car: the OBD range is plain ISO 15765-4. */
    val obdRange: Set<Int> = setOf(0x7E0, 0x7E1, 0x7E5)
}
