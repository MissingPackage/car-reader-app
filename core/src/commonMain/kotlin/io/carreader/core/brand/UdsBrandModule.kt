package io.carreader.core.brand

import io.carreader.core.link.EcuAddress
import io.carreader.core.obd2.Dtc
import io.carreader.core.obd2.DtcCodec
import io.carreader.core.obd2.DtcDescriptions
import io.carreader.core.obd2.DtcSource
import io.carreader.core.obd2.LiveValue
import io.carreader.core.uds.FailureTypes
import io.carreader.core.uds.NegativeResponseException
import io.carreader.core.uds.NoResponseException
import io.carreader.core.uds.UdsClient
import io.carreader.core.uds.UdsDtcRecord
import io.carreader.core.uds.UdsSession
import io.carreader.core.uds.UdsTimeoutAdjustable
import io.carreader.core.util.toHex
import kotlinx.coroutines.CancellationException

/** Generic (SAE) DTC text source, so the brand base does not hard-wire one table. */
fun interface GenericDtcText {
    fun describe(code: String): String?

    companion object {
        val None = GenericDtcText { null }

        /** Generic SAE J2012 texts from [DtcDescriptions]. */
        val Sae = GenericDtcText { DtcDescriptions.describe(it) }
    }
}

/** How an identification DID is shown. */
enum class IdFormat { ASCII, HEX }

/** One identification value read on scan, e.g. "Part number" ← F187. */
data class IdentificationDid(val label: String, val did: Int, val format: IdFormat = IdFormat.ASCII)

/**
 * Reusable base for brands whose control units speak UDS (ISO 14229) on CAN. A brand supplies tables only:
 * [ecuCatalog], [identificationDids], [dataCatalog] and optionally [describeManufacturerDtc]; the scan,
 * identification, DTC conversion and data reading algorithms live here.
 *
 * Reads work in the default session; the extended session (0x10 03) is opened only when the ECU refuses a
 * read with "not supported in active session" / "out of range", or when a [ScaledDataItem] requires it.
 */
