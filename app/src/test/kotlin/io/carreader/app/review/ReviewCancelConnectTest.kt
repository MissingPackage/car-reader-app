package io.carreader.app.review

import io.carreader.app.transport.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A socket whose connect blocks like an unreachable host until close() is called (or 4 s pass). */
private class BlockingSocket : Socket() {
    val closed = CountDownLatch(1)
    @Volatile var closedAtMs = -1L
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (!closed.await(4, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("connect timed out")
        throw java.net.SocketException("Socket closed")
    }
    override fun close() {
        closedAtMs = System.currentTimeMillis()
        closed.countDown()
        super.close()
    }
}

class ReviewCancelConnectTest {
    @Test
    fun cancellingABlockedConnectClosesTheSocketPromptly() = runBlocking {
        val sock = BlockingSocket()
        val t = TcpTransport("10.255.255.1", 35000, connectTimeoutMs = 4000, socketFactory = { sock })
        val job = launch(Dispatchers.IO) { runCatching { t.connect() } }
        delay(300)
        val cancelAt = System.currentTimeMillis()
        job.cancel()
        job.join()
        val tookMs = System.currentTimeMillis() - cancelAt
        println("cancel->join took $tookMs ms, socket closed ${if (sock.closedAtMs < 0) "never" else "${sock.closedAtMs - cancelAt} ms after cancel"}")
        assertTrue("cancel should abort the blocking connect quickly, took $tookMs ms", tookMs < 1000)
    }

    @Test
    fun tcpReadTimeoutIsEmptyAndPeerCloseThrows() = runBlocking {
        ServerSocket(0).use { server ->
            val t = TcpTransport("127.0.0.1", server.localPort)
            val accept = launch(Dispatchers.IO) {
                val c = server.accept()
                delay(400)
                c.close()
            }
            t.connect()
            val empty = t.read(100)
            assertTrue(empty.isEmpty())
            accept.join()
            val thrown = runCatching { t.read(500) }.exceptionOrNull()
            assertTrue("closed peer must throw, got $thrown", thrown is io.carreader.core.transport.TransportException)
            t.close()
        }
    }
}
