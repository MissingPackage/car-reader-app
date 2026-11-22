package io.carreader.core.mileage

import io.carreader.core.brand.BrandModule
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.obd2.LiveValue
import io.carreader.core.obd2.Obd2Port
import io.carreader.core.transport.TransportException
import io.carreader.core.uds.NegativeResponseException
import io.carreader.core.uds.NoResponseException
import io.carreader.core.uds.UdsClient
import io.carreader.core.uds.UdsSession
import io.carreader.core.brand.EcuProtocol
import io.carreader.core.uds.UdsTimeoutAdjustable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Reads the counters of [StandardCounters] and of the brand's [CounterCatalog] through the read-only ports.
 * Every counter yields a reading, with a [ReadingStatus] when there is no value, so the UI can say why.
 *
 * Absent control units are detected once each (TesterPresent with a short timeout), so a missing module costs
 * one probe instead of one timeout per counter. A lost adapter is not swallowed: it ends the flow.
 */
class CounterReader(
    private val obd: Obd2Port,
    private val uds: UdsClient,
    private val brand: BrandModule?,
    /** UDS client for the brand reads (e.g. with a per-refresh DID cache); defaults to [uds]. */
    private val brandUds: UdsClient = uds,
    private val probeTimeoutMs: Long = 500,
) {
    /** Brand counters first (the analyzer prefers the first value of a role, e.g. gearbox drive time), then OBD-II. */
    fun sources(vehicle: VehicleContext): List<CounterSource> =
        (brand?.counterCatalog?.sources(vehicle) ?: emptyList()) + StandardCounters.sources(vehicle)

    fun notes(vehicle: VehicleContext): List<String> = brand?.counterCatalog?.notes(vehicle) ?: emptyList()

    fun read(vehicle: VehicleContext): Flow<CounterReading> = flow {
        val sources = sources(vehicle)
        var pids: Set<Int>? = null
        val presence = HashMap<Int, Boolean>()
        for (source in sources) {
            when (val origin = source.origin) {
                is CounterOrigin.ObdPid -> {
                    val available = pids ?: (softly { obd.supportedPids() } ?: emptySet()).also { pids = it }
                    if (origin.pid.pid !in available) {
                        emit(CounterReading(source, OBD_MODULE, null, ReadingStatus.NOT_SUPPORTED))
                        continue
                    }
                    val values = softly { obd.readPid(origin.pid) } ?: emptyList()
                    if (values.isEmpty()) emit(CounterReading(source, OBD_MODULE, null, ReadingStatus.NOT_SUPPORTED))
                    values.forEach { emit(normalize(source, it.ecu?.let { ecu -> "$OBD_MODULE ($ecu)" } ?: OBD_MODULE, it)) }
                }
                is CounterOrigin.BrandData -> {
                    val module = moduleLabel(origin.ecu)
                    val brandModule = brand
                    if (brandModule == null) {
                        emit(CounterReading(source, module, null, ReadingStatus.NOT_SUPPORTED))
                        continue
                    }
                    if (origin.ecu.protocol != EcuProtocol.UDS) {
                        emit(CounterReading(source, module, null, ReadingStatus.NOT_SUPPORTED))
                        continue
                    }
                    val present = presence.getOrPut(origin.ecu.address.requestId) { answers(origin.ecu) }
                    if (!present) {
                        emit(CounterReading(source, module, null, ReadingStatus.MODULE_ABSENT))
                        continue
                    }
                    val value = brandModule.readData(origin.ecu, origin.item, brandUds)
                    emit(value?.let { normalize(source, module, it) } ?: CounterReading(source, module, null, ReadingStatus.NOT_SUPPORTED))
                }
            }
        }
        // Reads may have opened the extended session (0x10 03); hand every unit back in the default one.
        val touched = sources.mapNotNull { it.ecu }.filter { presence[it.address.requestId] == true }.distinctBy { it.address.requestId }
        for (ecu in touched) softly { uds.startSession(ecu.address, UdsSession.DEFAULT) }
    }

    /** Runs [block]; a lost adapter and cancellation propagate, any other failure gives null. */
    private suspend fun <T> softly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (e: Exception) {
        if (e.cause is TransportException) throw e
        null
    }

    /** TesterPresent with a short timeout: a negative answer still proves the unit is there. */
    private suspend fun answers(ecu: EcuDescriptor): Boolean {
        val probe = (uds as? UdsTimeoutAdjustable)?.withTimeout(probeTimeoutMs) ?: uds
        return try {
            probe.testerPresent(ecu.address)
            true
        } catch (e: NegativeResponseException) {
            true
        } catch (e: NoResponseException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            if (e.cause is TransportException) throw e
            false
        }
    }

    companion object {
        const val OBD_MODULE = "OBD-II"

        fun moduleLabel(ecu: EcuDescriptor): String = "${ecu.code} ${ecu.name}"

        /** Decoded value → km or hours, with the reason when it is not a usable reading. */
        fun normalize(source: CounterSource, module: String, value: LiveValue): CounterReading {
            val raw = value.value
            if (raw == null || raw.isNaN() || raw.isInfinite()) return CounterReading(source, module, null, ReadingStatus.NOT_RECORDED)
            val ceiling = source.ceiling
            if (ceiling != null && raw >= ceiling * (1 - 1e-12)) return CounterReading(source, module, null, ReadingStatus.OUT_OF_RANGE)
            val factor = CounterUnits.factor(source.kind, value.unit)
                ?: return CounterReading(source, module, null, ReadingStatus.UNKNOWN_UNIT)
            val normalized = raw * factor
            if (normalized <= 0.0) return CounterReading(source, module, null, ReadingStatus.NOT_RECORDED)
            return CounterReading(source, module, normalized, ReadingStatus.OK)
        }
    }
}
