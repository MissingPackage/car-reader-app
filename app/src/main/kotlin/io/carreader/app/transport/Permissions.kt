package io.carreader.app.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

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

    /** Before Android 12 a BLE scan needs the location permission and finds nothing while location is off. */
    fun bleScanNeedsLocation(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /**
     * Whether the system location switch is on, read only where it matters (before Android 12); true when it
     * cannot be read, so the app suggests nothing then.
     */
    fun locationEnabled(context: Context): Boolean {
        if (!bleScanNeedsLocation()) return true
        return runCatching {
            val lm = context.getSystemService(LocationManager::class.java) ?: return true
            LocationManagerCompat.isLocationEnabled(lm)
        }.getOrDefault(true)
    }
}
