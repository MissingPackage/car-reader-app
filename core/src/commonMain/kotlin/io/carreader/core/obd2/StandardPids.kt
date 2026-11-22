package io.carreader.core.obd2

/**
 * Standard service 01 PIDs (SAE J1979 / ISO 15031-5). Formulas follow the public "OBD-II PIDs" table:
 * A, B, C, D are the data bytes after the PID echo.
 */
object StandardPids {
    private const val PCT = 100.0 / 255.0
    private const val TRIM = 100.0 / 128.0
    private const val LAMBDA = 2.0 / 65536.0

    val monitorStatus: PidDefinition = MonitorStatusPid

    val fuelSystemStatus: PidDefinition = object : BasePid(0x03, "Fuel system status", "", 2) {
        private val states = mapOf(
            0x00 to "Not present",
            0x01 to "Open loop, insufficient engine temperature",
            0x02 to "Closed loop",
            0x04 to "Open loop, engine load or deceleration",
            0x08 to "Open loop, system failure",
            0x10 to "Closed loop, feedback fault",
        )
        override fun decode(data: ByteArray): LiveValue {
            requireLength(data)
            val a = data[0].toInt() and 0xFF
            val b = data[1].toInt() and 0xFF
            val s1 = states[a] ?: "Unknown ($a)"
            return label(if (b == 0) s1 else "System 1: $s1; system 2: ${states[b] ?: "Unknown ($b)"}")
        }
    }

    val engineLoad = ScaledPid(0x04, "Calculated engine load", "%", 1, PCT, decimals = 1)
    val coolantTemp = ScaledPid(0x05, "Engine coolant temperature", "°C", 1, offset = -40.0)
    val shortTermTrimBank1 = ScaledPid(0x06, "Short term fuel trim, bank 1", "%", 1, TRIM, -100.0, 1)
    val longTermTrimBank1 = ScaledPid(0x07, "Long term fuel trim, bank 1", "%", 1, TRIM, -100.0, 1)
    val shortTermTrimBank2 = ScaledPid(0x08, "Short term fuel trim, bank 2", "%", 1, TRIM, -100.0, 1)
    val longTermTrimBank2 = ScaledPid(0x09, "Long term fuel trim, bank 2", "%", 1, TRIM, -100.0, 1)
    val fuelPressure = ScaledPid(0x0A, "Fuel pressure (gauge)", "kPa", 1, 3.0)
    val intakeManifoldPressure = ScaledPid(0x0B, "Intake manifold absolute pressure", "kPa", 1)
    val engineRpm = ScaledPid(0x0C, "Engine speed", "rpm", 2, 0.25, decimals = 0)
    val vehicleSpeed = ScaledPid(0x0D, "Vehicle speed", "km/h", 1)
    val timingAdvance = ScaledPid(0x0E, "Timing advance", "° before TDC", 1, 0.5, -64.0, 1)
    val intakeAirTemp = ScaledPid(0x0F, "Intake air temperature", "°C", 1, offset = -40.0)
    val mafRate = ScaledPid(0x10, "Mass air flow rate", "g/s", 2, 0.01, decimals = 2)
    val throttlePosition = ScaledPid(0x11, "Throttle position", "%", 1, PCT, decimals = 1)

    val obdStandard: PidDefinition = EnumPid(
        0x1C, "OBD standard",
        mapOf(
            1 to "OBD-II (CARB)",
            2 to "OBD (EPA)",
            3 to "OBD and OBD-II",
            4 to "OBD-I",
            5 to "Not OBD compliant",
            6 to "EOBD (Europe)",
            7 to "EOBD and OBD-II",
            8 to "EOBD and OBD",
            9 to "EOBD, OBD and OBD-II",
            10 to "JOBD (Japan)",
            11 to "JOBD and OBD-II",
            12 to "JOBD and EOBD",
            13 to "JOBD, EOBD and OBD-II",
            17 to "Engine Manufacturer Diagnostics (EMD)",
            18 to "EMD+",
            19 to "Heavy Duty OBD (Child/Partial)",
            20 to "Heavy Duty OBD",
            21 to "World Wide Harmonized OBD",
            23 to "Heavy Duty Euro OBD Stage I without NOx control",
            24 to "Heavy Duty Euro OBD Stage I with NOx control",
            25 to "Heavy Duty Euro OBD Stage II without NOx control",
            26 to "Heavy Duty Euro OBD Stage II with NOx control",
            28 to "Brazil OBD Phase 1",
            29 to "Brazil OBD Phase 2",
            30 to "Korean OBD",
            31 to "India OBD I",
            32 to "India OBD II",
            33 to "Heavy Duty Euro OBD Stage VI",
        ),
    )

