package io.carreader.sim

import io.carreader.core.link.BusProtocol
import kotlin.math.roundToInt

/** A DID computed from the live engine values at each read. */
typealias LiveDid = (SignalSource) -> ByteArray

/**
 * Ready-made cars. Every builder returns a fresh, independent scenario (own state, own DTC lists).
 *
 * `extraDids` adds or overrides UDS DIDs per ECU request id (e.g. `mapOf(0x7E0 to mapOf(0xF40C to {...}))`),
 * so brand modules can test their own measurements without changing the defaults.
 *
 * Part numbers, software versions and codings are plausible VW-group-style values made up for the
 * simulator, not copied from a real car. The live VAG DIDs follow the OBDb signal sets bundled in
 * brands/vag (scaling noted per DID).
 */
object Scenarios {

    /** 2016 VW Passat B8 2.0 TDI, ISO 15765-4 CAN 11-bit 500k, MQB address table. */
    fun passatB8(
        adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR,
        extraDids: Map<Int, Map<Int, LiveDid>> = emptyMap(),
        /** km taken off the instrument cluster only, as a tampered car would show; the other units keep the truth. */
        clusterRollbackKm: Int = 0,
    ): SimScenario {
        val vin = "WVWZZZ3CZGE512345"
        val vehicle = SimVehicleState()
        val counters = PassatCounters(vehicle, clusterRollbackKm)
        val signals = LiveSignals(vehicle, diesel = true)
        val engineDids = mapOf<Int, LiveDid>(
            0xF40C to { s -> (s.rpm * 4).u16c() }, // rpm mirror of OBD PID 0C, same scaling
            0x105A to { s -> (s.moduleVolts * 50).u16c() }, // battery voltage, raw / 50 V
            0x1057 to { s -> ((s.baroKpa / 100.0 + s.boostBar) * 1000).u16c() }, // boost pressure, raw / 1000 bar
            0x10F9 to { s -> ((s.dpfOutletC + 273.1) * 10).u16c() }, // DPF outlet temperature, raw / 10 - 273.1 °C
            0x1044 to { s -> ((s.dpfOutletC + 25 + 273.1) * 10).u16c() }, // DPF surface temperature (extended session)
        )
        val gatewayDids = mapOf<Int, LiveDid>(
            0x2A0C to { _ -> bytesOf(78) }, // battery state of charge, %
            0x2A0B to { s -> bytesOf((s.ambientC + 40 + 6).roundToInt().coerceIn(0, 255)) }, // battery temperature, raw - 40 °C
            0x2A07 to { s -> ((vehicle.batteryVolts() - 4.0) * 1000).u16c() }, // battery voltage, raw / 1000 + 4 V
        )
        val ecus = listOf(
            vagEngine(
                signals, vin, FuelType.DIESEL,
                stored = listOf("P0401"), pending = emptyList(), permanent = listOf("P0401"),
                calibrationIds = listOf("04L906021DT 6301"), cvns = listOf("9A3F5C21"),
                ids = vagIds("04L906021DT", "6301", "04L907309N", "H13", "R4 2.0l TDI", "VWZ6Z0G1234567", vin, "0114000032240800EF00"),
                udsDtcs = listOf(SimUdsDtc("P0401", 0x00, 0xAF)),
                live = engineDids + counters.engine + (extraDids[0x7E0] ?: emptyMap()),
                extendedOnly = setOf(0x1044),
                odometerKm = { vehicle.odometerKm.toDouble() },
                engineRunTimeS = { counters.engineHours * 3600.0 },
            ),
            vagTransmission(signals, "0D9300040J", "DQ250-6F", vin, calibrationId = "0D9300040J  4527", live = counters.transmission),
            vagUds("Brakes (ABS/ESC)", 0x713, 0x77D, vagIds("3Q0907379AB", "0261", "3Q0907379AB", "H03", "ESC MK100", "00000L4E3A1234", vin, "1A2B0000C0"), signals, extraDids, live = counters.brakes),
            vagUds("Air conditioning", 0x746, 0x7B0, vagIds("3G0907044AE", "0405", "3G0907044AE", "H07", "Climatronic", "0123456789A123", vin, "000A0C00"), signals, extraDids),
            vagUds("Central electronics", 0x70E, 0x778, vagIds("5Q0937084CE", "0352", "5Q0937084CE", "H25", "BCM MQB", "0000000P801234", vin, "4A0E8E00F4A1"), signals, extraDids),
            vagUds(
                "Airbag", 0x715, 0x77F, vagIds("5Q0959655BJ", "0712", "5Q0959655BJ", "H22", "AirbagVW20", "003F0V0Y4A1234", vin, "12003655"), signals, extraDids,
                dtcs = listOf(SimUdsDtc("B1001", 0x1B, 0x2F)),
                pendingCount = 1, // airbag modules often answer 0x78 before the DTC list
            ),
            vagUds(
                "Instruments", 0x714, 0x77E, vagIds("3G0920751B", "0411", "3G0920751B", "H11", "KOMBI", "VWZ7Z0F4123456", vin, "1D0E0000"), signals, extraDids,
                live = counters.instruments,
                extendedOnly = setOf(0x2203),
            ),
            vagUds(
                "Gateway", 0x710, 0x77A, vagIds("5Q0907530AA", "1601", "5Q0907530AA", "H31", "GW MQB High", "1504000000A123", vin, "030103000400"), signals, extraDids,
                dtcs = listOf(SimUdsDtc("U0121", 0x00, 0x28)),
                live = gatewayDids,
            ),
            vagUds("Steering assist", 0x712, 0x77C, vagIds("5Q0909144R", "3015", "5Q0909144R", "H06", "EPS_MQB_ZFLS", "000000003Q1234", vin, "0100"), signals, extraDids),
            vagUds("Information electronics", 0x773, 0x7DD, vagIds("3G0035020", "0338", "3G0035020", "H40", "MIB STD2 NAV", "VWZ4Z7G0012345", vin, "0D00042F40"), signals, extraDids),
            vagUds("Park assist", 0x70A, 0x774, vagIds("5Q0919294E", "1510", "5Q0919294E", "H05", "Parking Aid", "18150000A01234", vin, "02"), signals, extraDids),
        )
        return SimScenario(
            name = if (clusterRollbackKm > 0) "VW Passat B8 2.0 TDI (2016), odometer rolled back" else "VW Passat B8 2.0 TDI (2016)",
            description = "MQB diesel on CAN 11-bit 500k with 11 ECUs: engine EGR fault, gateway lost ABS communication, airbag fault" +
                if (clusterRollbackKm > 0) "; instrument cluster shows $clusterRollbackKm km less than the other units" else "",
            protocol = BusProtocol.CAN_11_500,
            adapter = adapter,
            ecus = ecus,
            vehicle = vehicle,
            canPadding = 0xAA,
            signals = signals,
        )
    }

