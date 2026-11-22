package io.carreader.app.review

import io.carreader.app.logging.AppAuditLog
import io.carreader.app.logging.AppInfo
import io.carreader.app.session.PreviewVehicleSession
import io.carreader.app.session.SessionDependencies
import io.carreader.app.session.SessionFactory
import io.carreader.app.settings.InMemorySettings
import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.transport.DemoCar
import io.carreader.app.transport.DemoTransports
import io.carreader.app.transport.DeviceDiscovery
import io.carreader.app.transport.DeviceEntry
import io.carreader.app.transport.TransportProvider
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.Screen
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.Direction
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.session.ConnectionState
import io.carreader.core.session.VehicleSession
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regression tests for the app review findings C1, C2, M3 and M4 beyond the reviewer's own tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFindingsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Killable(private val inner: Transport) : Transport by inner {
        @Volatile var dead = false
        override suspend fun write(data: ByteArray) { if (dead) throw TransportException("The BLE adapter disconnected"); inner.write(data) }
        override suspend fun read(timeoutMs: Long): ByteArray { if (dead) throw TransportException("BLE connection closed"); return inner.read(timeoutMs) }
    }

    private class FakeTransport : Transport {
        override val description = "Fake adapter"
        override suspend fun connect() = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun read(timeoutMs: Long) = ByteArray(0)
        override suspend fun close() = Unit
    }

    /** A session whose adapter release takes a while (a Bluetooth socket closing). */
    private class SlowRelease(private val inner: VehicleSession, private val events: MutableList<String>) : VehicleSession by inner {
        override suspend fun disconnect() {
            delay(2_000)
            inner.disconnect()
            events += "released"
        }
    }

    private val traffic = RingTrafficLog(clock = { 0L })

    private fun vm(
        scope: CoroutineScope,
        provider: TransportProvider,
        sessionFactory: ((Transport) -> VehicleSession)? = null,
    ): MainViewModel {
        val settings = InMemorySettings()
        val confirmation = DialogConfirmationPort()
        val audit = AppAuditLog(file = null)
        val deps = SessionDependencies(ClearOnlyPolicy { settings.mutationsEnabled }, confirmation, audit, traffic, { 0L })
        return MainViewModel(
            transports = provider,
            sessionFactory = sessionFactory ?: { SessionFactory.create(it, deps) },
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
            cleanupScope = scope,
            io = dispatcher,
            clock = { 0L },
        )
    }

    @Test
    fun everyConnectionAttemptStartsWithASeparatorInTheLog() = runTest(dispatcher) {
        val vm = vm(backgroundScope, { DemoTransports.create(DemoCar.PASSAT) })
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        val separators = traffic.snapshot().filter { it.direction == Direction.INFO && "New connection" in it.text }
        vm.disconnect()
        assertEquals(1, separators.size)
        assertTrue(separators.single().text.contains("Demo PASSAT"))
        assertTrue(separators.single().text.contains("1970-01-01"))
    }

    @Test
    fun reconnectAfterALostAdapterConnectsAgain() = runTest(dispatcher) {
        val transports = mutableListOf<Killable>()
        val vm = vm(backgroundScope, { t -> Killable(DemoTransports.create((t as ConnectTarget.Demo).car)).also { transports += it } })
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        vm.navigate(Screen.LiveData)
        advanceTimeBy(3_000); runCurrent()
        transports.last().dead = true
        advanceTimeBy(10_000); runCurrent()
        assertTrue(vm.state.value.connection is ConnectionState.Failed)

        vm.reconnect()
        advanceUntilIdle()
        val s = vm.state.value
        vm.disconnect()
        assertEquals(2, transports.size)
        assertTrue("reconnect did not connect: ${s.connection}", s.connection is ConnectionState.Connected)
        assertEquals(listOf(Screen.Vehicle), s.backStack)
    }

    @Test
    fun newConnectionWaitsForThePreviousAdapterRelease() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        // The test scope (not backgroundScope) runs the release, so advanceUntilIdle waits for its delay.
        val vm = vm(
            this,
            { events += "create"; FakeTransport() },
            sessionFactory = { SlowRelease(PreviewVehicleSession(transportDescription = it.description), events) },
        )
        vm.connect(ConnectTarget.Wifi("192.168.0.10", 35000))
        advanceUntilIdle()
        vm.disconnect()
        vm.connect(ConnectTarget.Wifi("192.168.0.10", 35000))
        advanceUntilIdle()
        val connected = vm.state.value.connection
        vm.disconnect()
        assertEquals(listOf("create", "released", "create"), events.take(3))
        assertTrue(connected is ConnectionState.Connected)
    }

    @Test
    fun liveDataStopsInTheBackgroundAndResumesInTheForeground() = runTest(dispatcher) {
        val vm = vm(backgroundScope, { FakeTransport() }, sessionFactory = { PreviewVehicleSession(transportDescription = it.description) })
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        vm.navigate(Screen.LiveData)
        advanceTimeBy(1_200); runCurrent()
        assertTrue(vm.state.value.live.running)

        vm.onAppBackground()
        assertFalse(vm.state.value.live.running)
        advanceUntilIdle() // would never finish if the live flow were still collected

        vm.onAppForeground()
        runCurrent()
        val running = vm.state.value.live.running
        vm.disconnect()
        assertTrue(running)
    }

    @Test
    fun leavingTheModulesScreenCancelsTheScan() = runTest(dispatcher) {
        val vm = vm(backgroundScope, { FakeTransport() }, sessionFactory = {
            PreviewVehicleSession(transportDescription = it.description, stepDelayMs = 500)
        })
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        vm.navigate(Screen.Modules)
        advanceTimeBy(100); runCurrent()
        assertTrue(vm.state.value.scan.running)
        vm.back()
        advanceUntilIdle() // would run the whole scan if it were not cancelled
        val scan = vm.state.value.scan
        assertFalse(scan.running)
        assertFalse("a cancelled scan must start again on the next visit", scan.started)

        vm.navigate(Screen.Modules)
        advanceUntilIdle()
        val again = vm.state.value.scan
        vm.disconnect()
        assertEquals(PreviewVehicleSession.previewEcus.size, again.reports.size)
    }
}
