package io.carreader.app.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions the Bluetooth transports need, per Android version. Wi-Fi and demo need none. */
object Permissions {
    fun bluetoothRuntime(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // BLUETOOTH and BLUETOOTH_ADMIN are install-time; BLE scans need location before Android 12.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun hasBluetooth(context: Context): Boolean = bluetoothRuntime().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Before Android 12 the paired list works without location; only BLE scans need it. */
    fun canListPaired(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetooth(context)
}