    /** 2018 Audi Q5 (FY) 2.0 TDI, ISO 15765-4 CAN 11-bit 500k, MLB-evo address table. */
    fun audiQ5(
        adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR,
        extraDids: Map<Int, Map<Int, LiveDid>> = emptyMap(),
    ): SimScenario {
        val vin = "WAUZZZFYZJ2045678"
        val vehicle = SimVehicleState(fuelLevelPercent = 41.0, ambientC = 9.0, odometerKm = 54_210)
        val signals = LiveSignals(vehicle, diesel = true)
        val odo = { vehicle.odometerKm.toLong() }
        val engineDids = mapOf<Int, LiveDid>(
            0xF40C to { s -> (s.rpm * 4).u16c() },
            0x11BE to { s -> ((s.oilC + 273.1) * 10).u16c() }, // engine oil temperature, raw / 10 - 273.1 °C
            0x1135 to { s -> (s.coolantC * 10).u16c() }, // coolant at radiator outlet, raw / 10 °C
            0x1116 to { s -> (if (s.coolantC > 85) 3500.0 else 0.0).u16c() }, // fan 1 duty, raw / 100 %
        )
        val gatewayDids = mapOf<Int, LiveDid>(
            0x2A0C to { _ -> bytesOf(83) }, // start/stop battery state of charge, %
            0x2A0A to { s -> bytesOf((s.ambientC + 40 + 4).roundToInt().coerceIn(0, 255)) }, // battery sensor temperature
        )
        val ecus = listOf(
            vagEngine(
                signals, vin, FuelType.DIESEL,
                stored = listOf("P2463"), pending = listOf("P0299"), permanent = listOf("P2463"),
                calibrationIds = listOf("04L906056KN 0007"), cvns = listOf("5E61B0C4"),
                ids = vagIds("04L906056KN", "0007", "04L907309R", "H05", "R4 2.0l TDI", "AUZ7Z0J3456789", vin, "011A0012342F0800EF00"),
                udsDtcs = listOf(SimUdsDtc("P2463", 0x00, 0xAF), SimUdsDtc("P0299", 0x00, 0x24)),
                live = engineDids + mapOf<Int, LiveDid>(
                    0x295A to { _ -> u24(odo()) }, // odometer, km (OBDb Q5_ODO)
                    0x1160 to { _ -> u32(odo() * 1000) }, // odometer, raw / 1000 km (Q5_ODO_HD)
                    0x5149 to { _ -> u24(31_870) }, // chain elongation, mileage at last adaptation, km
                ) + (extraDids[0x7E0] ?: emptyMap()),
                extendedOnly = emptySet(),
            ),
            vagTransmission(
                signals, "0CK927156M", "DL382-7F", vin, calibrationId = "0CK927156M  2211",
                live = mapOf(
                    0x0302 to { _ -> u16(1_204 * 4) }, // total drive time, raw / 4 h (Q5_DRIVE_TIME)
                    0x10CA to { _ -> u16(4_980).reversedArray() }, // clutch 1 kiss point adaptation, little endian, raw * 10 km
                ),
            ),
            vagUds(
                "Brakes (ABS/ESC)", 0x713, 0x77D, vagIds("80A907379D", "0110", "80A907379D", "H04", "ESP 9.3", "00000M2E1B5678", vin, "2A3D00C1"), signals, extraDids,
                dtcs = listOf(SimUdsDtc("C0035", 0x29, 0x28)),
            ),
            vagUds("Air conditioning", 0x746, 0x7B0, vagIds("80A820043", "0230", "80A820043", "H09", "Climatronic", "AUZ8Z0H1234567", vin, "000B0D00"), signals, extraDids),
            vagUds("Central electronics", 0x70E, 0x778, vagIds("4M0907063CJ", "0210", "4M0907063CJ", "H18", "BCM1 MLBevo", "0000000Q512345", vin, "5B0F8F00A4C1"), signals, extraDids),
            vagUds("Adaptive cruise control", 0x757, 0x7C1, vagIds("4M0907561", "0240", "4M0907561", "H02", "ACC Bosch", "B00000X4567890", vin, "0300"), signals, extraDids),
            vagUds("Airbag", 0x715, 0x77F, vagIds("80A959655B", "0450", "80A959655B", "H12", "AirbagAU10", "004F1X0Y5B5678", vin, "13004655"), signals, extraDids),
            vagUds(
                "Instruments", 0x714, 0x77E, vagIds("80A920790A", "0352", "80A920790A", "H21", "KOMBI", "AUZ7Z0K5678901", vin, "1E0F0000"), signals, extraDids,
                live = mapOf(0x22B8 to { _ -> u32(odo() * 1000 + 450) }), // odometer, raw / 1000 km (Q5_ODO_MILEAGE_HR)
            ),
            vagUds("Gateway", 0x710, 0x77A, vagIds("4M0907468G", "0930", "4M0907468G", "H35", "GW MLBevo", "1704000000B567", vin, "040203000500"), signals, extraDids, live = gatewayDids),
            vagUds("Steering assist", 0x712, 0x77C, vagIds("80A909144D", "2011", "80A909144D", "H07", "EPS_MLB", "000000008W5678", vin, "0110"), signals, extraDids),
            vagUds(
                "Information electronics", 0x773, 0x7DD, vagIds("8W0035021B", "0631", "8W0035021B", "H50", "MMI MIB2+ HIGH", "AUZ1Z7H0056789", vin, "0E00052F40"), signals, extraDids,
                dtcs = listOf(SimUdsDtc("U0184", 0x00, 0x28)),
            ),
            vagUds("Park assist", 0x70A, 0x774, vagIds("80A919294A", "0130", "80A919294A", "H06", "Parking Aid", "19160000B05678", vin, "03"), signals, extraDids),
        )
        return SimScenario(
            name = "Audi Q5 FY 2.0 TDI (2018)",
            description = "MLB-evo diesel on CAN 11-bit 500k with 12 ECUs: DPF soot fault, pending underboost, ABS wheel sensor, MMI lost radio",
            protocol = BusProtocol.CAN_11_500,
            adapter = adapter,
            ecus = ecus,
            vehicle = vehicle,
            canPadding = 0xAA,
            signals = signals,
        )
    }

