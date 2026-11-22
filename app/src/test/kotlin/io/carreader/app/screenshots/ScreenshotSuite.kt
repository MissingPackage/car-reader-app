package io.carreader.app.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.ui.AppRoot
import io.carreader.app.ui.Screen
import io.carreader.app.ui.UiState
import io.carreader.app.ui.screens.FaultCodesScreen
import io.carreader.app.ui.screens.ModulesScreen
import io.carreader.brands.vag.VagBrandModule
import io.carreader.core.guard.CheckResult
import io.carreader.core.guard.MutationKind
import io.carreader.core.guard.MutationRequest
import io.carreader.core.link.EcuAddress
import io.carreader.core.log.Direction
import io.carreader.core.log.TrafficEntry
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcSource
import io.carreader.app.ui.Loadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Screenshots of the main screens in Italian, on a Pixel 5. Each screen is reached by driving the real
 * [io.carreader.app.ui.MainViewModel] against the preview Passat; a few states the preview car never produces
 * (a second stored code, a guard warning) are injected into the state or the confirmation port.
 *
 * Record with `./gradlew :app:recordPaparazziDebug`; goldens land in app/src/test/snapshots/images.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ScreenshotSuite(night: Boolean) {
    private val phone = DeviceConfig.PIXEL_5.copy(locale = "it", nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT)
    private val tallScreens = !night

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = phone,
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 0.5,
    )

    private val h = ScreenshotHarness()
    private val vm get() = h.viewModel

    @Before fun setUp() = Dispatchers.setMain(h.dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun connected() {
        vm.connect(ConnectTarget.BluetoothClassic("OBDLink MX+ 29467", "00:04:3E:9A:12:7C"))
        h.scheduler.advanceUntilIdle()
    }

    private fun shot(name: String, tall: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit = { AppRoot(vm, onExit = {}) }) {
        paparazzi.snapshot(name) { ScreenshotHost(content) }
        if (tall && tallScreens) {
            paparazzi.unsafeUpdateConfig(deviceConfig = phone.copy(screenHeight = phone.screenHeight * 2))
            paparazzi.snapshot("$name-full") { ScreenshotHost(content) }
            paparazzi.unsafeUpdateConfig(deviceConfig = phone)
        }
    }

    @Test fun connect() {
        vm.refreshDevices()
        shot("connect", tall = true)
    }

    @Test fun connectFailed() {
        vm.refreshDevices()
        // The failure card as a real adapter timeout shows it.
        val failed = vm.state.value.copy(connection = io.carreader.core.session.ConnectionState.Failed("Adapter did not answer ATZ"))
        shot("connect-failed") { io.carreader.app.ui.screens.ConnectScreen(failed, vm) }
    }

    @Test fun vehicle() {
        connected()
        shot("vehicle", tall = true)
    }

    @Test fun liveData() {
        connected()
        vm.navigate(Screen.LiveData)
        h.scheduler.runCurrent()
        h.scheduler.advanceTimeBy(5_000)
        h.scheduler.runCurrent()
        shot("live-data", tall = true)
        vm.stopLive()
    }

    @Test fun faultCodes() {
        connected()
        vm.navigate(Screen.FaultCodes)
        h.scheduler.advanceUntilIdle()
        val s = vm.state.value
        val stored = s.faults.dtcs[DtcSource.STORED]?.valueOrNull.orEmpty() +
            Dtc("P0300", DtcSource.STORED, null, ecu = "7E8")
        val withTwo: UiState = s.copy(faults = s.faults.copy(dtcs = s.faults.dtcs + (DtcSource.STORED to Loadable.Ready(stored))))
        shot("fault-codes", tall = true) { FaultCodesScreen(withTwo, vm) }
    }

    @Test fun readiness() {
        connected()
        vm.navigate(Screen.Readiness)
        h.scheduler.advanceUntilIdle()
        shot("readiness", tall = true)
    }

    @Test fun modules() {
        connected()
        vm.navigate(Screen.Modules)
        h.scheduler.advanceUntilIdle()
        val s = vm.state.value
        // The note a Passat B6/B7 shows when only the engine-side modules answer.
        val withNote = s.copy(scan = s.scan.copy(brandNotes = listOf(VagBrandModule.TP20_NOTE)))
        shot("modules", tall = true) { ModulesScreen(withNote, vm) }
    }

    @Test fun modulesScanning() {
        connected()
        vm.navigate(Screen.Modules)
        h.scheduler.advanceUntilIdle()
        val s = vm.state.value
        val partial = s.copy(scan = s.scan.copy(running = true, reports = s.scan.reports.take(3), notes = emptyList(), brandNotes = emptyList()))
        shot("modules-scanning") { ModulesScreen(partial, vm) }
    }

    @Test fun ecuDetail() {
        connected()
        vm.navigate(Screen.Modules)
        h.scheduler.advanceUntilIdle()
        vm.navigate(Screen.EcuDetail("01"))
        h.scheduler.advanceUntilIdle()
        shot("ecu-detail", tall = true)
    }

    @Test fun mileage() {
        connected()
        vm.navigate(Screen.Mileage)
        h.scheduler.advanceUntilIdle()
        shot("mileage", tall = true)
    }

    @Test fun settings() {
        connected()
        vm.navigate(Screen.Settings)
        shot("settings")
    }

    @Test fun guardConfirmation() {
        connected()
        vm.navigate(Screen.FaultCodes)
        h.scheduler.advanceUntilIdle()
        val request = MutationRequest(
            MutationKind.CLEAR_EMISSION_DTC,
            EcuAddress.OBD_FUNCTIONAL,
            "All emission ECUs",
            "Clear stored, pending fault codes and freeze frames; turns off the MIL; resets readiness monitors",
        )
        val checks = listOf(
            "Vehicle stationary" to CheckResult.Pass,
            "Engine off" to CheckResult.Warn("Engine speed unavailable: make sure the engine is off"),
            "Battery voltage ≥ 12.0 V" to CheckResult.Pass,
            "Adapter suitable" to CheckResult.Pass,
        )
        h.scope.launch { h.confirmation.confirm(request, checks) }
        h.scheduler.runCurrent()
        shot("guard-confirmation")
        h.confirmation.answer(false)
        h.scheduler.advanceUntilIdle()
    }

    @Test fun diagnosticsLog() {
        connected()
        // One audited, confirmed clear so the audit section is not empty.
        h.settings.mutationsEnabled = true
        vm.navigate(Screen.FaultCodes)
        h.scheduler.advanceUntilIdle()
        vm.clearEmissionDtcs()
        h.scheduler.runCurrent()
        h.confirmation.answer(true)
        h.scheduler.advanceUntilIdle()
        vm.dismissMessage()
        h.settings.mutationsEnabled = false
        listOf(
            Direction.INFO to "Opening OBDLink MX+ · Bluetooth",
            Direction.TX to "ATZ",
            Direction.RX to "ELM327 v1.4b",
            Direction.TX to "STI",
            Direction.RX to "STN2255 v5.10.3",
            Direction.TX to "ATSP0",
            Direction.RX to "OK",
            Direction.TX to "0100",
            Direction.RX to "7E8 06 41 00 BE 3F A8 13",
            Direction.TX to "0902",
            Direction.RX to "7E8 10 14 49 02 01 57 56 57",
            Direction.ERROR to "Timeout waiting for 7DD (5F Information electronics)",
        ).forEachIndexed { i, (dir, text) -> h.traffic.record(TrafficEntry(ScreenshotHarness.FIXED_TIME + i * 140L, dir, text)) }
        vm.navigate(Screen.Log)
        shot("diagnostics-log", tall = true)
    }

    @Test fun about() {
        vm.navigate(Screen.About)
        shot("about", tall = true)
    }
}

class ItalianLightScreenshots : ScreenshotSuite(night = false)

class ItalianDarkScreenshots : ScreenshotSuite(night = true)
