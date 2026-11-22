package io.carreader.app.session

import io.carreader.core.brand.BrandModule
import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.VehicleContext
import io.carreader.core.guard.AuditLog
import io.carreader.core.guard.ConfirmationPort
import io.carreader.core.guard.GuardContext
import io.carreader.core.guard.GuardPolicy
import io.carreader.core.guard.MutationKind
import io.carreader.core.guard.MutationRequest
import io.carreader.core.guard.StandardChecks
import io.carreader.core.guard.WriteGuard
import io.carreader.core.link.AdapterChip
import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.EcuAddress
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.FreezeFrame
import io.carreader.core.obd2.LiveValue
import io.carreader.core.obd2.MonitorStatus
import io.carreader.core.obd2.PidDefinition
import io.carreader.core.obd2.ReadinessReport
import io.carreader.core.obd2.VehicleIdentification
import io.carreader.core.session.ConnectionState
import io.carreader.core.session.VehicleSession
import io.carreader.core.session.VehicleSummary
import io.carreader.core.uds.UdsClient
import io.carreader.core.vin.Manufacturer
import io.carreader.core.vin.Vin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * In-app fake [VehicleSession]: a VW Passat B8 with canned data. Used by Compose previews, JVM tests, and as the
 * stand-in until the real session is wired in [SessionFactory]. Mutations run through a real [WriteGuard] so
 * the settings switch, the checks and the confirmation dialog behave as they will on a car.
 */