    /** 2015 Fiat 500 1.2 petrol on CAN 11-bit 500k, standard OBD-II only (no UDS): physical UDS gets 7F xx 11. */
    fun genericPetrol(adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR): SimScenario = fiat(adapter, "Fiat 500 1.2 (2015)")

    /** The [genericPetrol] car behind a cheap ELM327 "v1.5" clone ([SimAdapterProfile.CLONE_V15]). */
    fun cloneAdapter(): SimScenario = fiat(SimAdapterProfile.CLONE_V15, "Fiat 500 1.2 (2015), clone ELM327 v1.5")

    /** 2003 Opel Astra G 1.6 petrol on ISO 9141-2 (K-line): OBD-II only, VIN in five messages, 7-byte DTC messages. */
    fun kLineLegacy(adapter: SimAdapterProfile = SimAdapterProfile.SIMULATOR): SimScenario {
        val vehicle = SimVehicleState(fuelLevelPercent = 23.0, ambientC = 18.0, odometerKm = 214_880)
        val signals = LiveSignals(vehicle, diesel = false)
        val engine = ComposedEcu(
            name = "Engine",
            requestId = 0x7E0,
            responseId = 0x7E8,
            behaviors = listOf(
                ObdEngineEcu(
                    signals, FuelType.PETROL,
                    supportedPids = ObdEngineEcu.LEGACY_PETROL_PIDS,
                    vin = "W0L0TGF4835012345",
                    storedDtcs = listOf("P0130", "P0300", "P0171", "P0420"),
                    calibrationIds = listOf("09391283DA"),
                    cvns = listOf("00A1B2C3"),
                    obdStandard = 0x06,
                    incompleteMonitors = 0x04, // evaporative system not complete
                    style = ObdBusStyle.SERIAL,
                ),
            ),
            answersObdFunctional = true,
            serialAddress = 0x10,
        )
        return SimScenario(
            name = "Opel Astra G 1.6 (2003)",
            description = "Petrol on ISO 9141-2 K-line, OBD-II only: four stored DTCs, lean mixture",
            protocol = BusProtocol.ISO_9141_2,
            adapter = adapter,
            ecus = listOf(engine),
            vehicle = vehicle,
            signals = signals,
        )
    }

