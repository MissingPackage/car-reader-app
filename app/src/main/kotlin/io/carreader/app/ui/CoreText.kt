package io.carreader.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.carreader.app.R
import io.carreader.core.guard.MutationKind

/**
 * Localizes the English text the core produces for the guard (check names and refusal reasons). Unknown text
 * is shown as is: the core may add checks later.
 */
object CoreText {
    private val moving = Regex("""Vehicle is moving \((\d+) km/h\)""")
    private val lowBattery = Regex("""Battery voltage too low \(([\d.]+) V\)""")
    private val engineRunning = Regex("""Engine running \((\d+) rpm\).*""")
    private val batteryCheck = Regex("""Battery voltage ≥ ([\d.]+) V""")

    @Composable
    fun checkName(name: String): String = when {
        name == "Vehicle stationary" -> stringResource(R.string.check_stationary)
        name == "Engine off" -> stringResource(R.string.check_engine_off)
        name == "Adapter suitable" -> stringResource(R.string.check_adapter)
        batteryCheck.matches(name) -> stringResource(R.string.check_battery, batteryCheck.find(name)!!.groupValues[1])
        else -> name
    }

    @Composable
    fun reason(text: String): String {
        moving.matchEntire(text)?.let { return stringResource(R.string.reason_moving, it.groupValues[1]) }
        lowBattery.matchEntire(text)?.let { return stringResource(R.string.reason_low_battery, it.groupValues[1]) }
        engineRunning.matchEntire(text)?.let { return stringResource(R.string.reason_engine_running, it.groupValues[1]) }
        return when {
            text.startsWith("Operations that modify the vehicle are disabled") -> stringResource(R.string.reason_disabled)
            text == "Cancelled by user" -> stringResource(R.string.reason_cancelled)
            text.startsWith("Vehicle speed unavailable") -> stringResource(R.string.reason_speed_unknown)
            text == "Battery voltage unavailable" -> stringResource(R.string.reason_battery_unknown)
            text.startsWith("Engine speed unavailable: make sure") -> stringResource(R.string.reason_rpm_unknown_warn)
            text.startsWith("Engine speed unavailable") -> stringResource(R.string.reason_rpm_unknown)
            text == "Adapter not initialized" -> stringResource(R.string.reason_adapter_missing)
            else -> text
        }
    }

    @Composable
    fun kind(kind: MutationKind): String = when (kind) {
        MutationKind.CLEAR_EMISSION_DTC -> stringResource(R.string.kind_clear_emission)
        MutationKind.CLEAR_ECU_DTC -> stringResource(R.string.kind_clear_ecu)
        MutationKind.WRITE_DATA -> stringResource(R.string.kind_write)
        MutationKind.ROUTINE -> stringResource(R.string.kind_routine)
    }
}
