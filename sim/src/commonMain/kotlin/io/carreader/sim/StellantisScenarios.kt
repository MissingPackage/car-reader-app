package io.carreader.sim

import io.carreader.core.link.BusProtocol
import kotlin.math.roundToInt

/**
 * Stellantis cars for the simulator. Addresses and DID layouts follow the OBDb tables bundled in
 * brands/stellantis; part numbers, software versions and serials are made up. Fresh state per call.
 */
object StellantisScenarios {

    /** 2022 Peugeot 308 III 1.2 PureTech, PSA architecture on CAN 11-bit 500k. */
    fun peugeot308(adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR): SimScenario {
        val vin = "VF3LBHNSZNS123456"
        val vehicle = SimVehicleState(fuelLevelPercent = 62.0, ambientC = 16.0, odometerKm = 41_870)
        val signals = LiveSignals(vehicle, diesel = false)
        val engineDids = mapOf<Int, () -> ByteArray>(
            0xD402 to { (signals.speedKmh * 128).u16c() }, // vehicle speed, raw / 128 km/h
            0xD415 to { ((signals.coolantC + 273.14) * 10).u16c() }, // coolant, raw / 10 - 273.14 °C
            0xD49C to { vehicle.odometerKm.toLong().u32() }, // total mileage, km
        )
        val ecus = listOf(
            obdEngine(0x7E0, 0x7E8, signals, vin, stored = listOf("P0420"), calibration = "9832451780 01"),
            psaUnit("Engine", 0x6A8, ids("9832451780", "BOSCH", "0261S20123", "MED17.4.4", vin), engineDids, listOf(SimUdsDtc("P0420", 0x00, 0x2F))),
            psaUnit("Body computer (BSI)", 0x752, ids("9831234580", "VALEO", "BSI2020-1", "BSI", vin), emptyMap(), listOf(SimUdsDtc("B1A00", 0x13, 0x28))),
            psaUnit("Automatic gearbox", 0x6A9, ids("9829874480", "AISIN", "EAT8-02", "EAT8", vin), mapOf(0xD802 to { vehicle.odometerKm.u24() })),
            psaUnit("Power steering", 0x6B5, ids("9826669980", "JTEKT", "EPS-CMP", "DAE", vin), mapOf(0xD404 to { bytesOf((signals.moduleVolts * 10).roundToInt().coerceIn(0, 255)) })),
        )
        return SimScenario(
            name = "Peugeot 308 1.2 PureTech (2022)",
            description = "PSA architecture on CAN 11-bit 500k: engine, BSI, EAT8 gearbox and power steering answer",
            protocol = BusProtocol.CAN_11_500, adapter = adapter, ecus = ecus, vehicle = vehicle, signals = signals,
        )
    }

    /**
     * 2023 Citroën C4 whose gateway drops every unauthenticated brand request: only standard OBD-II answers
     * (7E0), every PSA unit stays silent.
     */
    fun citroenGatewayBlocked(adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR): SimScenario {
        val vin = "VR7BCYHZRPJ123456"
        val vehicle = SimVehicleState(fuelLevelPercent = 40.0, ambientC = 12.0, odometerKm = 18_300)
        val signals = LiveSignals(vehicle, diesel = false)
        return SimScenario(
            name = "Citroën C4 (2023), secure gateway",
            description = "PSA architecture on CAN 11-bit: OBD-II answers, every brand unit is silent behind the gateway",
            protocol = BusProtocol.CAN_11_500, adapter = adapter,
            ecus = listOf(obdEngine(0x7E0, 0x7E8, signals, vin, stored = emptyList(), calibration = "9841112280 02")),
            vehicle = vehicle, signals = signals,
        )
    }