    val runTime = ScaledPid(0x1F, "Run time since engine start", "s", 2)
    val distanceWithMil = ScaledPid(0x21, "Distance traveled with MIL on", "km", 2)
    val fuelRailPressureRelative = ScaledPid(0x22, "Fuel rail pressure (relative to manifold vacuum)", "kPa", 2, 0.079, decimals = 1)
    val fuelRailPressure = ScaledPid(0x23, "Fuel rail gauge pressure (diesel, direct injection)", "kPa", 2, 10.0)

    /** PIDs 24-2B: wide-range O2 sensors 1-8, lambda from A/B (C/D carry the sensor voltage). */
    val o2Lambda: List<ScaledPid> = (1..8).map { n ->
        ScaledPid(0x23 + n, "Oxygen sensor $n equivalence ratio (lambda)", "λ", 4, LAMBDA, decimals = 3, width = 2)
    }

    val commandedEgr = ScaledPid(0x2C, "Commanded EGR", "%", 1, PCT, decimals = 1)
    val egrError = ScaledPid(0x2D, "EGR error", "%", 1, TRIM, -100.0, 1)
    val commandedEvapPurge = ScaledPid(0x2E, "Commanded evaporative purge", "%", 1, PCT, decimals = 1)
    val fuelLevel = ScaledPid(0x2F, "Fuel tank level input", "%", 1, PCT, decimals = 1)
    val warmUpsSinceClear = ScaledPid(0x30, "Warm-ups since codes cleared", "", 1)
    val distanceSinceClear = ScaledPid(0x31, "Distance traveled since codes cleared", "km", 2)
    val baroPressure = ScaledPid(0x33, "Absolute barometric pressure", "kPa", 1)
    val catalystTempB1S1 = ScaledPid(0x3C, "Catalyst temperature, bank 1 sensor 1", "°C", 2, 0.1, -40.0, 1)
    val catalystTempB2S1 = ScaledPid(0x3D, "Catalyst temperature, bank 2 sensor 1", "°C", 2, 0.1, -40.0, 1)
    val catalystTempB1S2 = ScaledPid(0x3E, "Catalyst temperature, bank 1 sensor 2", "°C", 2, 0.1, -40.0, 1)
    val catalystTempB2S2 = ScaledPid(0x3F, "Catalyst temperature, bank 2 sensor 2", "°C", 2, 0.1, -40.0, 1)
    val controlModuleVoltage = ScaledPid(0x42, "Control module voltage", "V", 2, 0.001, decimals = 2)
    val absoluteLoad = ScaledPid(0x43, "Absolute load value", "%", 2, PCT, decimals = 1)
    val commandedLambda = ScaledPid(0x44, "Commanded air-fuel equivalence ratio (lambda)", "λ", 2, LAMBDA, decimals = 3)
    val relativeThrottle = ScaledPid(0x45, "Relative throttle position", "%", 1, PCT, decimals = 1)
    val ambientAirTemp = ScaledPid(0x46, "Ambient air temperature", "°C", 1, offset = -40.0)
    val absoluteThrottleB = ScaledPid(0x47, "Absolute throttle position B", "%", 1, PCT, decimals = 1)
    val acceleratorPedalD = ScaledPid(0x49, "Accelerator pedal position D", "%", 1, PCT, decimals = 1)
    val acceleratorPedalE = ScaledPid(0x4A, "Accelerator pedal position E", "%", 1, PCT, decimals = 1)
    val commandedThrottle = ScaledPid(0x4C, "Commanded throttle actuator", "%", 1, PCT, decimals = 1)
    val timeWithMil = ScaledPid(0x4D, "Time run with MIL on", "min", 2)
    val timeSinceClear = ScaledPid(0x4E, "Time since trouble codes cleared", "min", 2)

