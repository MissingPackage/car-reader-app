package io.carreader.core.session

import io.carreader.core.brand.BrandModule
import io.carreader.core.brand.BrandRegistry
import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.VehicleContext
import io.carreader.core.guard.AuditLog
import io.carreader.core.guard.ConfirmationPort
import io.carreader.core.guard.GatedLink
import io.carreader.core.guard.GuardContext
import io.carreader.core.guard.GuardPolicy
import io.carreader.core.guard.MutationKind
import io.carreader.core.guard.MutationRequest
import io.carreader.core.guard.SafetyCheck
import io.carreader.core.guard.StandardChecks
import io.carreader.core.guard.WriteGuard
import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.elm.ElmLink
import io.carreader.core.log.Direction
import io.carreader.core.log.TrafficEntry
import io.carreader.core.log.TrafficLog
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.FreezeFrame
import io.carreader.core.obd2.LiveValue
import io.carreader.core.obd2.Obd2Port
import io.carreader.core.obd2.Obd2Service
import io.carreader.core.obd2.PidDefinition
import io.carreader.core.obd2.ReadinessReport
import io.carreader.core.obd2.StandardPids
import io.carreader.core.obd2.VehicleIdentification
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import io.carreader.core.link.LinkException
import io.carreader.core.uds.DefaultUdsClient
import io.carreader.core.uds.UdsClient
import io.carreader.core.vin.Vin
import io.carreader.core.vin.WmiResolver
import io.carreader.core.vin.WmiTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Default [VehicleSession]: wires transport → ELM driver → [GatedLink] → OBD-II / UDS → brand module.
 * Every upper layer receives the gated link only, so mutations can only happen inside [WriteGuard.execute].
 */
