package io.carreader.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Bluetooth classic (SPP/RFCOMM) adapters: OBDLink MX+/LX, vLinker MC/FS, most ELM327 clones.
 * Callers must hold BLUETOOTH_CONNECT (Android 12+) before [connect].
 */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val label: String,
) : Transport {
    override val description: String = "$label (${device.address})"

    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val buffer = ByteArray(4096)

    override suspend fun connect() = withContext(Dispatchers.IO) {
        // Discovery slows RFCOMM connects down dramatically and makes them fail on many phones.
        runCatching { adapter.cancelDiscovery() }
        val secureError: IOException
        try {
            open(device.createRfcommSocketToServiceRecord(SPP_UUID))
            return@withContext
        } catch (e: IOException) {
            secureError = e
        }
        // Many clones refuse the secure (authenticated) socket: retry insecure.
        delay(300)
        try {
            open(device.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
        } catch (e: IOException) {
            throw TransportException(
                "Bluetooth connection to $label failed (${secureError.message}; insecure: ${e.message})",
                e,
            )
        }
    }

    private suspend fun open(candidate: BluetoothSocket) {
        val cancelHandle = coroutineContext.job.invokeOnCompletion { if (it != null) runCatching { candidate.close() } }
        try {
            candidate.connect()
        } catch (e: IOException) {
            runCatching { candidate.close() }
            throw e
        } finally {
            cancelHandle.dispose()
        }
        socket = candidate
        input = candidate.inputStream
        output = candidate.outputStream
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportException("Not connected")
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            throw TransportException("Bluetooth write failed: ${e.message}", e)
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val inp = input ?: throw TransportException("Not connected")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
        try {
            var available = inp.available()
            while (available <= 0) {
                if (System.currentTimeMillis() >= deadline) return@withContext ByteArray(0)
                delay(POLL_MS)
                available = inp.available()
            }
            val n = inp.read(buffer, 0, minOf(available, buffer.size))
            if (n < 0) throw TransportException("The Bluetooth adapter closed the connection")
            buffer.copyOf(n)
        } catch (e: IOException) {
            throw TransportException("Bluetooth read failed: ${e.message}", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_MS = 10L
    }
}