class PreviewVehicleSession(
    private val transportDescription: String = "Preview adapter",
    policy: GuardPolicy = GuardPolicy.Disabled,
    confirmation: ConfirmationPort = ConfirmationPort { _, _ -> false },
    audit: AuditLog = AuditLog { },
    private val clock: () -> Long = { 0L },
    private val stepDelayMs: Long = 0L,
    private val failConnectWith: String? = null,
) : VehicleSession {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val guard = WriteGuard(policy, StandardChecks.all(), confirmation, audit, clock)
    private var storedDtcs = sampleStored
    private val ecuDtcs = sampleEcuDtcs.toMutableMap()
    private var tick = 0

    val adapter = AdapterInfo(
        identity = "Preview adapter",
        firmware = "1.0",
        chip = AdapterChip.SIMULATOR,
        supportsStCommands = false,
        supportsVoltage = true,
        supportsHeaders = true,
        certified = false,
    )

    private val guardContext = object : GuardContext {
        override val adapter: AdapterInfo get() = this@PreviewVehicleSession.adapter
        override val busProtocol: BusProtocol = BusProtocol.CAN_11_500
        override suspend fun vehicleSpeedKmh(): Int = 0
        override suspend fun engineRpm(): Int = 0
        override suspend fun batteryVoltage(): Double = 12.4
    }

    override suspend fun connect(): ConnectionState {
        for (step in listOf("Opening $transportDescription", "Resetting adapter", "Searching protocol")) {
            _state.value = ConnectionState.Connecting(step)
            delay(stepDelayMs)
        }
        val result = if (failConnectWith != null) {
            ConnectionState.Failed(failConnectWith)
        } else {
            ConnectionState.Connected(adapter, BusProtocol.CAN_11_500)
        }
        _state.value = result
        return result
    }

    override suspend fun disconnect() {
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun identify(): VehicleSummary {
        delay(stepDelayMs)
        val vin = Vin.parseOrNull(SAMPLE_VIN)
        return VehicleSummary(
            identification = VehicleIdentification(vin = SAMPLE_VIN, calibrationIds = listOf("04L906026FT"), ecuNames = listOf("ECM-EngineControl")),
            context = VehicleContext(vin, Manufacturer("Volkswagen AG", "Volkswagen", "vag", "Germany"), 2018, canBus = true),
            brand = PreviewBrandModule,
            batteryVoltage = 12.4,
        )
    }

    override suspend fun supportedPids(): List<PidDefinition> = previewPids

    override fun liveData(pids: List<PidDefinition>, periodMs: Long): Flow<List<LiveValue>> = flow {
        while (true) {
            tick++
            emit(pids.map { (it as? PreviewPid)?.sample(tick) ?: it.decode(ByteArray(it.bytes)) })
            delay(periodMs)
        }
    }

    override suspend fun readDtcs(source: DtcSource): List<Dtc> = when (source) {
        DtcSource.STORED -> storedDtcs
        DtcSource.PENDING -> listOf(Dtc("P0420", DtcSource.PENDING, "Catalyst system efficiency below threshold (bank 1)", ecu = "7E8"))
        DtcSource.PERMANENT -> emptyList()
        DtcSource.ECU -> emptyList()
    }

    override suspend fun readAllEmissionDtcs(): List<Dtc> =
        readDtcs(DtcSource.STORED) + readDtcs(DtcSource.PENDING) + readDtcs(DtcSource.PERMANENT)

    override suspend fun readiness(): ReadinessReport = ReadinessReport(
        milOn = storedDtcs.isNotEmpty(),
        storedDtcCount = storedDtcs.size,
        compressionIgnition = true,
        monitors = listOf(
            MonitorStatus("Misfire", supported = true, complete = true),
            MonitorStatus("Fuel system", supported = true, complete = true),
            MonitorStatus("Comprehensive components", supported = true, complete = true),
            MonitorStatus("NMHC catalyst", supported = true, complete = false),
            MonitorStatus("NOx / SCR aftertreatment", supported = true, complete = false),
            MonitorStatus("Boost pressure", supported = true, complete = true),
            MonitorStatus("Exhaust gas sensor", supported = true, complete = true),
            MonitorStatus("Particulate filter", supported = true, complete = true),
            MonitorStatus("EGR / VVT system", supported = true, complete = true),
            MonitorStatus("Heated catalyst", supported = false, complete = false),
        ),
    )

    override suspend fun freezeFrame(): FreezeFrame? = storedDtcs.firstOrNull()?.let {
        FreezeFrame(
            frameIndex = 0,
            triggerDtc = it.code,
            values = listOf(
                LiveValue("rpm", "Engine speed", 2150.0, "rpm", "2150 rpm"),
                LiveValue("speed", "Vehicle speed", 64.0, "km/h", "64 km/h"),
                LiveValue("coolant", "Coolant temperature", 88.0, "°C", "88 °C"),
                LiveValue("load", "Calculated engine load", 41.2, "%", "41.2 %"),
            ),
        )
    }

    override suspend fun clearEmissionDtcs(): Boolean {
        val request = MutationRequest(MutationKind.CLEAR_EMISSION_DTC, EcuAddress.OBD_FUNCTIONAL, "All emission ECUs", "Clear stored OBD-II fault codes and turn off the MIL")
        return guard.execute(request, guardContext) {
            storedDtcs = emptyList()
            true
        }
    }

    override fun scanEcus(): Flow<EcuReport> = flow {
        for (ecu in previewEcus) {
            delay(stepDelayMs)
            emit(reportFor(ecu))
        }
    }

    private fun reportFor(ecu: EcuDescriptor): EcuReport = when (ecu.code) {
        "44" -> EcuReport(ecu, present = false)
        "5F" -> EcuReport(ecu, present = false, error = "No response (timeout)")
        else -> EcuReport(
            ecu = ecu,
            present = true,
            identification = listOf(
                "Part number" to "5Q0907530${ecu.code}",
                "Software version" to "0${ecu.code}2",
                "Hardware number" to "5Q0907530",
                "System name" to ecu.name.uppercase(),
            ),
            dtcs = ecuDtcs[ecu.code].orEmpty(),
        )
    }

    override fun brandDataItems(ecu: EcuDescriptor): List<DataItem> = PreviewBrandModule.dataItems(ecu, VehicleContext(null, null, canBus = true))

    override suspend fun readBrandData(ecu: EcuDescriptor, items: List<DataItem>): List<LiveValue> {
        delay(stepDelayMs)
        tick++
        return items.map { it.decode(byteArrayOf((tick % 50).toByte(), 0x10)) }
    }

    override suspend fun clearEcuDtcs(ecu: EcuDescriptor) {
        val request = MutationRequest(MutationKind.CLEAR_ECU_DTC, ecu.address, "${ecu.code} ${ecu.name} (${ecu.address.requestId.toString(16).uppercase()})", "Clear the fault memory of ${ecu.name}")
        guard.execute(request, guardContext) {
            ecuDtcs.remove(ecu.code)
        }
    }

    /** A fixed-value PID for previews; [sample] varies the value over time. */
    class PreviewPid(
        override val pid: Int,
        override val key: String,
        override val name: String,
        override val unit: String,
        private val base: Double,
        private val amplitude: Double,
    ) : PidDefinition {
        override val bytes: Int = 2
        override fun decode(data: ByteArray): LiveValue = value(base)
        fun sample(tick: Int): LiveValue = value(base + amplitude * sin(tick / 3.0 + pid))
        private fun value(v: Double): LiveValue {
            val rounded = (v * 10).roundToInt() / 10.0
            return LiveValue(key, name, rounded, unit, "$rounded $unit", ecu = "7E8")
        }
    }

    private class PreviewDataItem(
        override val key: String,
        override val name: String,
        override val unit: String,
        override val did: Int,
        private val scale: Double,
        private val offset: Double,
    ) : DataItem {
        override fun decode(data: ByteArray): LiveValue {
            val raw = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            val v = ((raw * scale + offset) * 10).roundToInt() / 10.0
            return LiveValue(key, name, v, unit, "$v $unit")
        }
    }

    object PreviewBrandModule : BrandModule {
        override val id = "preview"
        override val displayName = "Volkswagen group (preview)"
        override fun supports(vehicle: VehicleContext) = vehicle.manufacturer?.groupId == "vag"
        override fun ecus(vehicle: VehicleContext): List<EcuDescriptor> = previewEcus
        override suspend fun scan(ecu: EcuDescriptor, uds: UdsClient) = EcuReport(ecu, present = false)
        override fun dataItems(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem> = when (ecu.code) {
            "01" -> listOf(
                PreviewDataItem("dpf_soot", "DPF soot mass", "g", 0x1234, 0.5, 2.0),
                PreviewDataItem("oil_temp", "Oil temperature", "°C", 0x1235, 1.0, 60.0),
            )
            "17" -> listOf(PreviewDataItem("fuel_level", "Fuel level", "l", 0x2203, 1.0, 10.0))
            else -> emptyList()
        }
        override suspend fun readData(ecu: EcuDescriptor, item: DataItem, uds: UdsClient): LiveValue? = null
        override fun describeDtc(ecu: EcuDescriptor, code: String, failureType: Int?): String? = null
    }

    companion object {
        const val SAMPLE_VIN = "WVWZZZ3CZJE012345"

        val previewPids: List<PidDefinition> = listOf(
            PreviewPid(0x0C, "rpm", "Engine speed", "rpm", 820.0, 40.0),
            PreviewPid(0x0D, "speed", "Vehicle speed", "km/h", 0.0, 0.0),
            PreviewPid(0x05, "coolant", "Coolant temperature", "°C", 86.0, 2.0),
            PreviewPid(0x04, "load", "Calculated engine load", "%", 18.0, 3.0),
            PreviewPid(0x0B, "map", "Intake manifold pressure", "kPa", 101.0, 4.0),
            PreviewPid(0x0F, "iat", "Intake air temperature", "°C", 24.0, 1.0),
            PreviewPid(0x42, "voltage", "Control module voltage", "V", 14.1, 0.1),
        )

        private fun ecu(code: String, name: String, request: Int, response: Int) =
            EcuDescriptor(code, name, EcuAddress(request, response))

        val previewEcus: List<EcuDescriptor> = listOf(
            ecu("01", "Engine", 0x7E0, 0x7E8),
            ecu("02", "Transmission", 0x7E1, 0x7E9),
            ecu("03", "Brakes (ABS/ESC)", 0x713, 0x77D),
            ecu("15", "Airbag", 0x715, 0x77F),
            ecu("17", "Instruments", 0x714, 0x77E),
            ecu("19", "Gateway", 0x710, 0x77A),
            ecu("44", "Steering assist", 0x712, 0x77C),
            ecu("5F", "Information electronics", 0x773, 0x7DD),
        )

        private val sampleStored = listOf(
            Dtc("P0401", DtcSource.STORED, "Exhaust gas recirculation flow insufficient", ecu = "7E8"),
        )

        private val sampleEcuDtcs = mapOf(
            "01" to listOf(Dtc("P0401", DtcSource.ECU, "Exhaust gas recirculation flow insufficient", failureType = 0x00, statusByte = 0x2F, ecu = "01 Engine")),
            "15" to listOf(Dtc("B1013", DtcSource.ECU, "Passenger seat belt switch", failureType = 0x13, statusByte = 0x09, ecu = "15 Airbag")),
            "19" to listOf(Dtc("U1121", DtcSource.ECU, null, failureType = 0x00, statusByte = 0x08, ecu = "19 Gateway")),
        )
    }
}