    // --- builders ----------------------------------------------------------------------------------

    private fun fiat(adapter: SimAdapterProfile, name: String): SimScenario {
        val vehicle = SimVehicleState(fuelLevelPercent = 55.0, ambientC = 21.0, odometerKm = 63_120)
        val signals = LiveSignals(vehicle, diesel = false)
        val engine = ComposedEcu(
            "Engine", 0x7E0, 0x7E8,
            ObdEngineEcu(
                signals, FuelType.PETROL,
                vin = "ZFA3120000J123456",
                storedDtcs = listOf("P0420"),
                pendingDtcs = listOf("P0300"),
                calibrationIds = listOf("55266433 03"),
                cvns = listOf("3C9D0E12"),
                ecuName = "ECM" to "EngineControl",
            ),
        )
        return SimScenario(
            name = name,
            description = "Petrol on CAN 11-bit 500k, OBD-II only: catalyst efficiency fault, pending misfire",
            protocol = BusProtocol.CAN_11_500,
            adapter = adapter,
            ecus = listOf(engine),
            vehicle = vehicle,
            signals = signals,
        )
    }

    private fun vagEngine(
        signals: SignalSource,
        vin: String,
        fuel: FuelType,
        stored: List<String>,
        pending: List<String>,
        permanent: List<String>,
        calibrationIds: List<String>,
        cvns: List<String>,
        ids: Map<Int, ByteArray>,
        udsDtcs: List<SimUdsDtc>,
        live: Map<Int, LiveDid>,
        extendedOnly: Set<Int>,
        odometerKm: (() -> Double)? = null,
        engineRunTimeS: (() -> Double)? = null,
    ): SimEcu = ComposedEcu(
        "Engine", 0x7E0, 0x7E8,
        ObdEngineEcu(
            signals, fuel, vin = vin,
            storedDtcs = stored, pendingDtcs = pending, permanentDtcs = permanent,
            calibrationIds = calibrationIds, cvns = cvns, ecuName = "ECM" to "EngineControl",
            incompleteMonitors = 0x40, // particulate filter monitor not complete
            odometerKm = odometerKm,
            engineRunTimeS = engineRunTimeS,
        ),
        UdsEcu(dids = ids, extraDids = live.mapValues { (_, f) -> { f(signals) } }, extendedSessionDids = extendedOnly, dtcs = udsDtcs),
    )

