package io.carreader.app.ui

import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.transport.DeviceEntry
import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuReport
import io.carreader.core.mileage.CounterReading
import io.carreader.core.mileage.MileageCheck
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.FreezeFrame
import io.carreader.core.obd2.LiveValue
import io.carreader.core.obd2.PidDefinition
import io.carreader.core.obd2.ReadinessReport
import io.carreader.core.session.ConnectionState
import io.carreader.core.session.VehicleSummary

/** Screens of the single activity. */
sealed interface Screen {
    data object Connect : Screen
    data object Vehicle : Screen
    data object LiveData : Screen
    data object FaultCodes : Screen
    data object Readiness : Screen
    data object Modules : Screen
    data object Mileage : Screen
    data class EcuDetail(val code: String) : Screen
    data object Settings : Screen
    data object Log : Screen
    data object About : Screen
}

/** A value that is loaded asynchronously from the car. */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}

data class DiscoveryState(
    val bluetooth: BluetoothStatus = BluetoothStatus.UNSUPPORTED,
    val paired: List<DeviceEntry> = emptyList(),
    val scanned: List<DeviceEntry> = emptyList(),
    val scanning: Boolean = false,
    val scanError: String? = null,
    val wifiHost: String = "",
    val wifiPort: String = "",
)

data class LiveState(
    val available: Loadable<List<PidDefinition>> = Loadable.Idle,
    /** Selected PID keys, in selection order. */
    val selected: List<String> = emptyList(),
    /** Latest values keyed by "key@ecu". */
    val values: Map<String, LiveValue> = emptyMap(),
    val running: Boolean = false,
    val error: String? = null,
)

data class FaultsState(
    val dtcs: Map<DtcSource, Loadable<List<Dtc>>> = emptyMap(),
    val freezeFrame: Loadable<FreezeFrame?> = Loadable.Idle,
    val clearing: Boolean = false,
)

/** Notes shown above the module scan results. Mapped to localized text by the UI. */
enum class ScanNote { NO_BRAND_MODULE, PROBABLY_TP20, NOT_CAN, NOTHING_FOUND }

data class ScanState(
    val running: Boolean = false,
    val started: Boolean = false,
    val expected: Int = 0,
    val reports: List<EcuReport> = emptyList(),
    val error: String? = null,
    val notes: List<ScanNote> = emptyList(),
    /** Notes from the session's brand module about the finished scan (English text from the core). */
    val brandNotes: List<String> = emptyList(),
)

data class EcuDetailState(
    val code: String,
    val items: List<DataItem> = emptyList(),
    val values: Loadable<List<LiveValue>> = Loadable.Idle,
    val clearing: Boolean = false,
    val cleared: Boolean = false,
)

/** Mileage check: readings arrive one by one; [check] is the comparison once every counter was read. */
data class MileageState(
    val running: Boolean = false,
    val started: Boolean = false,
    val expected: Int = 0,
    val readings: List<CounterReading> = emptyList(),
    val check: MileageCheck? = null,
    val error: String? = null,
)

/** One-shot messages shown as a dialog. */
sealed interface UiMessage {
    /** The guard refused. [disabledInSettings] offers a shortcut to Settings. */
    data class GuardBlocked(val reasons: List<String>, val disabledInSettings: Boolean) : UiMessage
    data object EmissionCodesCleared : UiMessage
    data object EcuCodesCleared : UiMessage
    data object ClearFailed : UiMessage
    data class Error(val text: String) : UiMessage
}

data class UiState(
    val backStack: List<Screen> = listOf(Screen.Connect),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val target: ConnectTarget? = null,
    val transportDescription: String? = null,
    val discovery: DiscoveryState = DiscoveryState(),
    val summary: Loadable<VehicleSummary> = Loadable.Idle,
    val live: LiveState = LiveState(),
    val faults: FaultsState = FaultsState(),
    val readiness: Loadable<ReadinessReport?> = Loadable.Idle,
    val scan: ScanState = ScanState(),
    val ecuDetail: EcuDetailState? = null,
    val mileage: MileageState = MileageState(),
    val mutationsEnabled: Boolean = false,
    val message: UiMessage? = null,
) {
    val screen: Screen get() = backStack.last()
    val isConnected: Boolean get() = connection is ConnectionState.Connected
    val connected: ConnectionState.Connected? get() = connection as? ConnectionState.Connected
    /** The session ended under a car screen (adapter lost, car switched off): offer reconnecting. */
    val connectionLost: Boolean get() = connection is ConnectionState.Failed || connection is ConnectionState.Disconnected
}
