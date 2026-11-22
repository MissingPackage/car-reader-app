package io.carreader.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.ScanNote
import io.carreader.app.ui.Screen
import io.carreader.app.ui.UiState
import io.carreader.app.ui.theme.LocalStatusColors
import io.carreader.core.brand.EcuReport

@Composable
fun ModulesScreen(state: UiState, vm: MainViewModel) {
    val scan = state.scan
    val brand = state.summary.valueOrNull?.brand
    ScreenScaffold(
        title = stringResource(R.string.modules_title),
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = vm::startScan, enabled = !scan.running) { Icon(Icons.Filled.Refresh, stringResource(R.string.modules_rescan)) }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    brand?.let { stringResource(R.string.modules_brand, it.displayName) } ?: stringResource(R.string.modules_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (scan.running) {
                item {
                    Column {
                        val done = scan.reports.size
                        Text(
                            if (scan.expected > 0) stringResource(R.string.modules_scanning_progress, done, scan.expected)
                            else stringResource(R.string.modules_scanning),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (scan.expected > 0) {
                            LinearProgressIndicator(progress = { done.toFloat() / scan.expected }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            }
            scan.notes.forEach { note -> item { WarningBox(noteText(note)) } }
            scan.brandNotes.forEach { note -> item { WarningBox(note) } }
            scan.error?.let { err -> item { ErrorText(stringResource(R.string.modules_scan_failed, err), onRetry = vm::startScan) } }
            if (!scan.running && scan.started && scan.reports.isEmpty() && scan.error == null) {
                item { EmptyText(stringResource(R.string.modules_none)) }
            }
            val present = scan.reports.filter { it.present }
            val others = scan.reports.filterNot { it.present }
            if (present.isNotEmpty()) {
                item { Text(stringResource(R.string.modules_found, present.size), style = MaterialTheme.typography.titleMedium) }
                items(present, key = { "p" + it.ecu.code }) { report ->
                    EcuRow(report) { vm.navigate(Screen.EcuDetail(report.ecu.code)) }
                }
            }
            if (others.isNotEmpty()) {
                item { Text(stringResource(R.string.modules_not_found), style = MaterialTheme.typography.titleMedium) }
                items(others, key = { "a" + it.ecu.code }) { report -> EcuRow(report, onClick = null) }
            }
        }
    }
}

@Composable
private fun noteText(note: ScanNote): String = when (note) {
    ScanNote.NO_BRAND_MODULE -> stringResource(R.string.note_no_brand)
    ScanNote.PROBABLY_TP20 -> stringResource(R.string.note_tp20)
    ScanNote.NOT_CAN -> stringResource(R.string.note_not_can)
    ScanNote.NOTHING_FOUND -> stringResource(R.string.note_nothing_found)
}

@Composable
private fun EcuRow(report: EcuReport, onClick: (() -> Unit)?) {
    val status = LocalStatusColors.current
    val (icon, tint, label) = when {
        report.error != null -> Triple(Icons.Filled.ErrorOutline, status.bad, stringResource(R.string.modules_error, report.error.orEmpty()))
        !report.present -> Triple(Icons.Filled.RemoveCircleOutline, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.modules_absent))
        report.dtcs.isNotEmpty() -> Triple(
            Icons.Filled.Warning,
            status.warn,
            pluralStringResource(R.plurals.modules_dtc_count, report.dtcs.size, report.dtcs.size),
        )
        else -> Triple(Icons.Filled.CheckCircle, status.ok, stringResource(R.string.modules_ok))
    }
    Card(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${report.ecu.code}  ${report.ecu.name}", style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
            }
        }
    }
}