    private fun vagTransmission(
        signals: SignalSource,
        partNumber: String,
        system: String,
        vin: String,
        calibrationId: String,
        live: Map<Int, LiveDid> = emptyMap(),
    ): SimEcu =
        ComposedEcu(
            "Transmission", 0x7E1, 0x7E9,
            ObdEngineEcu(
                signals, FuelType.DIESEL,
                supportedPids = setOf(0x0D),
                calibrationIds = listOf(calibrationId),
                cvns = listOf("1F2E3D4C"),
                ecuName = "TCM" to "TransmissionCtl",
                freezeFrame = null,
            ),
            UdsEcu(
                dids = vagIds(partNumber, "4527", partNumber, "H09", system, "00001234567890", vin, "0018"),
                extraDids = live.mapValues { (_, f) -> { f(signals) } },
            ),
        )

    private fun vagUds(
        name: String,
        requestId: Int,
        responseId: Int,
        ids: Map<Int, ByteArray>,
        signals: SignalSource,
        extraDids: Map<Int, Map<Int, LiveDid>>,
        dtcs: List<SimUdsDtc> = emptyList(),
        live: Map<Int, LiveDid> = emptyMap(),
        pendingCount: Int = 0,
        extendedOnly: Set<Int> = emptySet(),
    ): SimEcu {
        val all = live + (extraDids[requestId] ?: emptyMap())
        return ComposedEcu(
            name, requestId, responseId,
            UdsEcu(
                dids = ids, extraDids = all.mapValues { (_, f) -> { f(signals) } }, extendedSessionDids = extendedOnly,
                dtcs = dtcs, pendingCount = pendingCount,
            ),
        )
    }

