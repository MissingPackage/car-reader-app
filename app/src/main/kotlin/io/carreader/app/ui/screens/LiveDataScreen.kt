package io.carreader.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.Loadable
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.UiState
import io.carreader.app.ui.theme.ValueTextStyle

@Composable
fun LiveDataScreen(state: UiState, vm: MainViewModel) {
    val live = state.live
    var picking by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = stringResource(R.string.live_title),
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { picking = !picking }) { Icon(Icons.Filled.Tune, stringResource(R.string.live_choose)) }
            if (live.running) {
                IconButton(onClick = vm::stopLive) { Icon(Icons.Filled.Pause, stringResource(R.string.live_pause)) }
            } else {
                IconButton(onClick = vm::startLive, enabled = live.selected.isNotEmpty()) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.live_resume))
                }
            }
        },
    ) { padding ->
        val available = live.available
        when {
            available is Loadable.Failed -> Column(Modifier.padding(padding).padding(16.dp)) { ErrorText(available.message) }
            available !is Loadable.Ready -> Column(Modifier.padding(padding)) { Loading(stringResource(R.string.live_loading_pids)) }
            available.value.isEmpty() -> Column(Modifier.padding(padding).padding(16.dp)) { EmptyText(stringResource(R.string.live_no_pids)) }
            picking || live.selected.isEmpty() -> PidPicker(padding, state, vm, onDone = { picking = false })
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                live.error?.let { err -> item { ErrorText(stringResource(R.string.live_error, err), onRetry = vm::startLive) } }
                if (!live.running) item { WarningBox(stringResource(R.string.live_paused)) }
                val pidsByKey = available.value.associateBy { it.key }
                val rows = live.selected.flatMap { key ->
                    val values = live.values.values.filter { it.key == key }
                    if (values.isEmpty()) listOf(Triple(key, pidsByKey[key]?.name ?: key, null)) else values.map { Triple(key, it.name, it) }
                }
                items(rows, key = { (key, _, v) -> key + "@" + (v?.ecu ?: "") }) { (_, name, value) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                value?.ecu?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                            }
                            Text(value?.text ?: "—", style = ValueTextStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PidPicker(padding: PaddingValues, state: UiState, vm: MainViewModel, onDone: () -> Unit) {
    val pids = state.live.available.valueOrNull.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.live_choose_hint), style = MaterialTheme.typography.bodyMedium) }
        items(pids, key = { it.key }) { pid ->
            val checked = pid.key in state.live.selected
            Row(
                Modifier.fillMaxWidth().clickable { vm.toggleLivePid(pid.key) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { vm.toggleLivePid(pid.key) })
                Column(Modifier.weight(1f)) {
                    Text(pid.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "PID %02X%s".format(pid.pid, if (pid.unit.isNotBlank()) " · ${pid.unit}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            BigButton(stringResource(R.string.live_show), enabled = state.live.selected.isNotEmpty(), modifier = Modifier.padding(top = 12.dp)) {
                onDone()
                vm.startLive()
            }
        }
    }
}
