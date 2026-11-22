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
        return if (v >= minimumVolts) CheckResult.Pass else CheckResult.Fail("Battery voltage too low (${"%.1f".formatCompat(v)} V)")
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

/** Engine off is recommended for clearing DTCs; failing it blocks writes/routines, only warns for clears. */
class EngineOffCheck : SafetyCheck {
    override val name = "Engine off"
    override suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult {
        val rpm = context.engineRpm() ?: return CheckResult.Pass // no answer usually means engine off
        return if (rpm == 0) CheckResult.Pass else CheckResult.Fail("Engine running ($rpm rpm): switch it off, keep ignition on")
    }
}

internal fun String.formatCompat(v: Double): String {
    val rounded = kotlin.math.round(v * 10) / 10.0
    return rounded.toString()
}

object StandardChecks {
    fun all(): List<SafetyCheck> = listOf(VehicleStationaryCheck(), EngineOffCheck(), BatteryVoltageCheck(), AdapterCapabilityCheck())
}
