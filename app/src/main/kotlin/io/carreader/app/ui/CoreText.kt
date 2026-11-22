package io.carreader.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.carreader.app.R
import io.carreader.brands.vag.VagBrandModule
import io.carreader.core.guard.MutationKind

/**
 * Localizes the English text the core and brand modules produce for the UI (guard checks, refusal reasons,
 * confirmation texts, scan notes, readiness monitors). Unknown text is shown as is: the core may add more later.
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

    /** Target label of a mutation request, as shown in the confirmation dialog. */
    @Composable
    fun target(label: String): String = when (label) {
        "All emission ECUs" -> stringResource(R.string.confirm_target_all_emission)
        else -> label
    }

    /** Description of a mutation request, as shown in the confirmation dialog. */
    @Composable
    fun description(text: String): String = when (text) {
        "Clear stored, pending fault codes and freeze frames; turns off the MIL; resets readiness monitors" ->
            stringResource(R.string.confirm_desc_clear_emission)
        "Clear the fault memory of this control module" -> stringResource(R.string.confirm_desc_clear_ecu)
        else -> text
    }

    /** Notes a brand module adds to a finished module scan. */
    @Composable
    fun brandNote(text: String): String = when (text) {
        VagBrandModule.TP20_NOTE -> stringResource(R.string.note_vag_tp20)
        VagBrandModule.KLINE_NOTE -> stringResource(R.string.note_vag_kline)
        else -> text
    }

    /** Readiness monitor names (SAE J1979 service 01 PID 01, as named by the core's Readiness decoder). */
    @Composable
    fun monitor(name: String): String = when (name) {
        "Misfire" -> stringResource(R.string.monitor_misfire)
        "Fuel system" -> stringResource(R.string.monitor_fuel_system)
        "Comprehensive components" -> stringResource(R.string.monitor_components)
        "Catalyst" -> stringResource(R.string.monitor_catalyst)
        "Heated catalyst" -> stringResource(R.string.monitor_heated_catalyst)
        "Evaporative system" -> stringResource(R.string.monitor_evap)
        "Secondary air system" -> stringResource(R.string.monitor_secondary_air)
        "Gasoline particulate filter" -> stringResource(R.string.monitor_gpf)
        "Oxygen sensor" -> stringResource(R.string.monitor_o2_sensor)
        "Oxygen sensor heater" -> stringResource(R.string.monitor_o2_heater)
        "EGR / VVT system" -> stringResource(R.string.monitor_egr_vvt)
        "NMHC catalyst" -> stringResource(R.string.monitor_nmhc_catalyst)
        "NOx / SCR aftertreatment" -> stringResource(R.string.monitor_nox_scr)
        "Boost pressure" -> stringResource(R.string.monitor_boost)
        "Exhaust gas sensor" -> stringResource(R.string.monitor_exhaust_sensor)
        "Particulate filter" -> stringResource(R.string.monitor_pm_filter)
        else -> name
    }
}
