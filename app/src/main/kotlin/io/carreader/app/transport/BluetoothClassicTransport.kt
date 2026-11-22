package io.carreader.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth classic (SPP/RFCOMM) adapters: OBDLink MX+/LX, vLinker MC/FS, most ELM327 clones.
 * Callers must hold BLUETOOTH_CONNECT (Android 12+) before [connect].
 *
 * Reading: a blocking reader on [Dispatchers.IO] feeds [incoming]. `InputStream.available()` keeps returning 0
 * after the adapter is gone, so polling it would never notice a lost connection; a blocking read returns -1 or
 * throws, which closes the channel and makes [read] throw.
 */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val label: String,
    private val log: TransportLog = TransportLog.None,
) : Transport {
    override val description: String = "$label (${device.address})"

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var incoming = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var readerScope: CoroutineScope? = null

    /** One way of opening the RFCOMM socket; adapters and phones differ in which one works. */
    private class Attempt(val name: String, val create: () -> BluetoothSocket)

    override suspend fun connect() = withContext(Dispatchers.IO) {
        // Discovery slows RFCOMM connects down dramatically and makes them fail on many phones.
        runCatching { adapter.cancelDiscovery() }
        val attempts = listOf(
            Attempt("secure SPP") { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            // Many clones refuse the secure (authenticated) socket.
            Attempt("insecure SPP") { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            // Some clones publish no SDP record for SPP: connect to RFCOMM channel 1 directly.
            Attempt("RFCOMM channel 1") { rfcommChannel1() },
        )
        val errors = mutableListOf<String>()
        for ((i, attempt) in attempts.withIndex()) {
            if (i > 0) delay(RETRY_DELAY_MS)
            val candidate = try {
                attempt.create()
            } catch (e: Exception) {
                errors += "${attempt.name}: ${e.message ?: e.javaClass.simpleName}"
                log.info("Bluetooth ${attempt.name} socket not available: ${e.message ?: e.javaClass.simpleName}")
                continue
            }
            try {
                open(candidate)
                log.info("Bluetooth connected to ${device.address} with the ${attempt.name} socket (attempt ${i + 1} of ${attempts.size})")
                return@withContext
            } catch (e: IOException) {
                errors += "${attempt.name}: ${e.message}"
                log.info("Bluetooth ${attempt.name} connect failed: ${e.message}")
            }
        }
        throw TransportException("Bluetooth connection to $label failed (${errors.joinToString("; ")})")
    }

    /** Hidden API, reached by reflection: the last resort used by most OBD apps for clones without SDP. */
    @SuppressLint("DiscouragedPrivateApi")
    private fun rfcommChannel1(): BluetoothSocket =
        device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket

    private suspend fun open(candidate: BluetoothSocket) {
        try {
            abortOnCancel(abort = candidate::close) { candidate.connect() }
        } catch (e: Throwable) {
            runCatching { candidate.close() }
            throw e
        }
        val input: InputStream
        val out: java.io.OutputStream
        try {
            input = candidate.inputStream
            out = candidate.outputStream
        } catch (e: Throwable) {
            runCatching { candidate.close() }
            throw e
        }
        socket = candidate
        output = out
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        incoming = channel
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readerScope = scope
        scope.launch { readLoop(input, channel) }
    }

    private fun readLoop(input: InputStream, channel: Channel<ByteArray>) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (e: Throwable) {
                // Any vendor-stack failure ends the connection instead of escaping the reader scope.
                channel.close(TransportException("Bluetooth read failed: ${e.message}", e))
                return
            }
            if (n < 0) {
                channel.close(TransportException("The Bluetooth adapter closed the connection"))
                return
            }
            if (n > 0) channel.trySend(buffer.copyOf(n))
        }
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

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (socket == null) throw TransportException("Not connected")
        return ChannelReader.read(incoming, timeoutMs, closedMessage = "The Bluetooth adapter closed the connection")
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        val s = socket
        socket = null
        output = null
        // Closing the socket ends the blocking read in the reader coroutine.
        runCatching { s?.close() }
        readerScope?.cancel()
        readerScope = null
        incoming.close()
        Unit
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BUFFER_SIZE = 4096
        private const val RETRY_DELAY_MS = 300L
    }
}
