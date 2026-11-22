package io.carreader.app.transport

import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Wi-Fi ELM327 adapters: a plain TCP socket, by default 192.168.0.10:35000.
 * [socketFactory] lets the Android side bind the socket to the Wi-Fi network: the adapter's access point has no
 * internet, so without binding Android may route the connection over mobile data.
 */
class TcpTransport(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val connectTimeoutMs: Int = 6000,
    private val socketFactory: () -> Socket = { Socket() },
) : Transport {
    override val description: String = "Wi-Fi $host:$port"

    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val buffer = ByteArray(4096)

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = socketFactory()
        try {
            // Cancelling (the Cancel button) closes the socket, which aborts a connect to an unreachable host.
            abortOnCancel(abort = s::close) {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
        } catch (e: IOException) {
            runCatching { s.close() }
            throw TransportException("Cannot reach the Wi-Fi adapter at $host:$port (${e.message})", e)
        } catch (e: Throwable) {
            runCatching { s.close() }
            throw e
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportException("Not connected")
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            throw TransportException("Wi-Fi write failed: ${e.message}", e)
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val s = socket ?: throw TransportException("Not connected")
        val inp = input ?: throw TransportException("Not connected")
        try {
            s.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val n = try {
                inp.read(buffer)
            } catch (_: SocketTimeoutException) {
                return@withContext ByteArray(0)
            }
            if (n < 0) throw TransportException("The Wi-Fi adapter closed the connection")
            val first = buffer.copyOf(n)
            val more = inp.available()
            if (more <= 0) first else first + ByteArray(more).let { extra -> extra.copyOf(inp.read(extra).coerceAtLeast(0)) }
        } catch (e: IOException) {
            throw TransportException("Wi-Fi read failed: ${e.message}", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
