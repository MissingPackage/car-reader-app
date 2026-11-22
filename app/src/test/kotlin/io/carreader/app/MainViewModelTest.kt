package io.carreader.app

import io.carreader.app.logging.AppAuditLog
import io.carreader.app.logging.AppInfo
import io.carreader.app.session.PreviewVehicleSession
import io.carreader.app.settings.InMemorySettings
import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.transport.DemoCar
import io.carreader.app.transport.DeviceDiscovery
import io.carreader.app.transport.DeviceEntry
import io.carreader.app.transport.DeviceKind
import io.carreader.app.transport.TransportProvider
import io.carreader.app.ui.Loadable
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.Screen
import io.carreader.app.ui.UiMessage
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.core.guard.ClearOnlyPolicy
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.obd2.DtcSource
import io.carreader.core.session.ConnectionState
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = InMemorySettings()
    private val confirmation = DialogConfirmationPort()
    private val audit = AppAuditLog(file = null)
    private var failConnect: String? = null
    private var transportError: Throwable? = null
    private var created = 0

    private class FakeTransport : Transport {
        override val description = "Fake adapter"
        override suspend fun connect() = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun read(timeoutMs: Long) = ByteArray(0)
        override suspend fun close() = Unit
    }

    private val discovery = object : DeviceDiscovery {
        var scan: Flow<DeviceEntry> = emptyFlow()
        override fun status() = BluetoothStatus.READY
        override fun pairedDevices() = listOf(
            DeviceEntry("Headphones", "00:00:00:00:00:01", DeviceKind.CLASSIC),
            DeviceEntry("OBDII", "00:00:00:00:00:02", DeviceKind.CLASSIC),
        )
        override fun scanLe() = scan
    }

    private fun viewModel(scope: TestScope): MainViewModel = MainViewModel(
        transports = TransportProvider { transportError?.let { throw it }; FakeTransport() },
        sessionFactory = {
            created++
            PreviewVehicleSession(
                transportDescription = it.description,
                policy = ClearOnlyPolicy { settings.mutationsEnabled },
                confirmation = confirmation,
                audit = audit,
                failConnectWith = failConnect,
            )
        },
        settings = settings,
        confirmation = confirmation,
        discovery = discovery,
        traffic = RingTrafficLog(clock = { 0L }),
        audit = audit,
        appInfo = AppInfo("test", 1, "JVM", "0"),
        cleanupScope = scope,
        io = dispatcher,
        clock = { 0L },
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.connected(): MainViewModel {
        val vm = viewModel(this)
        vm.connect(ConnectTarget.BluetoothClassic("OBDII", "00:00:00:00:00:02"))
        advanceUntilIdle()
        return vm
    }

    @Test
    fun connectSuccessShowsVehicleAndSummary() = runTest(dispatcher) {
        val vm = connected()
        val s = vm.state.value
        assertTrue(s.connection is ConnectionState.Connected)
        assertEquals(listOf(Screen.Vehicle), s.backStack)
        assertEquals(PreviewVehicleSession.SAMPLE_VIN, s.summary.valueOrNull?.identification?.vin)
        assertEquals("Fake adapter", s.transportDescription)
    }

    @Test
    fun connectFailureStaysOnConnectWithReason() = runTest(dispatcher) {
        failConnect = "Adapter did not answer ATZ"
        val vm = connected()
        val s = vm.state.value
        assertEquals(ConnectionState.Failed("Adapter did not answer ATZ"), s.connection)
        assertEquals(listOf(Screen.Connect), s.backStack)
    }

    @Test
    fun transportCreationErrorIsReadable() = runTest(dispatcher) {
        transportError = TransportException("Demo not available in this build")
        val vm = viewModel(this)
        vm.connect(ConnectTarget.Demo(DemoCar.PASSAT))
        advanceUntilIdle()
        assertEquals(ConnectionState.Failed("Demo not available in this build"), vm.state.value.connection)
        assertEquals(0, created)
    }

    @Test
    fun backFromVehicleDisconnects() = runTest(dispatcher) {
        val vm = connected()
        assertTrue(vm.back())
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, vm.state.value.connection)
        assertEquals(listOf(Screen.Connect), vm.state.value.backStack)
        assertFalse("back on the connect screen exits", vm.back())
    }

    @Test
    fun liveDataStreamsSelectedPidsAndPauses() = runTest(dispatcher) {
        val vm = connected()
        vm.navigate(Screen.LiveData)
        runCurrent()
        val live = vm.state.value.live
        assertTrue(live.available is Loadable.Ready)
        assertTrue(live.selected.containsAll(listOf("rpm", "speed", "coolant")))
        advanceTimeBy(1_200)
        assertTrue(vm.state.value.live.running)
        assertNotNull(vm.state.value.live.values["rpm@7E8"])

        vm.toggleLivePid("rpm")
        advanceTimeBy(600)
        assertNull(vm.state.value.live.values["rpm@7E8"])

        vm.back() // leaving the screen stops polling
        assertFalse(vm.state.value.live.running)
        advanceUntilIdle() // would never finish if the live flow were still collected
    }

    @Test
    fun faultCodesLoadPerSource() = runTest(dispatcher) {
        val vm = connected()
        vm.navigate(Screen.FaultCodes)
        advanceUntilIdle()
        val dtcs = vm.state.value.faults.dtcs
        assertEquals(listOf("P0401"), dtcs[DtcSource.STORED]?.valueOrNull?.map { it.code })
        assertEquals(listOf("P0420"), dtcs[DtcSource.PENDING]?.valueOrNull?.map { it.code })
        assertEquals(emptyList<String>(), dtcs[DtcSource.PERMANENT]?.valueOrNull?.map { it.code })
        assertEquals("P0401", vm.state.value.faults.freezeFrame.valueOrNull?.triggerDtc)
    }

    @Test
    fun clearIsBlockedWhenSettingIsOff() = runTest(dispatcher) {
        val vm = connected()
        vm.clearEmissionDtcs()
        advanceUntilIdle()
        val msg = vm.state.value.message
        assertTrue(msg is UiMessage.GuardBlocked)
        assertTrue((msg as UiMessage.GuardBlocked).disabledInSettings)
        assertNull("no dialog when the policy refuses", confirmation.pending.value)
    }

    @Test
    fun clearAsksConfirmationAndClearsWhenAccepted() = runTest(dispatcher) {
        settings.mutationsEnabled = true
        val vm = connected()
        vm.navigate(Screen.FaultCodes)
        advanceUntilIdle()
        vm.clearEmissionDtcs()
        runCurrent()
        val pending = confirmation.pending.value
        assertNotNull("confirmation dialog shown", pending)
        assertTrue(pending!!.checks.isNotEmpty())
        confirmation.answer(true)
        advanceUntilIdle()
        assertEquals(UiMessage.EmissionCodesCleared, vm.state.value.message)
        assertEquals(emptyList<String>(), vm.state.value.faults.dtcs[DtcSource.STORED]?.valueOrNull?.map { it.code })
        assertTrue(audit.snapshot().any { it.outcome == "executed" })
    }

    @Test
    fun clearCancelledByUserIsReported() = runTest(dispatcher) {
        settings.mutationsEnabled = true
        val vm = connected()
        vm.clearEmissionDtcs()
        runCurrent()
        confirmation.answer(false)
        advanceUntilIdle()
        val msg = vm.state.value.message as UiMessage.GuardBlocked
        assertFalse(msg.disabledInSettings)
        assertNull(confirmation.pending.value)
    }

    @Test
    fun moduleScanCollectsReportsAndOpensDetail() = runTest(dispatcher) {
        val vm = connected()
        vm.navigate(Screen.Modules)
        advanceUntilIdle()
        val scan = vm.state.value.scan
        assertFalse(scan.running)
        assertEquals(PreviewVehicleSession.previewEcus.size, scan.reports.size)
        assertEquals(PreviewVehicleSession.previewEcus.size, scan.expected)
        assertTrue(scan.reports.first { it.ecu.code == "15" }.dtcs.isNotEmpty())
        assertFalse(scan.reports.first { it.ecu.code == "44" }.present)

        vm.navigate(Screen.EcuDetail("01"))
        advanceUntilIdle()
        val detail = vm.state.value.ecuDetail!!
        assertEquals(2, detail.items.size)
        assertEquals(2, detail.values.valueOrNull?.size)

        settings.mutationsEnabled = true
        vm.clearEcuDtcs()
        runCurrent()
        assertTrue(confirmation.pending.value!!.request.targetLabel.startsWith("01 Engine"))
        confirmation.answer(true)
        advanceUntilIdle()
        assertEquals(UiMessage.EcuCodesCleared, vm.state.value.message)
        assertTrue(vm.state.value.ecuDetail!!.cleared)
    }

    @Test
    fun settingsSwitchPersists() = runTest(dispatcher) {
        val vm = viewModel(this)
        assertFalse(vm.state.value.mutationsEnabled)
        vm.setMutationsEnabled(true)
        assertTrue(settings.mutationsEnabled)
        assertTrue(vm.state.value.mutationsEnabled)
    }

    @Test
    fun wifiFieldsAreValidatedAndRemembered() = runTest(dispatcher) {
        val vm = viewModel(this)
        assertEquals(ConnectTarget.Wifi("192.168.0.10", 35000), vm.wifiTarget())
        vm.setWifiPort("abc")
        assertNull(vm.wifiTarget())
        vm.setWifiHost("192.168.4.1")
        vm.setWifiPort("23")
        val target = vm.wifiTarget()!!
        vm.connect(target)
        advanceUntilIdle()
        assertEquals("192.168.4.1", settings.wifiHost)
        assertEquals(23, settings.wifiPort)
    }

    @Test
    fun pairedDevicesPutObdFirstAndBleScanMerges() = runTest(dispatcher) {
        val vm = viewModel(this)
        vm.refreshDevices()
        assertEquals("OBDII", vm.state.value.discovery.paired.first().name)
        discovery.scan = flowOf(
            DeviceEntry(null, "AA:00:00:00:00:01", DeviceKind.BLE),
            DeviceEntry("IOS-Vlink", "AA:00:00:00:00:01", DeviceKind.BLE),
            DeviceEntry("Watch", "AA:00:00:00:00:02", DeviceKind.BLE),
        )
        vm.startBleScan()
        advanceUntilIdle()
        val scanned = vm.state.value.discovery.scanned
        assertEquals(listOf("IOS-Vlink", "Watch"), scanned.map { it.name })
        assertFalse(vm.state.value.discovery.scanning)
    }

    @Test
    fun diagnosticReportHasHeader() = runTest(dispatcher) {
        val vm = connected()
        val report = vm.buildReport()
        assertTrue(report.contains("VIN: ${PreviewVehicleSession.SAMPLE_VIN}"))
        assertTrue(report.contains("Protocol: ISO 15765-4 CAN 11-bit 500k"))
        assertTrue(report.contains("Adapter: Preview adapter"))
        assertTrue(report.contains("App: test (1)"))
    }
}