    val fuelType: PidDefinition = EnumPid(
        0x51, "Fuel type",
        mapOf(
            0 to "Not available",
            1 to "Gasoline",
            2 to "Methanol",
            3 to "Ethanol",
            4 to "Diesel",
            5 to "LPG",
            6 to "CNG",
            7 to "Propane",
            8 to "Electric",
            9 to "Bifuel: gasoline",
            10 to "Bifuel: methanol",
            11 to "Bifuel: ethanol",
            12 to "Bifuel: LPG",
            13 to "Bifuel: CNG",
            14 to "Bifuel: propane",
            15 to "Bifuel: electricity",
            16 to "Bifuel: electric and combustion engine",
            17 to "Hybrid gasoline",
            18 to "Hybrid ethanol",
            19 to "Hybrid diesel",
            20 to "Hybrid electric",
            21 to "Hybrid electric and combustion engine",
            22 to "Hybrid regenerative",
            23 to "Bifuel: diesel",
        ),
    )

    val ethanolPercent = ScaledPid(0x52, "Ethanol fuel percentage", "%", 1, PCT, decimals = 1)
    val oilTemp = ScaledPid(0x5C, "Engine oil temperature", "°C", 1, offset = -40.0)
    val fuelRate = ScaledPid(0x5E, "Engine fuel rate", "L/h", 2, 0.05, decimals = 2)
    val driverDemandTorque = ScaledPid(0x61, "Driver's demand engine torque", "%", 1, offset = -125.0)
    val actualTorque = ScaledPid(0x62, "Actual engine torque", "%", 1, offset = -125.0)
    val referenceTorque = ScaledPid(0x63, "Engine reference torque", "N·m", 2)

    /** PID 7F: byte A flags which totals follow; B-E total engine run time in seconds (idle and PTO totals after). */
    val engineRunTimeTotal = ScaledPid(0x7F, "Total engine run time", "s", 13, width = 4, startByte = 1)

    /** PID A6: odometer, 0.1 km per bit (mandatory in California from model year 2019; optional in the EU). */
    val odometer = ScaledPid(0xA6, "Odometer", "km", 4, 0.1, decimals = 1)

    val all: List<PidDefinition> = listOf(
        monitorStatus, fuelSystemStatus, engineLoad, coolantTemp,
        shortTermTrimBank1, longTermTrimBank1, shortTermTrimBank2, longTermTrimBank2,
        fuelPressure, intakeManifoldPressure, engineRpm, vehicleSpeed, timingAdvance, intakeAirTemp, mafRate,
        throttlePosition, obdStandard, runTime, distanceWithMil, fuelRailPressureRelative, fuelRailPressure,
    ) + o2Lambda + listOf(
        commandedEgr, egrError, commandedEvapPurge, fuelLevel, warmUpsSinceClear, distanceSinceClear, baroPressure,
        catalystTempB1S1, catalystTempB2S1, catalystTempB1S2, catalystTempB2S2,
        controlModuleVoltage, absoluteLoad, commandedLambda, relativeThrottle, ambientAirTemp, absoluteThrottleB,
        acceleratorPedalD, acceleratorPedalE, commandedThrottle, timeWithMil, timeSinceClear,
        fuelType, ethanolPercent, oilTemp, fuelRate, driverDemandTorque, actualTorque, referenceTorque,
        engineRunTimeTotal, odometer,
    )

    private val index: Map<Int, PidDefinition> = all.associateBy { it.pid }

    fun byPid(pid: Int): PidDefinition? = index[pid]
}
