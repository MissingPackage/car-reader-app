package io.carreader.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bluetooth Low Energy adapters (cheap ELM327 BLE clones, vLinker BM/FD, OBDLink CX). The adapter exposes a
 * serial-like service: we write commands to one characteristic and receive the answers as notifications on
 * another. The service is picked by [BleUartProfile].
 *
 * GATT allows one outstanding operation at a time: every operation goes through [gattMutex] and waits for its
 * callback before the next one starts. Callers must hold BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val label: String,
    private val log: TransportLog = TransportLog.None,
) : Transport {
    override val description: String = "$label (${device.address}, BLE)"

    private val gattMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeWithResponse = false
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var incoming = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var connectSignal = CompletableDeferred<Int>()
    /** The GATT operation in flight; only its own callback may complete it. */
    @Volatile private var pending: PendingOp? = null

    private enum class Op { DISCOVER_SERVICES, REQUEST_MTU, WRITE_DESCRIPTOR, WRITE_CHARACTERISTIC }

    private class PendingOp(val op: Op, val signal: CompletableDeferred<Int> = CompletableDeferred())

    private fun complete(op: Op, status: Int) {
        val p = pending ?: return
        if (p.op == op) p.signal.complete(status)
    }

    /** Human-readable summary of the selected service, for the traffic log header. */
    var selectedProfile: UartSelection? = null
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    connectSignal.complete(status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectSignal.complete(if (status == BluetoothGatt.GATT_SUCCESS) STATUS_DISCONNECTED else status)
                    pending?.signal?.complete(STATUS_DISCONNECTED)
                    incoming.close(TransportException("The BLE adapter disconnected (GATT status $status)"))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            complete(Op.DISCOVER_SERVICES, status)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            complete(Op.REQUEST_MTU, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            complete(Op.WRITE_DESCRIPTOR, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            complete(Op.WRITE_CHARACTERISTIC, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }

        @Deprecated("Called before Android 13 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            characteristic.value?.let { incoming.trySend(it.copyOf()) }
        }
    }

    override suspend fun connect() {
        var lastError: TransportException? = null
        // GATT status 133 on the first attempt is common on Android; one retry fixes most of them.
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                connectOnce()
                log.info("BLE connected to ${device.address} on attempt ${attempt + 1} of $CONNECT_ATTEMPTS")
                return
            } catch (e: RetryableBleException) {
                lastError = TransportException(e.message ?: "BLE connection failed", e)
                closeGatt()
                if (attempt < CONNECT_ATTEMPTS - 1) {
                    log.info("BLE attempt ${attempt + 1} failed (${e.message}): retrying in $RETRY_DELAY_MS ms")
                    delay(RETRY_DELAY_MS)
                }
            } catch (e: Throwable) {
                closeGatt()
                throw e
            }
        }
        throw lastError ?: TransportException("BLE connection failed")
    }

    private suspend fun connectOnce() {
        incoming = Channel(Channel.UNLIMITED)
        connectSignal = CompletableDeferred()
        mtu = DEFAULT_MTU
        val g = withContext(Dispatchers.Main) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } ?: throw TransportException("This phone could not open a BLE connection")
        gatt = g
        val status = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectSignal.await() }
            ?: throw RetryableBleException("The BLE adapter did not answer (timeout)")
        if (status != BluetoothGatt.GATT_SUCCESS) throw RetryableBleException("BLE connection failed (GATT status $status)")

        delay(SETTLE_MS)
        val discovered = gattMutex.withLock { operation(Op.DISCOVER_SERVICES, DISCOVERY_TIMEOUT_MS) { it.discoverServices() } }
        if (discovered != BluetoothGatt.GATT_SUCCESS) throw RetryableBleException("BLE service discovery failed (status $discovered)")

        val services = g.services.map { s ->
            GattServiceInfo(s.uuid, s.characteristics.map { GattCharInfo(it.uuid, it.properties) })
        }
        services.forEach { svc ->
            log.info("BLE service ${svc.uuid}: " + svc.characteristics.joinToString { "${it.uuid} [${BleUartProfile.describeProperties(it.properties)}]" })
        }
        val selection = BleUartProfile.select(services)
            ?: throw TransportException("No serial (UART) service found on this BLE device: is it an OBD adapter?")
        selectedProfile = selection
        log.info("BLE UART profile: service ${selection.service}, notify ${selection.notify}, write ${selection.write}")
        val service = g.getService(selection.service)
        val notifyChar = service.getCharacteristic(selection.notify)
        val writable = service.getCharacteristic(selection.write)

        // Larger MTU = fewer chunks; failure is harmless (we stay at 23).
        val mtuStatus = gattMutex.withLock { operation(Op.REQUEST_MTU, MTU_TIMEOUT_MS) { it.requestMtu(REQUESTED_MTU) } }
        log.info("BLE MTU $mtu (requested $REQUESTED_MTU, status $mtuStatus)")

        if (!g.setCharacteristicNotification(notifyChar, true)) {
            throw TransportException("Could not enable notifications on the BLE adapter")
        }
        notifyChar.getDescriptor(BleUartProfile.CCCD)?.let { cccd ->
            val value = if (notifyChar.properties and BleUartProfile.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val result = gattMutex.withLock { operation(Op.WRITE_DESCRIPTOR, DESCRIPTOR_TIMEOUT_MS) { writeDescriptorCompat(it, cccd, value) } }
            if (result != BluetoothGatt.GATT_SUCCESS) {
                throw TransportException("Could not subscribe to the BLE adapter (status $result)")
            }
        }

        // Prefer write-without-response when offered (what ELM BLE clones expect), else acknowledged writes.
        writeWithResponse = writable.properties and BleUartProfile.PROPERTY_WRITE_NO_RESPONSE == 0
        writeChar = writable
        log.info("BLE write type: " + if (writeWithResponse) "with response" else "without response")
    }

    /**
     * Starts one GATT operation and waits for its callback. Returns the GATT status, [STATUS_NOT_STARTED] when
     * the stack refused to start it (busy), [STATUS_TIMEOUT] when no callback arrived. Call under [gattMutex].
     */
    private suspend fun operation(op: Op, timeoutMs: Long, start: (BluetoothGatt) -> Boolean): Int {
        val g = gatt ?: throw TransportException("Not connected")
        val p = PendingOp(op)
        pending = p
        try {
            if (!start(g)) return STATUS_NOT_STARTED
            return withTimeoutOrNull(timeoutMs) { p.signal.await() } ?: STATUS_TIMEOUT
        } finally {
            if (pending === p) pending = null
        }
    }

    override suspend fun write(data: ByteArray) {
        val characteristic = writeChar ?: throw TransportException("Not connected")
        val chunkSize = (mtu - ATT_OVERHEAD).coerceAtLeast(DEFAULT_MTU - ATT_OVERHEAD)
        val type = if (writeWithResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        gattMutex.withLock {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(data.size, offset + chunkSize)
                val part = data.copyOfRange(offset, end)
                var attempts = 0
                while (true) {
                    val status = operation(Op.WRITE_CHARACTERISTIC, if (writeWithResponse) WRITE_ACK_TIMEOUT_MS else WRITE_PACE_TIMEOUT_MS) {
                        writeCharacteristicCompat(it, characteristic, part, type)
                    }
                    when {
                        status == BluetoothGatt.GATT_SUCCESS -> break
                        status == STATUS_TIMEOUT && !writeWithResponse -> break // queued locally, no ack expected
                        status == STATUS_NOT_STARTED && attempts++ < WRITE_RETRIES -> delay(BUSY_RETRY_MS)
                        status == STATUS_DISCONNECTED -> throw TransportException("The BLE adapter disconnected")
                        else -> throw TransportException("BLE write failed (status $status)")
                    }
                }
                offset = end
            }
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        ChannelReader.read(incoming, timeoutMs, closedMessage = "BLE connection closed")

    override suspend fun close() {
        closeGatt()
        incoming.close()
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }

    private class RetryableBleException(message: String) : Exception(message)

    private companion object {
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 247
        const val ATT_OVERHEAD = 3
        const val CONNECT_ATTEMPTS = 2
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val MTU_TIMEOUT_MS = 3_000L
        const val DESCRIPTOR_TIMEOUT_MS = 3_000L
        const val WRITE_ACK_TIMEOUT_MS = 2_000L
        const val WRITE_PACE_TIMEOUT_MS = 300L
        const val WRITE_RETRIES = 10
        const val BUSY_RETRY_MS = 20L
        const val RETRY_DELAY_MS = 600L
        const val SETTLE_MS = 300L
        const val STATUS_NOT_STARTED = -100
        const val STATUS_TIMEOUT = -101
        const val STATUS_DISCONNECTED = -102
    }
}
