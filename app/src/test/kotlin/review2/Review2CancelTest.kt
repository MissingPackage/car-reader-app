package review2

import io.carreader.app.logging.AppAuditLog
import io.carreader.app.logging.AppInfo
import io.carreader.app.session.SessionDependencies
import io.carreader.app.session.SessionFactory
import io.carreader.app.settings.InMemorySettings
import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.transport.DemoCar
import io.carreader.app.transport.DemoTransports
import io.carreader.app.transport.DeviceDiscovery
import io.carreader.app.transport.DeviceEntry
import io.carreader.app.transport.TcpTransport
import io.carreader.app.transport.TransportProvider
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.session.ConnectionState
import io.carreader.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class BlockingSocket : Socket() {
    val closed = CountDownLatch(1)
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (!closed.await(8, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("connect timed out")
        throw java.net.SocketException("Socket closed")
    }
    override fun close() { closed.countDown(); super.close() }
}

/** Never returns from close: a stuck adapter release. */
private class StuckCloseTransport(private val inner: Transport) : Transport by inner {
    val release = CountDownLatch(1)
    override suspend fun close() { withContext(Dispatchers.IO) { release.await(20, TimeUnit.SECONDS) }; inner.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Review2CancelTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() { Dispatchers.resetMain(); main.close() }

    private fun vm(provider: TransportProvider, traffic: RingTrafficLog): MainViewModel {
        val settings = InMemorySettings()
        val confirmation = DialogConfirmationPort()
        val audit = AppAuditLog(file = null)
        val deps = SessionDependencies(ClearOnlyPolicy { settings.mutationsEnabled }, confirmation, audit, traffic, { 0L })
        return MainViewModel(
            transports = provider,
            sessionFactory = { SessionFactory.create(it, deps) },
            settings = settings,
            confirmation = confirmation,
            discovery = object : DeviceDiscovery {
                override fun status() = BluetoothStatus.UNSUPPORTED
                override fun pairedDevices() = emptyList<DeviceEntry>()
                override fun scanLe() = emptyFlow<DeviceEntry>()
            },
            traffic = traffic,
            audit = audit,
            appInfo = AppInfo("test", 1, "JVM", "0"),
            cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            io = Dispatchers.IO,
            clock = System::currentTimeMillis,
        )
    }

    private fun onMain(block: () -> Unit) = runBlocking(main) { block() }

    @Test fun cancellingABlockedWifiConnectEndsDisconnectedNotFailed() {
        val traffic = RingTrafficLog(clock = { 0L })
        val vm = vm({ t -> if (t is ConnectTarget.Demo) DemoTransports.create(t.car) else TcpTransport("10.255.255.1", 35000, connectTimeoutMs = 8000, socketFactory = { BlockingSocket() }) }, traffic)
        onMain { vm.connect(ConnectTarget.Wifi("10.255.255.1", 35000)) }
        Thread.sleep(500)
        onMain { vm.cancelConnect() }
        Thread.sleep(1500)
        val c = vm.state.value.connection
        println("after cancel: $c")
        traffic.snapshot().forEach { println("  ${it.direction} ${it.text}") }
        assertTrue("after Cancel the connect screen shows $c", c is ConnectionState.Disconnected)
        // And the next attempt (demo) is not blocked.
        onMain { vm.connect(ConnectTarget.Demo(DemoCar.PASSAT)) }
        val t0 = System.currentTimeMillis()
        while (vm.state.value.connection !is ConnectionState.Connected && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(100)
        println("demo connect after cancel took ${System.currentTimeMillis() - t0} ms")
        val after = vm.state.value.connection
        onMain { vm.disconnect() }
        assertTrue("demo after cancel: $after", after is ConnectionState.Connected)
    }

    @Test fun aStuckReleaseBlocksTheNextConnectForever() {
        val traffic = RingTrafficLog(clock = { 0L })
        lateinit var stuck: StuckCloseTransport
        val vm = vm({ t ->
            val d = DemoTransports.create((t as ConnectTarget.Demo).car)
            if (t.car == DemoCar.PASSAT) StuckCloseTransport(d).also { stuck = it } else d
        }, traffic)
        onMain { vm.connect(ConnectTarget.Demo(DemoCar.PASSAT)) }
        val t0 = System.currentTimeMillis()
        while (vm.state.value.connection !is ConnectionState.Connected && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(100)
        println("plain demo connect took ${System.currentTimeMillis() - t0} ms")
        assertTrue(vm.state.value.connection is ConnectionState.Connected)
        onMain { vm.disconnect() }
        onMain { vm.connect(ConnectTarget.Demo(DemoCar.Q5)) }
        Thread.sleep(12000)
        val c = vm.state.value.connection
        println("Q5 connect while the Passat release is stuck: $c")
        stuck.release.countDown()
        onMain { vm.disconnect() }
        assertTrue("next connect waited on a stuck release: $c", c is ConnectionState.Connected)
    }
}