abstract class UdsBrandModule(
    private val genericText: GenericDtcText = GenericDtcText.Sae,
) : BrandModule {

    protected abstract fun ecuCatalog(vehicle: VehicleContext): List<EcuDescriptor>

    protected abstract val identificationDids: List<IdentificationDid>

    protected abstract fun dataCatalog(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem>

    protected open fun describeManufacturerDtc(ecu: EcuDescriptor, code: String, failureType: Int?): String? = null

    /** Status masks tried in order for 0x19 0x02; the next one is tried when an ECU rejects the mask. */
    protected open fun dtcStatusMasks(ecu: EcuDescriptor): List<Int> = listOf(0xFF, 0x2F, 0x0C)

    /** DID read to detect presence; its value is reused for identification when it is in the list. */
    protected open val probeDid: Int = 0xF187

    /** Short timeout for the presence probe, so absent ECUs cost little. */
    protected open val probeTimeoutMs: Long = 500

    /** Text attached to an ECU that did not answer (e.g. "this car may use another protocol"). */
    protected open fun absentNote(ecu: EcuDescriptor): String? = null

    /**
     * Hook for observations across a whole scan (e.g. "only the engine answered: the car probably uses a
     * protocol this adapter cannot reach"). The session or UI calls it with every report of the scan.
     */
    open fun scanNotes(vehicle: VehicleContext, reports: List<EcuReport>): List<String> = emptyList()

    final override fun ecus(vehicle: VehicleContext): List<EcuDescriptor> = ecuCatalog(vehicle)

    final override fun dataItems(ecu: EcuDescriptor, vehicle: VehicleContext): List<DataItem> = dataCatalog(ecu, vehicle)

    override fun describeDtc(ecu: EcuDescriptor, code: String, failureType: Int?): String? {
        val base = describeManufacturerDtc(ecu, code, failureType) ?: genericText.describe(code)
        val ft = failureType?.takeIf { it != 0 }?.let(FailureTypes::describe)
        return listOfNotNull(base, ft).joinToString(" — ").ifEmpty { null }
    }

    override suspend fun scan(ecu: EcuDescriptor, uds: UdsClient): EcuReport {
        if (ecu.protocol != EcuProtocol.UDS) {
            return EcuReport(ecu, present = false, error = "${ecu.protocol} control units are not supported yet")
        }
        val address = ecu.address
        val probe = (uds as? UdsTimeoutAdjustable)?.withTimeout(probeTimeoutMs) ?: uds
        val probed: ByteArray? = try {
            probe.readDataByIdentifier(address, probeDid)
        } catch (e: NegativeResponseException) {
            null // it answered, so it is there
        } catch (e: NoResponseException) {
            if (!answersTesterPresent(probe, address)) return EcuReport(ecu, present = false, error = absentNote(ecu))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return EcuReport(ecu, present = false, error = "Probe failed: ${e.message}")
        }

        val state = ScanState(uds, address)
        val errors = mutableListOf<String>()
        val identification = try {
            readIdentification(state, probed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "Identification failed: ${e.message}"
            emptyList()
        }
        val dtcs = try {
            readDtcs(ecu, state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "DTC read failed: ${e.message}"
            emptyList()
        }
        if (state.extended) {
            try { uds.startSession(address, UdsSession.DEFAULT) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        return EcuReport(ecu, present = true, identification = identification, dtcs = dtcs, error = errors.joinToString("; ").ifEmpty { null })
    }

    override suspend fun readData(ecu: EcuDescriptor, item: DataItem, uds: UdsClient): LiveValue? {
        val state = ScanState(uds, ecu.address)
        if ((item as? ScaledDataItem)?.requiredSession == UdsSession.EXTENDED.id) state.enterExtended()
        return try {
            val data = state.withSessionFallback { uds.readDataByIdentifier(ecu.address, item.did) }
            item.decode(data).copy(ecu = "${ecu.code} ${ecu.name}")
        } catch (e: NegativeResponseException) {
            null
        } catch (e: NoResponseException) {
            null
        } catch (e: IllegalArgumentException) {
            null // answer shorter than the signal layout: wrong variant for this car
        }
    }

    private suspend fun answersTesterPresent(uds: UdsClient, address: EcuAddress): Boolean = try {
        uds.testerPresent(address)
        true
    } catch (e: NegativeResponseException) {
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun readIdentification(state: ScanState, probed: ByteArray?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (id in identificationDids) {
            val data = if (id.did == probeDid && probed != null) probed else try {
                state.withSessionFallback { state.uds.readDataByIdentifier(state.address, id.did) }
            } catch (e: NegativeResponseException) {
                null
            } catch (e: NoResponseException) {
                null
            }
            val text = data?.let { decodeIdentification(it, id.format) }
            if (!text.isNullOrEmpty()) out += id.label to text
        }
        return out
    }

    private suspend fun readDtcs(ecu: EcuDescriptor, state: ScanState): List<Dtc> {
        val masks = dtcStatusMasks(ecu).ifEmpty { listOf(0xFF) }
        var lastError: NegativeResponseException? = null
        for (mask in masks) {
            val records: List<UdsDtcRecord> = try {
                state.withSessionFallback { state.uds.readDtcsByStatus(state.address, mask) }
            } catch (e: NegativeResponseException) {
                if (e.nrc == NRC_OUT_OF_RANGE || e.nrc == NRC_INVALID_FORMAT) { lastError = e; continue }
                throw e
            }
            return records.filter { it.status and mask != 0 }.map { toDtc(ecu, it) }
        }
        throw lastError ?: IllegalStateException("No status mask accepted")
    }

    private fun toDtc(ecu: EcuDescriptor, record: UdsDtcRecord): Dtc {
        val code = DtcCodec.formatUds(record.dtc)
        return Dtc(
            code = code,
            source = DtcSource.ECU,
            description = describeDtc(ecu, code, record.failureType),
            failureType = record.failureType,
            statusByte = record.status,
            ecu = "${ecu.code} ${ecu.name}",
        )
    }

    /** Session bookkeeping for one ECU: switches to the extended session at most once, on refusal. */
    private class ScanState(val uds: UdsClient, val address: EcuAddress) {
        var extended = false
            private set
        private var extendedFailed = false

        suspend fun enterExtended(): Boolean {
            if (extended) return true
            if (extendedFailed) return false
            return try {
                uds.startSession(address, UdsSession.EXTENDED)
                extended = true
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                extendedFailed = true
                false
            }
        }

        suspend fun <T> withSessionFallback(block: suspend () -> T): T = try {
            block()
        } catch (e: NegativeResponseException) {
            if (!extended && e.nrc in SESSION_NRCS && enterExtended()) block() else throw e
        }
    }

    companion object {
        private const val NRC_INVALID_FORMAT = 0x13
        private const val NRC_OUT_OF_RANGE = 0x31
        private val SESSION_NRCS = setOf(0x7F, 0x7E, 0x31, 0x22)

        /** ASCII when printable (padding 0x00/0x20/0xFF trimmed), otherwise hex. */
        fun decodeIdentification(data: ByteArray, format: IdFormat): String {
            if (format == IdFormat.HEX) return data.toHex("")
            val trimmed = data.dropWhile { isPad(it) }.dropLastWhile { isPad(it) }.toByteArray()
            if (trimmed.isEmpty()) return ""
            val printable = trimmed.all { (it.toInt() and 0xFF) in 0x20..0x7E }
            return if (printable) trimmed.decodeToString().trim() else data.toHex()
        }

        private fun isPad(b: Byte): Boolean = b.toInt() and 0xFF in setOf(0x00, 0x20, 0xFF)
    }
}
