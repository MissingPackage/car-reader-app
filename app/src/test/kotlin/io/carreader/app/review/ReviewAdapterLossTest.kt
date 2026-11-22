package io.carreader.app.review

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
import io.carreader.app.transport.TransportProvider
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.Screen
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.session.ConnectionState
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Simulates the adapter vanishing (unplugged, car switched off, BLE out of range). */
private class KillableTransport(private val inner: Transport) : Transport by inner {
    @Volatile var dead = false
    override suspend fun write(data: ByteArray) { if (dead) throw TransportException("The BLE adapter disconnected"); inner.write(data) }
    override suspend fun read(timeoutMs: Long): ByteArray { if (dead) throw TransportException("BLE connection closed"); return inner.read(timeoutMs) }
}

private class FailingTransport : Transport {
    override val description = "Fake classic adapter"
    override suspend fun connect() = throw TransportException("Bluetooth connection to OBDII failed (read failed, socket might closed)")
    override suspend fun write(data: ByteArray) = Unit
    override suspend fun read(timeoutMs: Long) = ByteArray(0)
    override suspend fun close() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewAdapterLossTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(scope: CoroutineScope, provider: TransportProvider, traffic: RingTrafficLog): MainViewModel {
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
            cleanupScope = scope,
            io = dispatcher,
            clock = { 0L },
        )
    }

    @Test
    fun liveDataReportsALostAdapterInsteadOfFreezingTheLastValues() = runTest(dispatcher) {
        lateinit var killable: KillableTransport
        val traffic = RingTrafficLog(clock = { 0L })
        val vm = vm(backgroundScope, { t -> KillableTransport(DemoTransports.create((t as ConnectTarget.Demo).car)).also { killable = it } }, traffic)
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        vm.navigate(Screen.LiveData)
        advanceTimeBy(3_000); runCurrent()
        val before = vm.state.value.live
        assertTrue("live running with values: $before", before.running && before.values.isNotEmpty())
        killable.dead = true
        advanceTimeBy(10_000); runCurrent()
        val s = vm.state.value
        println("after loss: connection=${s.connection::class.simpleName} running=${s.live.running} error=${s.live.error} values=${s.live.values.size}")
        vm.disconnect()
        // Core behaviour since the review: liveData throws and the session state becomes Failed.
        assertTrue("adapter gone but the session still says Connected", s.connection is ConnectionState.Failed)
        assertTrue("live data still running after the adapter was lost", !s.live.running)
        assertTrue("no live-data error shown after the adapter was lost", s.live.error != null)
    }

    @Test
    fun failedRealAttemptTrafficSurvivesTryingTheDemo() = runTest(dispatcher) {
        val traffic = RingTrafficLog(clock = { 0L })
        val vm = vm(backgroundScope, { t -> if (t is ConnectTarget.Demo) DemoTransports.create(t.car) else FailingTransport() }, traffic)
        vm.connect(ConnectTarget.BluetoothClassic("OBDII", "00:11:22:33:44:55"))
        advanceUntilIdle()
        assertTrue(vm.state.value.connection is ConnectionState.Failed)
        assertTrue(traffic.snapshot().any { "read failed" in it.text })
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        val kept = traffic.snapshot().any { "read failed" in it.text }
        vm.disconnect()
        assertTrue("the failed real-adapter traffic was wiped by the demo connect", kept)
    }
}
