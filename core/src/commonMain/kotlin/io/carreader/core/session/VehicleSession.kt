package io.carreader.core.session

import io.carreader.core.brand.BrandModule
import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.EcuReport
import io.carreader.core.brand.VehicleContext
import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.FreezeFrame
import io.carreader.core.obd2.LiveValue
import io.carreader.core.obd2.PidDefinition
import io.carreader.core.obd2.ReadinessReport
import io.carreader.core.obd2.VehicleIdentification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val step: String) : ConnectionState
    data class Connected(val adapter: AdapterInfo, val protocol: BusProtocol?) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/** Summary shown on the home screen after connect. */
data class VehicleSummary(
    val identification: VehicleIdentification,
    val context: VehicleContext,
    val brand: BrandModule?,
    val batteryVoltage: Double?,
)

/**
 * Application facade: the only API the UI uses. It composes the link, the OBD-II port, the UDS client,
 * the brand registry and the WriteGuard. One instance per connection.
 */
interface VehicleSession {
    val state: StateFlow<ConnectionState>

    suspend fun connect(): ConnectionState

    suspend fun disconnect()

    /** VIN, brand detection, battery voltage. */
    suspend fun identify(): VehicleSummary

    // Phase 1: standard OBD-II
    suspend fun supportedPids(): List<PidDefinition>
    fun liveData(pids: List<PidDefinition>, periodMs: Long = 500): Flow<List<LiveValue>>
    suspend fun readDtcs(source: DtcSource): List<Dtc>
    suspend fun readAllEmissionDtcs(): List<Dtc>
    suspend fun readiness(): ReadinessReport?
    suspend fun freezeFrame(): FreezeFrame?
    /** Guarded. Throws GuardDenied when blocked. */
    suspend fun clearEmissionDtcs(): Boolean

    // Phase 2: brand modules
    /** Probes every ECU the brand knows, emitting a report per ECU as it completes. */
    fun scanEcus(): Flow<EcuReport>
    fun brandDataItems(ecu: EcuDescriptor): List<DataItem>
    suspend fun readBrandData(ecu: EcuDescriptor, items: List<DataItem>): List<LiveValue>
    /** Guarded. Throws GuardDenied when blocked. */
    suspend fun clearEcuDtcs(ecu: EcuDescriptor)
}
