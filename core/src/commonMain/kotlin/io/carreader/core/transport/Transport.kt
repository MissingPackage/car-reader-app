package io.carreader.core.transport

/**
 * Port: a raw byte channel to an adapter (Bluetooth SPP, BLE, Wi-Fi TCP, in-process simulator).
 * Adapters live at the edges: platform modules (Android) and the simulator implement it.
 */
interface Transport {
    /** Human-readable label for UI and logs, e.g. "OBDLink MX+ (00:04:3E:...)". */
    val description: String

    suspend fun connect()

    suspend fun write(data: ByteArray)

    /**
     * Returns the bytes available, waiting at most [timeoutMs] for the first one.
     * Returns an empty array on timeout. Throws [TransportException] when the channel is closed or broken.
     */
    suspend fun read(timeoutMs: Long): ByteArray

    suspend fun close()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Factory port so the session layer can (re)create a transport without knowing the platform. */
fun interface TransportFactory {
    fun create(): Transport
}
