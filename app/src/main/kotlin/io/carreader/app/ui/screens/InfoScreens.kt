package io.carreader.app.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.carreader.app.BuildConfig
import io.carreader.app.R
import io.carreader.app.ui.LogSharing
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.UiState

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = { vm.back() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_mutations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = state.mutationsEnabled,
                        onCheckedChange = { on -> if (on) confirmEnable = true else vm.setMutationsEnabled(false) },
                    )
                }
                Text(stringResource(R.string.settings_mutations_hint), style = MaterialTheme.typography.bodyMedium)
            }
            WarningBox(stringResource(R.string.settings_mutations_warning))
            Text(stringResource(R.string.settings_never), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.settings_mutations)) },
            text = { Text(stringResource(R.string.settings_enable_question)) },
            confirmButton = {
                TextButton(onClick = { confirmEnable = false; vm.setMutationsEnabled(true) }) { Text(stringResource(R.string.settings_enable)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun LogScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val traffic = remember(version, state.connection) { vm.trafficLines() }
    val audit = remember(version) { vm.auditLines() }
    val subject = stringResource(R.string.log_share_subject, BuildConfig.VERSION_NAME)
    val chooser = stringResource(R.string.log_share)
    ScreenScaffold(
        title = stringResource(R.string.log_title),
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { version++ }) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
            IconButton(onClick = { vm.clearTraffic(); version++ }) { Icon(Icons.Filled.DeleteOutline, stringResource(R.string.log_clear)) }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Text(stringResource(R.string.log_hint), style = MaterialTheme.typography.bodyMedium) }
            item {
                BigButton(stringResource(R.string.log_share), icon = Icons.Filled.Share, modifier = Modifier.padding(vertical = 12.dp)) {
                    LogSharing.share(context, vm.buildReport(), subject, chooser)
                }
            }
            item { Text(stringResource(R.string.log_audit), style = MaterialTheme.typography.titleMedium) }
            if (audit.isEmpty()) item { Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium) }
            items(audit) { LogLine(it) }
            item { Text(stringResource(R.string.log_traffic, traffic.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            if (traffic.isEmpty()) item { Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium) }
            items(traffic) { LogLine(it) }
        }
    }
}

@Composable
private fun LogLine(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        softWrap = false,
    )
}

@Composable
fun AboutScreen(vm: MainViewModel) {
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = { vm.back() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodyLarge)
            Section(title = stringResource(R.string.about_safety_title)) {
                Text(stringResource(R.string.about_safety), style = MaterialTheme.typography.bodyLarge)
            }
            Section(title = stringResource(R.string.about_sources_title)) {
                Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.bodyMedium)
            }
            Section(title = stringResource(R.string.about_licenses_title)) {
                Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
