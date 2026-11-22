package io.carreader.app.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.transport.DemoCar
import io.carreader.app.transport.DeviceEntry
import io.carreader.app.transport.DeviceKind
import io.carreader.app.transport.DeviceRanking
import io.carreader.app.transport.Permissions
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.Screen
import io.carreader.app.ui.UiState
import io.carreader.core.session.ConnectionState

@Composable
fun ConnectScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var hasBluetooth by remember { mutableStateOf(Permissions.hasBluetooth(context)) }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        hasBluetooth = Permissions.hasBluetooth(context)
        vm.refreshDevices()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshDevices() }
    // Before Android 12 the paired list needs no runtime permission (only BLE scans need location).
    val pairedAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetooth
    LaunchedEffect(pairedAllowed) {
        if (pairedAllowed) vm.refreshDevices()
    }

    val connecting = state.connection is ConnectionState.Connecting
    val d = state.discovery

    ScreenScaffold(
        title = stringResource(R.string.connect_title),
        onBack = null,
        actions = {
            IconButton(onClick = { vm.navigate(Screen.Log) }) { Icon(Icons.AutoMirrored.Filled.ListAlt, stringResource(R.string.log_title)) }
            IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings_title)) }
            IconButton(onClick = { vm.navigate(Screen.About) }) { Icon(Icons.Filled.Info, stringResource(R.string.about_title)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionStatusCard(state, onCancel = vm::cancelConnect) }

            // Bluetooth: permission, adapter state, paired devices.
            item { Text(stringResource(R.string.connect_paired_title), style = MaterialTheme.typography.titleLarge) }
            when {
                !pairedAllowed -> item {
                    PermissionCard(
                        askedOnce = askedOnce,
                        onAllow = { permissionLauncher.launch(Permissions.bluetoothRuntime()) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    )
                }
                d.bluetooth == BluetoothStatus.UNSUPPORTED -> item { EmptyText(stringResource(R.string.connect_bt_unsupported)) }
                d.bluetooth == BluetoothStatus.DISABLED -> item {
                    Section {
                        Text(stringResource(R.string.connect_bt_disabled), style = MaterialTheme.typography.bodyLarge)
                        BigButton(stringResource(R.string.connect_bt_enable), icon = Icons.Filled.Bluetooth) {
                            runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        }
                    }
                }
                d.paired.isEmpty() -> item { EmptyText(stringResource(R.string.connect_no_paired)) }
                else -> items(d.paired, key = { "p" + it.address }) { device ->
                    DeviceCard(device, enabled = !connecting) {
                        vm.connect(
                            if (device.kind == DeviceKind.BLE) ConnectTarget.Ble(device.displayName, device.address)
                            else ConnectTarget.BluetoothClassic(device.displayName, device.address),
                        )
                    }
                }
            }
            item { Text(stringResource(R.string.connect_pair_hint), style = MaterialTheme.typography.bodySmall) }

            // BLE scan.
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.connect_ble_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (d.scanning) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            item {
                Text(stringResource(R.string.connect_ble_hint), style = MaterialTheme.typography.bodySmall)
            }
            item {
                if (d.scanning) {
                    OutlinedButton(onClick = vm::stopBleScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_ble_stop)) }
                } else {
                    BigButton(
                        stringResource(R.string.connect_ble_scan),
                        icon = Icons.Filled.BluetoothSearching,
                        enabled = !connecting && d.bluetooth == BluetoothStatus.READY,
                    ) {
                        if (Permissions.hasBluetooth(context)) vm.startBleScan()
                        else permissionLauncher.launch(Permissions.bluetoothRuntime())
                    }
                }
            }
            d.scanError?.let { err -> item { ErrorText(stringResource(R.string.connect_ble_failed, err)) } }
            items(d.scanned, key = { "b" + it.address }) { device ->
                DeviceCard(device, enabled = !connecting) { vm.connect(ConnectTarget.Ble(device.displayName, device.address)) }
            }

            // Wi-Fi.
            item { Text(stringResource(R.string.connect_wifi_title), style = MaterialTheme.typography.titleLarge) }
            item {
                Section {
                    Text(stringResource(R.string.connect_wifi_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = d.wifiHost,
                        onValueChange = vm::setWifiHost,
                        label = { Text(stringResource(R.string.connect_wifi_host)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = d.wifiPort,
                        onValueChange = vm::setWifiPort,
                        label = { Text(stringResource(R.string.connect_wifi_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val wifi = vm.wifiTarget()
                    BigButton(stringResource(R.string.connect_wifi_connect), icon = Icons.Filled.Wifi, enabled = !connecting && wifi != null) {
                        wifi?.let(vm::connect)
                    }
                }
            }

            // Demo.
            item { Text(stringResource(R.string.connect_demo_title), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(R.string.connect_demo_hint), style = MaterialTheme.typography.bodySmall) }
            item {
                BigButton(stringResource(R.string.connect_demo_passat), icon = Icons.Filled.DirectionsCar, enabled = !connecting) {
                    vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
                }
            }
            item {
                BigButton(stringResource(R.string.connect_demo_q5), icon = Icons.Filled.DirectionsCar, enabled = !connecting) {
                    vm.connect(ConnectTarget.Demo(DemoCar.Q5))
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: UiState, onCancel: () -> Unit) {
    when (val c = state.connection) {
        is ConnectionState.Connecting -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.connect_connecting), style = MaterialTheme.typography.titleMedium)
                }
                state.transportDescription?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (c.step.isNotBlank()) Text(c.step, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.connect_connecting_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        is ConnectionState.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connect_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(c.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.connect_failed_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        else -> Text(stringResource(R.string.connect_intro), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionCard(askedOnce: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Section(title = stringResource(R.string.perm_title)) {
        Text(stringResource(R.string.perm_explanation), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.perm_no_location), style = MaterialTheme.typography.bodyMedium)
        BigButton(stringResource(R.string.perm_allow), icon = Icons.Filled.Bluetooth, onClick = onAllow)
        if (askedOnce) {
            Text(stringResource(R.string.perm_denied), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.perm_open_settings)) }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceEntry, enabled: Boolean, onClick: () -> Unit) {
    val obd = DeviceRanking.looksLikeObd(device.name)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (obd) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                Text(device.address, style = MaterialTheme.typography.bodyMedium)
                val kind = when (device.kind) {
                    DeviceKind.BLE -> stringResource(R.string.device_kind_ble)
                    DeviceKind.CLASSIC, DeviceKind.DUAL -> stringResource(R.string.device_kind_classic)
                }
                AssistChip(onClick = onClick, enabled = enabled, label = { Text(if (obd) stringResource(R.string.device_obd, kind) else kind) })
            }
        }
    }
}
