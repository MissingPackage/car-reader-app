package io.carreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.Loadable
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.UiState
import io.carreader.app.ui.theme.LocalStatusColors
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcDescriptions
import io.carreader.core.obd2.DtcSource

private val tabs = listOf(DtcSource.STORED, DtcSource.PENDING, DtcSource.PERMANENT)

@Composable
fun FaultCodesScreen(state: UiState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var askClear by rememberSaveable { mutableStateOf(false) }
    val faults = state.faults
    ScreenScaffold(
        title = stringResource(R.string.faults_title),
        onBack = { vm.back() },
        actions = { IconButton(onClick = vm::refreshFaults) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, source ->
                    val count = faults.dtcs[source]?.valueOrNull?.size
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(sourceLabel(source) + (count?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(sourceHint(tabs[tab]), style = MaterialTheme.typography.bodySmall) }
                when (val list = faults.dtcs[tabs[tab]] ?: Loadable.Idle) {
                    Loadable.Idle, Loadable.Loading -> item { Loading(stringResource(R.string.faults_reading)) }
                    is Loadable.Failed -> item { ErrorText(list.message, onRetry = vm::refreshFaults) }
                    is Loadable.Ready -> if (list.value.isEmpty()) {
                        item { EmptyText(stringResource(R.string.faults_none)) }
                    } else {
                        items(list.value, key = { it.code + (it.ecu ?: "") }) { DtcCard(it) }
                    }
                }
                if (tabs[tab] == DtcSource.STORED) {
                    item { FreezeFrameSection(faults.freezeFrame) }
                }
                item {
                    BigButton(
                        stringResource(R.string.faults_clear),
                        icon = Icons.Filled.DeleteSweep,
                        danger = true,
                        enabled = !faults.clearing,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { askClear = true }
                }
                item { Text(stringResource(R.string.faults_clear_hint), style = MaterialTheme.typography.bodySmall) }
                if (faults.clearing) item { Loading(stringResource(R.string.faults_clearing)) }
            }
        }
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.faults_clear)) },
            text = { Text(stringResource(R.string.faults_clear_question)) },
            confirmButton = {
                TextButton(onClick = { askClear = false; vm.clearEmissionDtcs() }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun sourceLabel(source: DtcSource): String = when (source) {
    DtcSource.STORED -> stringResource(R.string.faults_stored)
    DtcSource.PENDING -> stringResource(R.string.faults_pending)
    DtcSource.PERMANENT -> stringResource(R.string.faults_permanent)
    DtcSource.ECU -> stringResource(R.string.faults_ecu)
}

@Composable
private fun sourceHint(source: DtcSource): String = when (source) {
    DtcSource.STORED -> stringResource(R.string.faults_stored_hint)
    DtcSource.PENDING -> stringResource(R.string.faults_pending_hint)
    DtcSource.PERMANENT -> stringResource(R.string.faults_permanent_hint)
    DtcSource.ECU -> ""
}

@Composable
fun DtcCard(dtc: Dtc, failureTypeText: String? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dtc.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                dtc.ecu?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            }
            Text(dtc.description ?: DtcDescriptions.describe(dtc.code) ?: stringResource(R.string.faults_no_description), style = MaterialTheme.typography.bodyLarge)
            dtc.failureType?.let { ftb ->
                val hex = "0x%02X".format(ftb)
                Text(
                    stringResource(R.string.dtc_failure_type, if (failureTypeText != null) "$failureTypeText ($hex)" else hex),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (dtc.statusByte != null) {
                val status = LocalStatusColors.current
                val flags = listOfNotNull(
                    dtc.isConfirmed?.let { (if (it) stringResource(R.string.dtc_confirmed) else stringResource(R.string.dtc_not_confirmed)) to it },
                    dtc.isTestFailed?.let { (if (it) stringResource(R.string.dtc_test_failed) else stringResource(R.string.dtc_test_passed)) to it },
                    dtc.isMilOn?.let { (if (it) stringResource(R.string.dtc_mil_on) else stringResource(R.string.dtc_mil_off)) to it },
                )
                flags.forEach { (label, bad) ->
                    Text("• $label", style = MaterialTheme.typography.bodyMedium, color = if (bad) status.bad else status.ok)
                }
                Text(stringResource(R.string.dtc_status_byte, "0x%02X".format(dtc.statusByte)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FreezeFrameSection(frame: Loadable<io.carreader.core.obd2.FreezeFrame?>) {
    Section(title = stringResource(R.string.faults_freeze_frame)) {
        when (frame) {
            Loadable.Idle, Loadable.Loading -> Loading()
            is Loadable.Failed -> ErrorText(frame.message)
            is Loadable.Ready -> {
                val ff = frame.value
                if (ff == null || ff.values.isEmpty()) {
                    Text(stringResource(R.string.faults_no_freeze_frame), style = MaterialTheme.typography.bodyMedium)
                } else {
                    ff.triggerDtc?.let { InfoRow(stringResource(R.string.faults_freeze_trigger), it) }
                    ff.values.forEach { InfoRow(it.name, it.text) }
                }
            }
        }
    }
}
