package io.carreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.CoreText
import io.carreader.app.ui.Loadable
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.UiState
import io.carreader.app.ui.theme.LocalStatusColors

@Composable
fun ReadinessScreen(state: UiState, vm: MainViewModel) {
    val status = LocalStatusColors.current
    ScreenScaffold(
        title = stringResource(R.string.readiness_title),
        onBack = { vm.back() },
        actions = { IconButton(onClick = vm::refreshReadiness) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) } },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(stringResource(R.string.readiness_hint), style = MaterialTheme.typography.bodyMedium) }
            when (val r = state.readiness) {
                Loadable.Idle, Loadable.Loading -> item { Loading() }
                is Loadable.Failed -> item { ErrorText(r.message, onRetry = vm::refreshReadiness) }
                is Loadable.Ready -> {
                    val report = r.value
                    if (report == null) {
                        item { EmptyText(stringResource(R.string.readiness_unavailable)) }
                    } else {
                        item {
                            Section {
                                InfoRow(
                                    stringResource(R.string.readiness_mil),
                                    stringResource(if (report.milOn) R.string.readiness_mil_on else R.string.readiness_mil_off),
                                    valueColor = if (report.milOn) status.bad else status.ok,
                                )
                                InfoRow(stringResource(R.string.readiness_dtc_count), report.storedDtcCount.toString())
                                InfoRow(
                                    stringResource(R.string.readiness_engine_type),
                                    stringResource(if (report.compressionIgnition) R.string.readiness_diesel else R.string.readiness_petrol),
                                )
                            }
                        }
                        items(report.monitors, key = { it.name }) { m ->
                            val (icon, color, label) = when {
                                !m.supported -> Triple(Icons.Filled.RemoveCircleOutline, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.readiness_not_supported))
                                m.complete -> Triple(Icons.Filled.CheckCircle, status.ok, stringResource(R.string.readiness_complete))
                                else -> Triple(Icons.Filled.HourglassTop, status.warn, stringResource(R.string.readiness_incomplete))
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, tint = color)
                                Spacer(Modifier.width(12.dp))
                                Text(CoreText.monitor(m.name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(12.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
                            }
                        }
                    }
                }
            }
        }
    }
}