    /** The identification DIDs read on scan (docs/CONTRACTS.md), ASCII except 0600 coding (hex bytes). */
    private fun vagIds(
        partNumber: String,
        software: String,
        hardware: String,
        hardwareVersion: String,
        system: String,
        serial: String,
        vin: String,
        codingHex: String,
    ): Map<Int, ByteArray> = mapOf(
        0xF187 to partNumber.padEnd(11).encodeToByteArray(),
        0xF189 to software.encodeToByteArray(),
        0xF191 to hardware.padEnd(11).encodeToByteArray(),
        0xF1A3 to hardwareVersion.encodeToByteArray(),
        0xF197 to system.padEnd(13).encodeToByteArray(),
        0xF18C to serial.encodeToByteArray(),
        0xF190 to vin.encodeToByteArray(),
        0x0600 to ByteArray(codingHex.length / 2) { codingHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
    )

    /**
     * Mileage counters of the simulated Passat, laid out as the OBDb Volkswagen-Passat rows read them. The cluster
     * shows the true odometer minus [rollbackKm]; every other unit keeps the true value, as after a cluster-only
     * tamper. Stamps and hours are fixed, plausible values for a car of this mileage.
     */
    private class PassatCounters(private val vehicle: SimVehicleState, private val rollbackKm: Int) {
        val engineHours = 2_310.0
        private val odo get() = vehicle.odometerKm.toLong()
        private val cluster get() = (odo - rollbackKm).coerceAtLeast(0)

        val instruments: Map<Int, LiveDid> = mapOf(
            0x22B8 to { _ -> u32(cluster * 1000 + 380) }, // PASSAT_ODO_MILEAGE_HR, raw / 1000 km
            0x2203 to { _ -> u24(cluster) }, // PASSAT_ODO_DASH, km, extended session
        )
        val engine: Map<Int, LiveDid> = mapOf(
            0x295A to { _ -> u24(odo - 3) }, // PASSAT_ODO, km (updated less often than the cluster)
            0x1160 to { _ -> u32(odo * 1000 + 120) }, // PASSAT_ODO_HD, raw / 1000 km
            0x5149 to { _ -> u24(61_240) }, // PASSAT_CHAIN_ELONGATION, mileage at last adaptation, km
            0x516C to { _ -> u24(61_240) }, // PASSAT_CHAIN_ELONGATION_INLET
        )
        val transmission: Map<Int, LiveDid> = mapOf(
            0x3841 to { _ -> u32(odo * 10) }, // PASSAT_ODO_V1, raw / 10 km
            0x3887 to { _ -> u32(odo * 10).reversedArray() }, // PASSAT_MQB_TCM_ODO_2, little endian, raw / 10 km
            0x10CA to { _ -> u16(7_135).reversedArray() }, // clutch 1 kiss point adaptation, little endian, raw * 10 km
            0x10EB to { _ -> u16(8_302).reversedArray() }, // clutch 2 kiss point adaptation, little endian, raw * 10 km
            0x0302 to { _ -> u16(2_050 * 4) }, // PASSAT_DRIVE_TIME, raw / 4 h
        )
        val brakes: Map<Int, LiveDid> = mapOf(
            0x180E to { _ -> bytesOf(0) + u24(odo - 210) }, // brake disk drying, last activation (bit 8, 24 bits, km)
            0x1821 to { _ -> // TPMS: 8 stamps of 32 bits from bit 8: adaptations (newest first), then warnings
                listOf(odo - 1_900, 66_020, 41_500, 18_230, odo - 5_400, 52_880, 0, 0)
                    .fold(bytesOf(0)) { acc, km -> acc + u32(km) }
            },
            0x18B6 to { _ -> bytesOf(0) + u24(0xFFFFFF) }, // counter steer support never activated
        )
    }

    private fun Double.u16c(): ByteArray = roundToInt().coerceIn(0, 0xFFFF).u16()

    private fun u16(v: Int): ByteArray = v.coerceIn(0, 0xFFFF).u16()
    private fun u24(v: Long): ByteArray {
        val raw = v.coerceIn(0L, 0xFFFFFFL)
        return ByteArray(3) { ((raw shr (16 - 8 * it)) and 0xFF).toByte() }
    }
    private fun u24(v: Int): ByteArray = u24(v.toLong())
    private fun u32(v: Long): ByteArray {
        val raw = v.coerceIn(0L, 0xFFFF_FFFFL)
        return ByteArray(4) { ((raw shr (24 - 8 * it)) and 0xFF).toByte() }
    }

    private fun SimVehicleState.batteryVolts(): Double =
        if (ignitionOn && engineRunning) restingBatteryVolts + 1.6 else restingBatteryVolts
}
