package io.carreader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.carreader.app.R
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.app.ui.screens.AboutScreen
import io.carreader.app.ui.screens.ConnectScreen
import io.carreader.app.ui.screens.EcuDetailScreen
import io.carreader.app.ui.screens.FaultCodesScreen
import io.carreader.app.ui.screens.LiveDataScreen
import io.carreader.app.ui.screens.LogScreen
import io.carreader.app.ui.screens.ModulesScreen
import io.carreader.app.ui.screens.ReadinessScreen
import io.carreader.app.ui.screens.SettingsScreen
import io.carreader.app.ui.screens.VehicleScreen
import io.carreader.app.ui.theme.LocalStatusColors
import io.carreader.core.guard.CheckResult

@Composable
fun AppRoot(viewModel: MainViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.confirmation.pending.collectAsStateWithLifecycle()

    BackHandler { if (!viewModel.back()) onExit() }

    when (val screen = state.screen) {
        Screen.Connect -> ConnectScreen(state, viewModel)
        Screen.Vehicle -> VehicleScreen(state, viewModel)
        Screen.LiveData -> LiveDataScreen(state, viewModel)
        Screen.FaultCodes -> FaultCodesScreen(state, viewModel)
        Screen.Readiness -> ReadinessScreen(state, viewModel)
        Screen.Modules -> ModulesScreen(state, viewModel)
        is Screen.EcuDetail -> EcuDetailScreen(screen.code, state, viewModel)
        Screen.Settings -> SettingsScreen(state, viewModel)
        Screen.Log -> LogScreen(state, viewModel)
        Screen.About -> AboutScreen(viewModel)
    }

    pending?.let { ConfirmationDialog(it, viewModel.confirmation) }
    state.message?.let { MessageDialog(it, viewModel) }
}

@Composable
private fun ConfirmationDialog(p: DialogConfirmationPort.PendingConfirmation, port: DialogConfirmationPort) {
    val status = LocalStatusColors.current
    AlertDialog(
        onDismissRequest = { port.answer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = status.warn) },
        title = { Text(CoreText.kind(p.request.kind)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.confirm_target, p.request.targetLabel), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(p.request.description, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.confirm_checks), style = MaterialTheme.typography.titleSmall)
                p.checks.forEach { (name, result) ->
                    val (icon, color, detail) = when (result) {
                        CheckResult.Pass -> Triple(Icons.Filled.CheckCircle, status.ok, null)
                        is CheckResult.Warn -> Triple(Icons.Filled.Warning, status.warn, result.reason)
                        is CheckResult.Fail -> Triple(Icons.Filled.Cancel, status.bad, result.reason)
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(icon, contentDescription = null, tint = color)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(CoreText.checkName(name), style = MaterialTheme.typography.bodyLarge)
                            if (detail != null) Text(CoreText.reason(detail), style = MaterialTheme.typography.bodyMedium, color = color)
                        }
                    }
                }
                Text(stringResource(R.string.confirm_question), style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { port.answer(true) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.confirm_yes)) }
        },
        dismissButton = { TextButton(onClick = { port.answer(false) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MessageDialog(message: UiMessage, vm: MainViewModel) {
    val title: String
    val body: @Composable () -> Unit
    var extra: (@Composable () -> Unit)? = null
    when (message) {
        is UiMessage.GuardBlocked -> {
            title = stringResource(R.string.blocked_title)
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.blocked_intro), style = MaterialTheme.typography.bodyLarge)
                    message.reasons.forEach { Text("• " + CoreText.reason(it), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            if (message.disabledInSettings) {
                extra = {
                    TextButton(onClick = { vm.dismissMessage(); vm.navigate(Screen.Settings) }) { Text(stringResource(R.string.blocked_open_settings)) }
                }
            }
        }
        UiMessage.EmissionCodesCleared -> {
            title = stringResource(R.string.cleared_title)
            body = { Text(stringResource(R.string.cleared_emission), style = MaterialTheme.typography.bodyLarge) }
        }
        UiMessage.EcuCodesCleared -> {
            title = stringResource(R.string.cleared_title)
            body = { Text(stringResource(R.string.cleared_ecu), style = MaterialTheme.typography.bodyLarge) }
        }
        UiMessage.ClearFailed -> {
            title = stringResource(R.string.error_title)
            body = { Text(stringResource(R.string.clear_failed), style = MaterialTheme.typography.bodyLarge) }
        }
        is UiMessage.Error -> {
            title = stringResource(R.string.error_title)
            body = { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
        }
    }
    AlertDialog(
        onDismissRequest = vm::dismissMessage,
        title = { Text(title) },
        text = body,
        confirmButton = { TextButton(onClick = vm::dismissMessage) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = extra,
    )
}
