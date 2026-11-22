package io.carreader.app.transport

import java.util.Locale
import java.util.UUID

/** Platform-free view of a GATT characteristic, so service selection is unit-testable on the JVM. */
data class GattCharInfo(val uuid: UUID, val properties: Int)

data class GattServiceInfo(val uuid: UUID, val characteristics: List<GattCharInfo>)

data class UartSelection(val service: UUID, val notify: UUID, val write: UUID)

/**
 * Picks the serial-over-BLE ("UART") service of an OBD adapter. Known layouts first, then the first service
 * that has a notify (or indicate) characteristic and a writable one.
 */
object BleUartProfile {
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20

    val CCCD: UUID = short(0x2902)

    private data class Known(val service: UUID, val notify: UUID, val write: UUID)

    private val known = listOf(
        Known(short(0xFFF0), short(0xFFF1), short(0xFFF2)),
        Known(short(0xFFE0), short(0xFFE1), short(0xFFE1)),
        Known(short(0x18F0), short(0x2AF0), short(0x2AF1)),
        Known(
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        ),
    )

    /** Standard services that are never the data channel. */
    private val ignored = setOf(short(0x1800), short(0x1801), short(0x180A), short(0x180F))

    fun short(id: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "0000%04X-0000-1000-8000-00805F9B34FB", id))

    fun canNotify(c: GattCharInfo) = c.properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0
    fun canWrite(c: GattCharInfo) = c.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0

    fun select(services: List<GattServiceInfo>): UartSelection? {
        for (k in known) {
            val service = services.firstOrNull { it.uuid == k.service } ?: continue
            val notify = service.characteristics.firstOrNull { it.uuid == k.notify && canNotify(it) }
            val write = service.characteristics.firstOrNull { it.uuid == k.write && canWrite(it) }
            if (notify != null && write != null) return UartSelection(service.uuid, notify.uuid, write.uuid)
        }
        for (service in services) {
            if (service.uuid in ignored) continue
            val notify = service.characteristics.firstOrNull(::canNotify) ?: continue
            val write = service.characteristics.firstOrNull { canWrite(it) && it.uuid != notify.uuid }
                ?: service.characteristics.firstOrNull(::canWrite)
                ?: continue
            return UartSelection(service.uuid, notify.uuid, write.uuid)
        }
        return null
    }
}
