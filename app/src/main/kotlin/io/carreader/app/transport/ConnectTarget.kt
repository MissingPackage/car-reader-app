package io.carreader.app.transport

import io.carreader.core.transport.Transport

/** The demo cars offered on the connect screen; [PASSAT_ROLLED_BACK] shows the mileage-check warning. */
enum class DemoCar { PASSAT, Q5, PASSAT_ROLLED_BACK, PEUGEOT_308, FIAT_500X, FIAT_500X_GATEWAY }

/** What the user picked on the connect screen. */
sealed interface ConnectTarget {
    /**
     * [typeUnknown]: Android did not report the device type (often a BLE-only adapter paired in the system
     * settings); the app tries classic first and suggests the BLE scan when that fails.
     */
    data class BluetoothClassic(val name: String, val address: String, val typeUnknown: Boolean = false) : ConnectTarget
    data class Ble(val name: String, val address: String) : ConnectTarget
    data class Wifi(val host: String, val port: Int) : ConnectTarget
    data class Demo(val car: DemoCar) : ConnectTarget
}

/** Turns a [ConnectTarget] into a [Transport]. Android implementation: [AndroidTransportProvider]. */
fun interface TransportProvider {
    fun create(target: ConnectTarget): Transport
}

/** [UNKNOWN]: a bonded device whose type Android does not know yet (it has not been seen since boot). */
enum class DeviceKind { CLASSIC, BLE, DUAL, UNKNOWN }

/** A Bluetooth device shown on the connect screen. */
data class DeviceEntry(val name: String?, val address: String, val kind: DeviceKind) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

/** Sorting of device lists: OBD-looking names first, then named devices, then by name. */
object DeviceRanking {
    private val obdKeywords = listOf(
        "OBD", "ELM", "VLINK", "VGATE", "KONNWEI", "VEEPEAK", "ICAR", "BAFX", "KIWI",
        "CARISTA", "STN", "SCANTOOL", "LELINK", "VIECAR", "CARLY", "MUCAR", "THINKCAR",
    )

    fun looksLikeObd(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.uppercase().filter { it.isLetterOrDigit() }
        return obdKeywords.any { normalized.contains(it) }
    }

    fun rank(devices: List<DeviceEntry>): List<DeviceEntry> = devices
        .distinctBy { it.address }
        .sortedWith(
            compareByDescending<DeviceEntry> { looksLikeObd(it.name) }
                .thenByDescending { !it.name.isNullOrBlank() }
                .thenBy { it.displayName.lowercase() },
        )
}
