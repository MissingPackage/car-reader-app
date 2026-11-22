package io.carreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.CoreText
import io.carreader.app.ui.Loadable
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.Screen
import io.carreader.app.ui.UiState
import io.carreader.app.ui.theme.LocalStatusColors
import io.carreader.core.link.AdapterChip
import java.util.Locale

@Composable
fun VehicleScreen(state: UiState, vm: MainViewModel) {
    ScreenScaffold(
        title = stringResource(R.string.vehicle_title),
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = vm::loadSummary) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionLostBanner(state, vm)

            Section(title = stringResource(R.string.vehicle_car)) {
                when (val s = state.summary) {
                    Loadable.Idle, Loadable.Loading -> Loading(stringResource(R.string.vehicle_identifying))
                    is Loadable.Failed -> ErrorText(stringResource(R.string.vehicle_identify_failed, CoreText.message(s.message)), onRetry = vm::loadSummary)
                    is Loadable.Ready -> {
                        val summary = s.value
                        val manufacturer = summary.context.manufacturer
                        InfoRow(stringResource(R.string.vehicle_vin), summary.identification.vin ?: stringResource(R.string.vehicle_vin_unknown))
                        InfoRow(
                            stringResource(R.string.vehicle_manufacturer),
                            manufacturer?.let { m -> listOfNotNull(m.brand, m.name.takeIf { it != m.brand }, m.country).joinToString(" · ") }
                                ?: stringResource(R.string.value_unknown),
                        )
                        summary.context.modelYear?.let { InfoRow(stringResource(R.string.vehicle_model_year), it.toString()) }
                        InfoRow(stringResource(R.string.vehicle_brand_module), summary.brand?.displayName ?: stringResource(R.string.vehicle_no_brand_module))
                        InfoRow(
                            stringResource(R.string.vehicle_battery),
                            summary.batteryVoltage?.let { String.format(Locale.getDefault(), "%.1f V", it) } ?: stringResource(R.string.value_unknown),
                            valueColor = summary.batteryVoltage?.let { if (it < 12.0) LocalStatusColors.current.warn else LocalStatusColors.current.ok }
                                ?: MaterialTheme.colorScheme.onSurface,
                        )
                        if (summary.identification.calibrationIds.isNotEmpty()) {
                            InfoRow(stringResource(R.string.vehicle_calibration), summary.identification.calibrationIds.joinToString(", "))
                        }
                    }
                }
            }

            // The everyday actions come before the technical adapter details.
            BigButton(stringResource(R.string.live_title), icon = Icons.Filled.Speed) { vm.navigate(Screen.LiveData) }
            BigButton(stringResource(R.string.faults_title), icon = Icons.Filled.ReportProblem) { vm.navigate(Screen.FaultCodes) }
            BigButton(stringResource(R.string.readiness_title), icon = Icons.Filled.Checklist) { vm.navigate(Screen.Readiness) }
            BigButton(stringResource(R.string.modules_title), icon = Icons.Filled.Memory) { vm.navigate(Screen.Modules) }

            state.connected?.let { connected ->
                Section(title = stringResource(R.string.vehicle_adapter)) {
                    val a = connected.adapter
                    InfoRow(stringResource(R.string.vehicle_protocol), connected.protocol?.label ?: stringResource(R.string.value_unknown))
                    InfoRow(stringResource(R.string.vehicle_adapter_identity), listOfNotNull(a.identity, a.firmware).joinToString(" · "))
                    InfoRow(stringResource(R.string.vehicle_adapter_chip), chipLabel(a.chip))
                    state.transportDescription?.let { InfoRow(stringResource(R.string.vehicle_connection), it) }
                    if (a.chip == AdapterChip.ELM327_CLONE) WarningBox(stringResource(R.string.vehicle_clone_warning))
                    a.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }

            val secondary = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            OutlinedButton(onClick = { vm.navigate(Screen.Log) }, modifier = secondary.padding(top = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null)
                Text("  " + stringResource(R.string.log_title))
            }
            OutlinedButton(onClick = { vm.navigate(Screen.Settings) }, modifier = secondary) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Text("  " + stringResource(R.string.settings_title))
            }
            OutlinedButton(onClick = { vm.navigate(Screen.About) }, modifier = secondary) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Text("  " + stringResource(R.string.about_title))
            }
            OutlinedButton(onClick = vm::disconnect, modifier = secondary) { Text(stringResource(R.string.action_disconnect)) }
        }
    }
}

@Composable
fun chipLabel(chip: AdapterChip): String = when (chip) {
    AdapterChip.ELM327_GENUINE -> stringResource(R.string.chip_elm_genuine)
    AdapterChip.STN -> stringResource(R.string.chip_stn)
    AdapterChip.ELM327_CLONE -> stringResource(R.string.chip_clone)
    AdapterChip.SIMULATOR -> stringResource(R.string.chip_simulator)
    AdapterChip.UNKNOWN -> stringResource(R.string.value_unknown)
}
