package io.carreader.app

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
import io.carreader.core.obd2.DtcSource
import io.carreader.core.session.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The demo path the tester will try first: ViewModel -> DefaultVehicleSession -> ElmLink -> SimTransport. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoEndToEndTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun demoPassatConnectsIdentifiesAndScans() = runTest(dispatcher) {
        val settings = InMemorySettings()
        val confirmation = DialogConfirmationPort()
        val audit = AppAuditLog(file = null)
        val traffic = RingTrafficLog(clock = { 0L })
        val deps = SessionDependencies(ClearOnlyPolicy { settings.mutationsEnabled }, confirmation, audit, traffic, { 0L })
        val vm = MainViewModel(
            transports = TransportProvider { target -> DemoTransports.create((target as ConnectTarget.Demo).car) },
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
            cleanupScope = this,
            io = dispatcher,
            clock = { 0L },
        )
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue("connected: ${s.connection}", s.connection is ConnectionState.Connected)
        val summary = s.summary.valueOrNull
        assertNotNull("summary: ${s.summary}", summary)
        assertEquals("WVWZZZ3CZGE512345", summary!!.identification.vin)
        assertNotNull("VAG module detected", summary.brand)

        vm.navigate(Screen.FaultCodes)
        advanceUntilIdle()
        assertTrue(vm.state.value.faults.dtcs[DtcSource.STORED]?.valueOrNull != null)

        vm.back()
        vm.navigate(Screen.Modules)
        advanceUntilIdle()
        val scan = vm.state.value.scan
        assertTrue("some ECU present: ${scan.reports.map { it.ecu.code to it.present }}", scan.reports.any { it.present })
        assertTrue(traffic.snapshot().isNotEmpty())
        vm.disconnect()
        advanceUntilIdle()
    }
}