    /**
     * 2019 Fiat 500X 1.6 MultiJet, FCA architecture on CAN 29-bit 500k. With [secureGateway] the body
     * computer refuses its fault memory, its software version and its measurement with 0x33, as a unit behind the FCA Secure Gateway Module may.
     */
    fun fiat500x(adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR, secureGateway: Boolean = false): SimScenario {
        val vin = "ZFA3340000P712345"
        val vehicle = SimVehicleState(fuelLevelPercent = 48.0, ambientC = 19.0, odometerKm = 88_410)
        val signals = LiveSignals(vehicle, diesel = true)
        val engine = ComposedEcu(
            "Engine", 0x18DA10F1, 0x18DAF110,
            ObdEngineEcu(
                signals, FuelType.DIESEL, vin = vin, storedDtcs = listOf("P2002"), calibrationIds = listOf("55275561 C3"),
                cvns = listOf("7A1C33E0"), ecuName = "ECM" to "EngineControl",
            ),
            UdsEcu(
                dids = ids("55275561", "MARELLI", "MJD8F3", "MJD 8F3", vin),
                extraDids = mapOf(
                    0x1000 to { (signals.rpm * 4).u16c() }, // rpm, raw / 4 (extended session)
                    0x1003 to { ((signals.coolantC + 40) * 50).u16c() }, // coolant, raw * 2 / 100 - 40 °C
                    0x1955 to { (signals.moduleVolts * 2000).u16c() }, // battery voltage, raw / 2000 (extended session)
                ),
                extendedSessionDids = setOf(0x1000, 0x1003, 0x1955),
                dtcs = listOf(SimUdsDtc("P2002", 0x00, 0x2F)),
            ),
        )
        val transmission = ComposedEcu(
            "Transmission", 0x18DA18F1, 0x18DAF118,
            UdsEcu(dids = ids("55270998", "AISIN", "AWF8F35", "AT9", vin) + mapOf(0x1D12 to bytesOf(4))),
        )
        val bcm = ComposedEcu(
            "Body computer", 0x18DA40F1, 0x18DAF140,
            UdsEcu(
                dids = ids("52096154", "CONTI", "BCM-SCCS", "BCM", vin),
                extraDids = mapOf(0x1004 to { bytesOf((signals.moduleVolts * 10).roundToInt().coerceIn(0, 255)) }),
                refusedDids = if (secureGateway) setOf(0x1004, 0xF195) else emptySet(),
                refuseDtcReads = secureGateway,
                dtcs = listOf(SimUdsDtc("B1004", 0x16, 0x28)),
            ),
        )
        return SimScenario(
            name = if (secureGateway) "Fiat 500X 1.6 MultiJet (2019), secure gateway" else "Fiat 500X 1.6 MultiJet (2019)",
            description = "FCA architecture on CAN 29-bit 500k: engine, transmission and body computer answer",
            protocol = BusProtocol.CAN_29_500, adapter = adapter, ecus = listOf(engine, transmission, bcm),
            vehicle = vehicle, signals = signals,
        )
    }

    // --- builders ----------------------------------------------------------------------------------

    private fun obdEngine(request: Int, response: Int, signals: SignalSource, vin: String, stored: List<String>, calibration: String): SimEcu =
        ComposedEcu(
            "Engine (OBD)", request, response,
            ObdEngineEcu(signals, FuelType.PETROL, vin = vin, storedDtcs = stored, calibrationIds = listOf(calibration), cvns = listOf("5E0A9C14"), ecuName = "ECM" to "EngineControl"),
        )

    /** A PSA unit: reply id as in the OBDb Peugeot tables (0x6xx: request - 0x20, 0x7xx: request - 0x100). */
    private fun psaUnit(name: String, request: Int, ids: Map<Int, ByteArray>, live: Map<Int, () -> ByteArray>, dtcs: List<SimUdsDtc> = emptyList()): SimEcu =
        ComposedEcu(name, request, if (request >= 0x700) request - 0x100 else request - 0x20, UdsEcu(dids = ids, extraDids = live, dtcs = dtcs))

    /** ISO 14229-1 identification DIDs read by the Stellantis module. */
    private fun ids(partNumber: String, supplier: String, software: String, system: String, vin: String): Map<Int, ByteArray> = mapOf(
        0xF187 to partNumber.encodeToByteArray(),
        0xF18A to supplier.encodeToByteArray(),
        0xF195 to software.encodeToByteArray(),
        0xF197 to system.encodeToByteArray(),
        0xF18C to "SN${partNumber.takeLast(6)}".encodeToByteArray(),
        0xF190 to vin.encodeToByteArray(),
    )

    private fun Double.u16c(): ByteArray = roundToInt().coerceIn(0, 0xFFFF).u16()
    private fun Long.u32(): ByteArray = bytesOf(((this shr 24) and 0xFF).toInt(), ((this shr 16) and 0xFF).toInt(), ((this shr 8) and 0xFF).toInt(), (this and 0xFF).toInt())
    private fun Int.u24(): ByteArray = bytesOf((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)
}
