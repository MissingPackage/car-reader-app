package io.carreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.Loadable
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.UiState
import io.carreader.core.uds.FailureTypes

@Composable
fun EcuDetailScreen(code: String, state: UiState, vm: MainViewModel) {
    val report = state.scan.reports.firstOrNull { it.ecu.code == code }
    val detail = state.ecuDetail?.takeIf { it.code == code }
    var askClear by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = report?.let { "${it.ecu.code} ${it.ecu.name}" } ?: code,
        onBack = { vm.back() },
    ) { padding ->
        if (report == null) {
            EmptyText(stringResource(R.string.modules_none))
            return@ScreenScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section(title = stringResource(R.string.ecu_identification)) {
                    InfoRow(
                        stringResource(R.string.ecu_address),
                        "0x%03X → 0x%03X".format(report.ecu.address.requestId, report.ecu.address.responseId ?: 0),
                    )
                    InfoRow(stringResource(R.string.ecu_protocol), report.ecu.protocol.name)
                    if (report.identification.isEmpty()) {
                        Text(stringResource(R.string.ecu_no_identification), style = MaterialTheme.typography.bodyMedium)
                    }
                    report.identification.forEach { (label, value) -> InfoRow(label, value) }
                }
            }

            item { Text(stringResource(R.string.ecu_dtcs), style = MaterialTheme.typography.titleLarge) }
            if (detail?.cleared == true) item { WarningBox(stringResource(R.string.ecu_cleared_rescan)) }
            if (report.dtcs.isEmpty()) {
                item { EmptyText(stringResource(R.string.faults_none)) }
            } else {
                items(report.dtcs, key = { it.code + (it.failureType ?: 0) }) { dtc -> DtcCard(dtc, failureTypeText(dtc.failureType)) }
            }
            item {
                BigButton(
                    stringResource(R.string.ecu_clear),
                    icon = Icons.Filled.DeleteSweep,
                    danger = true,
                    enabled = detail?.clearing != true,
                ) { askClear = true }
            }
            if (detail?.clearing == true) item { Loading(stringResource(R.string.faults_clearing)) }

            val items = detail?.items.orEmpty()
            if (items.isNotEmpty()) {
                item { Text(stringResource(R.string.ecu_data), style = MaterialTheme.typography.titleLarge) }
                when (val values = detail?.values ?: Loadable.Idle) {
                    Loadable.Idle, Loadable.Loading -> item { Loading() }
                    is Loadable.Failed -> item { ErrorText(values.message, onRetry = vm::refreshEcuData) }
                    is Loadable.Ready -> item {
                        Section {
                            if (values.value.isEmpty()) Text(stringResource(R.string.ecu_data_none), style = MaterialTheme.typography.bodyMedium)
                            values.value.forEach { InfoRow(it.name, it.text) }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = vm::refreshEcuData, enabled = detail?.values !is Loadable.Loading) {
                        androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("  " + stringResource(R.string.action_refresh))
                    }
                }
            }
        }
    }
    if (askClear && report != null) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.ecu_clear)) },
            text = { Text(stringResource(R.string.ecu_clear_question, "${report.ecu.code} ${report.ecu.name}")) },
            confirmButton = { TextButton(onClick = { askClear = false; vm.clearEcuDtcs() }) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** ISO 14229 failure-type byte as text (English, from the core table); null keeps the hex code only. */
fun failureTypeText(ftb: Int?): String? = ftb?.let(FailureTypes::describe)
