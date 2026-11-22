package io.carreader.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class BluetoothStatus { UNSUPPORTED, DISABLED, READY }

/** Lists paired devices and scans for BLE adapters. Android implementation: [AndroidDeviceDiscovery]. */
interface DeviceDiscovery {
    fun status(): BluetoothStatus
    fun pairedDevices(): List<DeviceEntry>
    /** Emits each BLE device seen; scanning stops when the collector is cancelled. */
    fun scanLe(): Flow<DeviceEntry>
}

class BleScanException(val errorCode: Int) : Exception("BLE scan failed (error $errorCode)")

/** Callers must hold the Bluetooth runtime permissions (see Permissions). */
@SuppressLint("MissingPermission")
class AndroidDeviceDiscovery(context: Context) : DeviceDiscovery {
    private val context = context.applicationContext

    override fun status(): BluetoothStatus {
        val adapter = AndroidTransportProvider.bluetoothAdapter(context) ?: return BluetoothStatus.UNSUPPORTED
        return if (runCatching { adapter.isEnabled }.getOrDefault(false)) BluetoothStatus.READY else BluetoothStatus.DISABLED
    }

    override fun pairedDevices(): List<DeviceEntry> {
        val adapter = AndroidTransportProvider.bluetoothAdapter(context) ?: return emptyList()
        val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        return DeviceRanking.rank(bonded.map { it.toEntry(null) })
    }

    override fun scanLe(): Flow<DeviceEntry> = callbackFlow {
        val adapter = AndroidTransportProvider.bluetoothAdapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(BleScanException(-1))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.device.toEntry(result.scanRecord?.deviceName, forceBle = true))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.device.toEntry(it.scanRecord?.deviceName, forceBle = true)) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleScanException(errorCode))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun BluetoothDevice.toEntry(advertisedName: String?, forceBle: Boolean = false): DeviceEntry {
        val kind = when {
            forceBle -> DeviceKind.BLE
            else -> when (runCatching { type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)) {
                BluetoothDevice.DEVICE_TYPE_LE -> DeviceKind.BLE
                BluetoothDevice.DEVICE_TYPE_DUAL -> DeviceKind.DUAL
                else -> DeviceKind.CLASSIC
            }
        }
        val deviceName = advertisedName ?: runCatching { name }.getOrNull()
        return DeviceEntry(deviceName, address, kind)
    }
}
