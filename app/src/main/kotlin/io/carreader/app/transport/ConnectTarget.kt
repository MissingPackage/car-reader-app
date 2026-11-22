package io.carreader.app.transport

import io.carreader.core.transport.Transport

/** The two demo cars offered on the connect screen. */
enum class DemoCar { PASSAT, Q5 }

/** What the user picked on the connect screen. */
sealed interface ConnectTarget {
    data class BluetoothClassic(val name: String, val address: String) : ConnectTarget
    data class Ble(val name: String, val address: String) : ConnectTarget
    data class Wifi(val host: String, val port: Int) : ConnectTarget
    data class Demo(val car: DemoCar) : ConnectTarget
}

/** Turns a [ConnectTarget] into a [Transport]. Android implementation: [AndroidTransportProvider]. */
fun interface TransportProvider {
    fun create(target: ConnectTarget): Transport
}

enum class DeviceKind { CLASSIC, BLE, DUAL }

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