class DefaultVehicleSession(
    transport: Transport,
    private val brands: BrandRegistry,
    policy: GuardPolicy,
    confirmation: ConfirmationPort,
    audit: AuditLog,
    private val traffic: TrafficLog,
    private val clock: () -> Long,
    checks: List<SafetyCheck> = StandardChecks.all(),
    private val wmi: WmiResolver = WmiTable,
    /** Lets the UI force a brand module (e.g. VIN unreadable). */
    private val forcedBrandId: String? = null,
) : VehicleSession {

    private val rawLink: DiagnosticLink = ElmLink(transport, traffic, clock)
    private val guard = WriteGuard(policy, checks, confirmation, audit, clock)
    private val link: GatedLink = GatedLink(rawLink, guard)
    private val obd: Obd2Port = Obd2Service(link)
    private val uds: UdsClient = DefaultUdsClient(link)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var summary: VehicleSummary? = null

    private val guardContext = object : GuardContext {
        override val adapter: AdapterInfo? get() = rawLink.adapterInfo
        override val busProtocol: BusProtocol? get() = rawLink.busProtocol
        override suspend fun vehicleSpeedKmh(): Int? = readFirst(0x0D)?.toInt()
        override suspend fun engineRpm(): Int? = readFirst(0x0C)?.toInt()
        override suspend fun batteryVoltage(): Double? =
            runCatchingNonCancel { rawLink.readBatteryVoltage() } ?: readFirst(0x42)
    }

    private suspend fun readFirst(pid: Int): Double? {
        val def = StandardPids.byPid(pid) ?: return null
        return runCatchingNonCancel { obd.readPid(def).firstNotNullOfOrNull { it.value } }
    }

    override suspend fun connect(): ConnectionState {
        _state.value = ConnectionState.Connecting("Initializing adapter")
        info("connect")
        return try {
            val adapter = rawLink.initialize()
            ConnectionState.Connected(adapter, rawLink.busProtocol).also { _state.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error("connect failed: ${e.message}")
            runCatchingNonCancel { rawLink.close() }
            ConnectionState.Failed(e.message ?: e::class.simpleName ?: "Connection failed").also { _state.value = it }
        }
    }

    override suspend fun disconnect() {
        runCatchingNonCancel { rawLink.close() }
        _state.value = ConnectionState.Disconnected
        info("disconnected")
    }

    override suspend fun identify(): VehicleSummary {
        val id = runCatchingNonCancel { obd.identification() } ?: VehicleIdentification(vin = null)
        val vin = Vin.parseOrNull(id.vin)
        val manufacturer = vin?.let(wmi::resolve)
        val context = VehicleContext(vin, manufacturer, modelYear = null, canBus = rawLink.busProtocol?.isCan == true)
        val brand = forcedBrandId?.let(brands::byId) ?: brands.forVehicle(context)
        val voltage = guardContext.batteryVoltage()
        return VehicleSummary(id, context, brand, voltage).also { summary = it }
    }

    private suspend fun currentSummary(): VehicleSummary = summary ?: identify()

    override suspend fun supportedPids(): List<PidDefinition> {
        val supported = obd.supportedPids()
        return StandardPids.all.filter { it.pid in supported && it.pid != 0x01 }
    }

    override fun liveData(pids: List<PidDefinition>, periodMs: Long): Flow<List<LiveValue>> = flow {
        var emptyRounds = 0
        while (currentCoroutineContext().isActive) {
            val started = clock()
            val values = pids.flatMap { pid -> readPidOrFail(pid) }
            emptyRounds = if (values.isEmpty() && pids.isNotEmpty()) emptyRounds + 1 else 0
            if (emptyRounds >= MAX_EMPTY_ROUNDS) {
                throw LinkException("No answer from the car for $emptyRounds consecutive rounds (ignition off or adapter lost?)")
            }
            emit(values)
            val elapsed = clock() - started
            delay((periodMs - elapsed).coerceAtLeast(50))
        }
    }

    /** One PID; a lost transport fails the session (so the UI shows it) instead of freezing the last values. */
    private suspend fun readPidOrFail(pid: PidDefinition): List<LiveValue> = try {
        obd.readPid(pid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        markLost(e)
        throw e
    } catch (e: Throwable) {
        if (e.cause is TransportException) { markLost(e); throw e }
        error("${e::class.simpleName}: ${e.message}")
        emptyList()
    }

    private fun markLost(e: Throwable) {
        error("connection lost: ${e.message}")
        _state.value = ConnectionState.Failed("Connection lost: ${e.message ?: "adapter not reachable"}")
    }

    override suspend fun readDtcs(source: DtcSource): List<Dtc> = obd.readDtcs(source)

    override suspend fun readAllEmissionDtcs(): List<Dtc> =
        listOf(DtcSource.STORED, DtcSource.PENDING, DtcSource.PERMANENT).flatMap { src ->
            runCatchingNonCancel { obd.readDtcs(src) } ?: emptyList()
        }

    override suspend fun readiness(): ReadinessReport? = obd.readiness()

    override suspend fun freezeFrame(): FreezeFrame? = obd.freezeFrame(0)

    override suspend fun clearEmissionDtcs(): Boolean {
        val request = MutationRequest(
            MutationKind.CLEAR_EMISSION_DTC,
            target = EcuAddress.OBD_FUNCTIONAL,
            targetLabel = "All emission ECUs",
            description = "Clear stored, pending fault codes and freeze frames; turns off the MIL; resets readiness monitors",
        )
        return guard.execute(request, guardContext) { obd.clearEmissionDtcs() }
    }

    override fun scanEcus(): Flow<EcuReport> = flow {
        val s = currentSummary()
        val brand = s.brand ?: return@flow
        for (ecu in brand.ecus(s.context)) {
            val report = runCatchingNonCancel { brand.scan(ecu, uds) }
                ?: EcuReport(ecu, present = false, error = "Scan failed")
            emit(report)
        }
    }

    override fun scanNotes(reports: List<EcuReport>): List<String> {
        val s = summary ?: return emptyList()
        return s.brand?.scanNotes(s.context, reports) ?: emptyList()
    }

    override fun brandDataItems(ecu: EcuDescriptor): List<DataItem> {
        val s = summary ?: return emptyList()
        return s.brand?.dataItems(ecu, s.context) ?: emptyList()
    }

    override suspend fun readBrandData(ecu: EcuDescriptor, items: List<DataItem>): List<LiveValue> {
        val brand = currentSummary().brand ?: return emptyList()
        // Many signals share one DID: read each DID once per refresh.
        val cached = DidCachingUdsClient(uds)
        return items.mapNotNull { item -> runCatchingNonCancel { brand.readData(ecu, item, cached) } }
    }

    override suspend fun clearEcuDtcs(ecu: EcuDescriptor) {
        val request = MutationRequest(
            MutationKind.CLEAR_ECU_DTC,
            target = ecu.address,
            targetLabel = "${ecu.code} ${ecu.name} (${ecu.address.requestId.toString(16).uppercase()})",
            description = "Clear the fault memory of this control module",
        )
        guard.execute(request, guardContext) { uds.clearDiagnosticInformation(ecu.address) }
    }

    /** The brand module in use, for UI hints. */
    fun brand(): BrandModule? = summary?.brand

    private companion object {
        const val MAX_EMPTY_ROUNDS = 5
    }

    private fun info(text: String) = traffic.record(TrafficEntry(clock(), Direction.INFO, text))
    private fun error(text: String) = traffic.record(TrafficEntry(clock(), Direction.ERROR, text))

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        error("${e::class.simpleName}: ${e.message}")
        null
    }
}

/** Per-refresh cache of ReadDataByIdentifier answers; everything else passes through. */
internal class DidCachingUdsClient(private val inner: UdsClient) : UdsClient by inner {
    private val cache = HashMap<Pair<EcuAddress, Int>, ByteArray>()
    override suspend fun readDataByIdentifier(target: EcuAddress, did: Int): ByteArray =
        cache[target to did] ?: inner.readDataByIdentifier(target, did).also { cache[target to did] = it }
}
