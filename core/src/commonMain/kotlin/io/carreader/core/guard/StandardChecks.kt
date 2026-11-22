package io.carreader.core.guard

/** Check 1: the vehicle must be stationary (Reg. 2018/858 Annex X 2.9: read-only while driving). */
class VehicleStationaryCheck : SafetyCheck {
    override val name = "Vehicle stationary"
    override suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult {
        val speed = context.vehicleSpeedKmh() ?: return CheckResult.Fail("Vehicle speed unavailable: cannot confirm the car is stopped")
        return if (speed == 0) CheckResult.Pass else CheckResult.Fail("Vehicle is moving ($speed km/h)")
    }
}

/** Check 2: battery voltage above a floor, so the ECU does not brown out mid-operation. */
class BatteryVoltageCheck(private val minimumVolts: Double = 12.0) : SafetyCheck {
    override val name = "Battery voltage ≥ $minimumVolts V"
    override suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult {
        val v = context.batteryVoltage() ?: return CheckResult.Fail("Battery voltage unavailable")
        return if (v >= minimumVolts) CheckResult.Pass else CheckResult.Fail("Battery voltage too low (${formatVolts(v)} V)")
    }
}

/**
 * Check 3: adapter class. Clearing DTCs is allowed on any adapter; writes and routines need a certified one
 * (clones drop long sessions, the main cause of bricked ECUs).
 */
class AdapterCapabilityCheck : SafetyCheck {
    override val name = "Adapter suitable"
    override suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult {
        val adapter = context.adapter ?: return CheckResult.Fail("Adapter not initialized")
        val needsCertified = request.kind == MutationKind.WRITE_DATA || request.kind == MutationKind.ROUTINE
        return if (!needsCertified || adapter.certified) CheckResult.Pass
        else CheckResult.Fail("This operation requires a certified adapter (OBDLink, vLinker); found ${adapter.identity}")
    }
}

/**
 * Engine off, ignition on. A running engine always blocks. No RPM answer is ambiguous (engine off, lost frame,
 * PID unsupported): it blocks writes and routines, and only warns for DTC clears.
 */
class EngineOffCheck : SafetyCheck {
    override val name = "Engine off"
    override suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult {
        val rpm = context.engineRpm()
        val clear = request.kind == MutationKind.CLEAR_EMISSION_DTC || request.kind == MutationKind.CLEAR_ECU_DTC
        return when {
            rpm == null && clear -> CheckResult.Warn("Engine speed unavailable: make sure the engine is off")
            rpm == null -> CheckResult.Fail("Engine speed unavailable: cannot confirm the engine is off")
            rpm == 0 -> CheckResult.Pass
            else -> CheckResult.Fail("Engine running ($rpm rpm): switch it off, keep ignition on")
        }
    }
}

internal fun formatVolts(v: Double): String = (kotlin.math.round(v * 10) / 10.0).toString()

object StandardChecks {
    fun all(): List<SafetyCheck> = listOf(VehicleStationaryCheck(), EngineOffCheck(), BatteryVoltageCheck(), AdapterCapabilityCheck())
}
