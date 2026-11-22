package io.carreader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.carreader.app.logging.AppAuditLog
import io.carreader.app.logging.AppInfo
import io.carreader.app.logging.DiagnosticReport
import io.carreader.app.settings.SettingsRepository
import io.carreader.app.transport.BluetoothStatus
import io.carreader.app.transport.ConnectTarget
import io.carreader.app.transport.DeviceDiscovery
import io.carreader.app.transport.DeviceRanking
import io.carreader.app.transport.TransportProvider
import io.carreader.app.ui.confirm.DialogConfirmationPort
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.EcuReport
import io.carreader.core.guard.GuardDenied
import io.carreader.core.log.RingTrafficLog
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.PidDefinition
import io.carreader.core.session.ConnectionState
import io.carreader.core.session.VehicleSession
import io.carreader.core.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's single ViewModel: navigation, connection lifecycle, and the state of every screen. It talks to the
 * car only through [VehicleSession]; transports and sessions come from injected factories so tests can use
 * [io.carreader.app.session.PreviewVehicleSession].
 */
class MainViewModel(
    private val transports: TransportProvider,
    private val sessionFactory: (Transport) -> VehicleSession,
    private val settings: SettingsRepository,
    val confirmation: DialogConfirmationPort,
    private val discovery: DeviceDiscovery,
    private val traffic: RingTrafficLog,
    private val audit: AppAuditLog,
    private val appInfo: AppInfo,
    /** Outlives the ViewModel, so the adapter is released even when the screen is gone. */
    private val cleanupScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(
        UiState(
            mutationsEnabled = settings.mutationsEnabled,
            discovery = DiscoveryState(wifiHost = settings.wifiHost, wifiPort = settings.wifiPort.toString()),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: VehicleSession? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var liveJob: Job? = null
    private var scanJob: Job? = null
    private var bleScanJob: Job? = null
    private var busyJob: Job? = null

    // ---------------------------------------------------------------- navigation

    fun navigate(screen: Screen) {
        when (screen) {
            Screen.LiveData -> openLiveData()
            Screen.FaultCodes -> if (_state.value.faults.dtcs.isEmpty()) refreshFaults()
            Screen.Readiness -> refreshReadiness()
            Screen.Modules -> if (!_state.value.scan.started) startScan()
            is Screen.EcuDetail -> openEcu(screen.code)
            else -> Unit
        }
        _state.update { it.copy(backStack = it.backStack + screen) }
    }

    /** Returns false when there is nothing to go back to (the activity should finish). */
    fun back(): Boolean {
        val current = _state.value
        if (current.backStack.size <= 1) {
            if (current.screen == Screen.Vehicle) {
                disconnect()
                return true
            }
            return false
        }
        leaving(current.screen)
        _state.update { it.copy(backStack = it.backStack.dropLast(1)) }
        return true
    }

    private fun leaving(screen: Screen) {
        if (screen == Screen.LiveData) stopLive()
        if (screen is Screen.EcuDetail) _state.update { it.copy(ecuDetail = null) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- discovery

    fun refreshDevices() {
        val status = runCatching { discovery.status() }.getOrDefault(BluetoothStatus.UNSUPPORTED)
        val paired = if (status == BluetoothStatus.READY) runCatching { DeviceRanking.rank(discovery.pairedDevices()) }.getOrDefault(emptyList()) else emptyList()
        _state.update { it.copy(discovery = it.discovery.copy(bluetooth = status, paired = paired)) }
    }

    fun startBleScan(durationMs: Long = 12_000) {
        if (bleScanJob?.isActive == true) return
        _state.update { it.copy(discovery = it.discovery.copy(scanning = true, scanError = null, scanned = emptyList())) }
        bleScanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(durationMs) {
                    discovery.scanLe().collect { found ->
                        _state.update { s ->
                            val pairedAddresses = s.discovery.paired.map { it.address }.toSet()
                            if (found.address in pairedAddresses) return@update s
                            val merged = (s.discovery.scanned.filterNot { it.address == found.address && found.name == null } + found)
                                .groupBy { it.address }
                                .map { (_, entries) -> entries.firstOrNull { it.name != null } ?: entries.first() }
                            s.copy(discovery = s.discovery.copy(scanned = DeviceRanking.rank(merged)))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(discovery = it.discovery.copy(scanError = e.message ?: e.javaClass.simpleName)) }
            } finally {
                _state.update { it.copy(discovery = it.discovery.copy(scanning = false)) }
            }
        }
    }

    fun stopBleScan() {
        bleScanJob?.cancel()
    }

    fun setWifiHost(host: String) = _state.update { it.copy(discovery = it.discovery.copy(wifiHost = host.trim())) }
    fun setWifiPort(port: String) = _state.update { it.copy(discovery = it.discovery.copy(wifiPort = port.filter(Char::isDigit).take(5))) }

    /** The Wi-Fi target from the edited fields, or null when they are not valid. */
    fun wifiTarget(): ConnectTarget.Wifi? {
        val d = _state.value.discovery
        val port = d.wifiPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (d.wifiHost.isBlank()) return null
        return ConnectTarget.Wifi(d.wifiHost, port)
    }

    // ---------------------------------------------------------------- connection

    fun connect(target: ConnectTarget) {
        if (connectJob?.isActive == true) return
        stopBleScan()
        if (target is ConnectTarget.Wifi) {
            settings.wifiHost = target.host
            settings.wifiPort = target.port
        }
        traffic.clear()
        _state.update {
            it.copy(
                connection = ConnectionState.Connecting(""),
                target = target,
                transportDescription = null,
                summary = Loadable.Idle,
                live = LiveState(),
                faults = FaultsState(),
                readiness = Loadable.Idle,
                scan = ScanState(),
                ecuDetail = null,
            )
        }
        connectJob = viewModelScope.launch {
            val transport = try {
                transports.create(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(connection = ConnectionState.Failed(describe(e))) }
                return@launch
            }
            val s = sessionFactory(transport)
            session = s
            _state.update { it.copy(transportDescription = transport.description) }
            stateJob?.cancel()
            stateJob = launch { s.state.collect { st -> _state.update { it.copy(connection = st) } } }
            val result = try {
                withContext(io) { s.connect() }
            } catch (e: CancellationException) {
                releaseSession(s)
                throw e
            } catch (e: Throwable) {
                ConnectionState.Failed(describe(e))
            }
            if (result is ConnectionState.Connected) {
                _state.update { it.copy(connection = result, backStack = listOf(Screen.Vehicle)) }
                loadSummary()
            } else {
                stateJob?.cancel()
                releaseSession(s)
                val failed = result as? ConnectionState.Failed ?: ConnectionState.Failed("Connection failed")
                _state.update { it.copy(connection = failed) }
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    fun disconnect() {
        connectJob?.cancel()
        stopLive()
        scanJob?.cancel()
        busyJob?.cancel()
        stateJob?.cancel()
        session?.let(::releaseSession)
        session = null
        _state.update {
            it.copy(
                connection = ConnectionState.Disconnected,
                backStack = listOf(Screen.Connect),
                summary = Loadable.Idle,
                live = LiveState(),
                faults = FaultsState(),
                readiness = Loadable.Idle,
                scan = ScanState(),
                ecuDetail = null,
            )
        }
    }

    private fun releaseSession(s: VehicleSession) {
        if (session === s) session = null
        cleanupScope.launch { runCatching { s.disconnect() } }
    }

    override fun onCleared() {
        stopLive()
        session?.let(::releaseSession)
        session = null
    }

    fun loadSummary() {
        val s = session ?: return
        _state.update { it.copy(summary = Loadable.Loading) }
        viewModelScope.launch {
            val result = load { withContext(io) { s.identify() } }
            _state.update { it.copy(summary = result) }
        }
    }

    // ---------------------------------------------------------------- live data

    private fun openLiveData() {
        val s = session ?: return
        val live = _state.value.live
        if (live.available is Loadable.Ready) {
            if (live.selected.isNotEmpty()) startLive()
            return
        }
        _state.update { it.copy(live = it.live.copy(available = Loadable.Loading)) }
        viewModelScope.launch {
            val result = load { withContext(io) { s.supportedPids() } }
            val defaults = (result as? Loadable.Ready)?.value?.let(::defaultSelection).orEmpty()
            _state.update { it.copy(live = it.live.copy(available = result, selected = defaults)) }
            if (defaults.isNotEmpty() && _state.value.screen == Screen.LiveData) startLive()
        }
    }

    private fun defaultSelection(pids: List<PidDefinition>): List<String> {
        val byPid = pids.associateBy { it.pid }
        val preferred = DEFAULT_PIDS.mapNotNull { byPid[it]?.key }
        return (preferred.ifEmpty { pids.map { it.key } }).take(MAX_DEFAULT_SELECTION)
    }

    fun toggleLivePid(key: String) {
        val wasRunning = _state.value.live.running
        _state.update {
            val selected = if (key in it.live.selected) it.live.selected - key else it.live.selected + key
            it.copy(live = it.live.copy(selected = selected, values = it.live.values.filterValues { v -> v.key in selected }))
        }
        if (wasRunning) {
            stopLive()
            if (_state.value.live.selected.isNotEmpty()) startLive()
        }
    }

    fun startLive() {
        val s = session ?: return
        val live = _state.value.live
        val pids = live.available.valueOrNull.orEmpty().filter { it.key in live.selected }
        if (pids.isEmpty()) return
        liveJob?.cancel()
        _state.update { it.copy(live = it.live.copy(running = true, error = null)) }
        liveJob = viewModelScope.launch {
            s.liveData(pids)
                .flowOn(io)
                .catch { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(live = it.live.copy(running = false, error = describe(e))) }
                }
                .collect { values ->
                    _state.update { st ->
                        val merged = st.live.values + values.associateBy { v -> "${v.key}@${v.ecu.orEmpty()}" }
                        st.copy(live = st.live.copy(values = merged))
                    }
                }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        _state.update { it.copy(live = it.live.copy(running = false)) }
    }

    // ---------------------------------------------------------------- fault codes

    fun refreshFaults() {
        val s = session ?: return
        val sources = listOf(DtcSource.STORED, DtcSource.PENDING, DtcSource.PERMANENT)
        _state.update { st ->
            st.copy(faults = st.faults.copy(dtcs = sources.associateWith { Loadable.Loading }, freezeFrame = Loadable.Loading))
        }
        viewModelScope.launch {
            for (source in sources) {
                val result = load { withContext(io) { s.readDtcs(source) } }
                _state.update { st -> st.copy(faults = st.faults.copy(dtcs = st.faults.dtcs + (source to result))) }
            }
            val frame = load { withContext(io) { s.freezeFrame() } }
            _state.update { st -> st.copy(faults = st.faults.copy(freezeFrame = frame)) }
        }
    }

    fun clearEmissionDtcs() {
        val s = session ?: return
        if (busyJob?.isActive == true) return
        _state.update { it.copy(faults = it.faults.copy(clearing = true)) }
        busyJob = viewModelScope.launch {
            val message = guarded { withContext(io) { s.clearEmissionDtcs() } }
                ?.let { ok -> if (ok) UiMessage.EmissionCodesCleared else UiMessage.ClearFailed }
            _state.update { it.copy(faults = it.faults.copy(clearing = false), message = message ?: it.message) }
            if (message == UiMessage.EmissionCodesCleared) {
                refreshFaults()
                if (_state.value.readiness !is Loadable.Idle) refreshReadiness()
            }
        }
    }

    /** Runs a guarded call; on refusal or error sets the message and returns null. */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GuardDenied) {
        _state.update { it.copy(message = UiMessage.GuardBlocked(e.reasons, disabledInSettings = !settings.mutationsEnabled)) }
        null
    } catch (e: Throwable) {
        _state.update { it.copy(message = UiMessage.Error(describe(e))) }
        null
    }

    // ---------------------------------------------------------------- readiness

    fun refreshReadiness() {
        val s = session ?: return
        _state.update { it.copy(readiness = Loadable.Loading) }
        viewModelScope.launch {
            val result = load { withContext(io) { s.readiness() } }
            _state.update { it.copy(readiness = result) }
        }
    }

    // ---------------------------------------------------------------- module scan

    fun startScan() {
        val s = session ?: return
        if (scanJob?.isActive == true) return
        val summary = _state.value.summary.valueOrNull
        val expected = summary?.brand?.let { brand -> runCatching { brand.ecus(summary.context).size }.getOrDefault(0) } ?: 0
        _state.update { it.copy(scan = ScanState(running = true, started = true, expected = expected)) }
        scanJob = viewModelScope.launch {
            try {
                s.scanEcus().flowOn(io).collect { report ->
                    _state.update { st ->
                        val others = st.scan.reports.filterNot { it.ecu.code == report.ecu.code }
                        st.copy(scan = st.scan.copy(reports = others + report))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(scan = it.scan.copy(error = describe(e))) }
            } finally {
                val reports = _state.value.scan.reports
                val brandNotes = runCatching { s.scanNotes(reports) }.getOrDefault(emptyList())
                _state.update { st ->
                    val local = scanNotes(st).let { notes ->
                        // The brand module's own explanation wins over the app's TP2.0 guess.
                        if (brandNotes.isNotEmpty()) notes - ScanNote.PROBABLY_TP20 else notes
                    }
                    st.copy(scan = st.scan.copy(running = false, notes = local, brandNotes = brandNotes))
                }
            }
        }
    }

    private fun scanNotes(st: UiState): List<ScanNote> {
        val notes = mutableListOf<ScanNote>()
        val summary = st.summary.valueOrNull
        val protocol = st.connected?.protocol
        if (summary != null && summary.brand == null) notes += ScanNote.NO_BRAND_MODULE
        if (protocol != null && !protocol.isCan) notes += ScanNote.NOT_CAN
        val reports = st.scan.reports
        val present = reports.filter { it.present }
        if (reports.isNotEmpty() && present.isEmpty()) notes += ScanNote.NOTHING_FOUND
        // VAG cars before MQB answer UDS only from the engine/gearbox; the rest sits behind VW TP2.0.
        val obdRangeOnly = present.isNotEmpty() && present.all { it.ecu.address.requestId in OBD_PHYSICAL_RANGE }
        if (summary?.context?.manufacturer?.groupId == "vag" && reports.size >= 3 && obdRangeOnly) notes += ScanNote.PROBABLY_TP20
        return notes
    }

    fun report(code: String): EcuReport? = _state.value.scan.reports.firstOrNull { it.ecu.code == code }

    // ---------------------------------------------------------------- ECU detail

    private fun openEcu(code: String) {
        val s = session ?: return
        val ecu = report(code)?.ecu ?: return
        val items = runCatching { s.brandDataItems(ecu) }.getOrDefault(emptyList())
        _state.update { it.copy(ecuDetail = EcuDetailState(code = code, items = items)) }
        if (items.isNotEmpty()) refreshEcuData()
    }

    fun refreshEcuData() {
        val s = session ?: return
        val detail = _state.value.ecuDetail ?: return
        val ecu = report(detail.code)?.ecu ?: return
        _state.update { st -> st.copy(ecuDetail = st.ecuDetail?.copy(values = Loadable.Loading)) }
        viewModelScope.launch {
            val result = load { withContext(io) { s.readBrandData(ecu, detail.items) } }
            _state.update { st -> st.copy(ecuDetail = st.ecuDetail?.takeIf { it.code == detail.code }?.copy(values = result) ?: st.ecuDetail) }
        }
    }

    fun clearEcuDtcs() {
        val s = session ?: return
        val detail = _state.value.ecuDetail ?: return
        val ecu: EcuDescriptor = report(detail.code)?.ecu ?: return
        if (busyJob?.isActive == true) return
        _state.update { st -> st.copy(ecuDetail = st.ecuDetail?.copy(clearing = true)) }
        busyJob = viewModelScope.launch {
            val done = guarded { withContext(io) { s.clearEcuDtcs(ecu) } } != null
            _state.update { st ->
                st.copy(
                    ecuDetail = st.ecuDetail?.copy(clearing = false, cleared = st.ecuDetail.cleared || done),
                    message = if (done) UiMessage.EcuCodesCleared else st.message,
                )
            }
        }
    }

    // ---------------------------------------------------------------- settings

    fun setMutationsEnabled(enabled: Boolean) {
        settings.mutationsEnabled = enabled
        _state.update { it.copy(mutationsEnabled = enabled) }
    }

    // ---------------------------------------------------------------- diagnostics log

    fun trafficLines(max: Int = 400): List<String> = traffic.snapshot().takeLast(max).map(DiagnosticReport::formatEntry)

    fun auditLines(): List<String> = audit.persistedLines()

    fun clearTraffic() = traffic.clear()

    fun buildReport(): String {
        val st = _state.value
        val summary = st.summary.valueOrNull
        return DiagnosticReport.build(
            app = appInfo,
            nowMs = clock(),
            transport = st.transportDescription,
            adapter = st.connected?.adapter,
            protocol = st.connected?.protocol,
            vin = summary?.identification?.vin,
            manufacturer = summary?.context?.manufacturer?.let { "${it.brand} (${it.name})" },
            brandModule = summary?.brand?.displayName,
            audit = audit.persistedLines(),
            traffic = traffic.snapshot(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun <T> load(block: suspend () -> T): Loadable<T> = try {
        Loadable.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Loadable.Failed(describe(e))
    }

    private fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "Bluetooth permission missing: allow it in the app settings"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    companion object {
        /** RPM, speed, coolant, load, intake pressure, intake temp, module voltage, fuel level. */
        val DEFAULT_PIDS = listOf(0x0C, 0x0D, 0x05, 0x04, 0x0B, 0x0F, 0x42, 0x2F)
        const val MAX_DEFAULT_SELECTION = 6
        private val OBD_PHYSICAL_RANGE = 0x7E0..0x7E7
    }
}
