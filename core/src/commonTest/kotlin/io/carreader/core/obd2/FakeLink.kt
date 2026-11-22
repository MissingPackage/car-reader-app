package io.carreader.core.obd2

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.AdapterChip
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.LinkException
import io.carreader.core.link.RequestOptions
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex

/**
 * Scripted [DiagnosticLink] for tests: maps a request ("01 0C") to the responses of each ECU.
 * Unscripted requests get no answer (empty list, like "NO DATA"); [failing] requests throw [LinkException].
 */
class FakeLink(override var busProtocol: BusProtocol? = BusProtocol.CAN_11_500) : io.carreader.core.guard.GatedDiagnosticLink {
    private val script = HashMap<String, MutableList<EcuResponse>>()
    val failing = HashSet<String>()
    val sent = ArrayList<String>()

    override val adapterInfo: AdapterInfo = AdapterInfo(
        identity = "Fake", firmware = null, chip = AdapterChip.SIMULATOR, supportsStCommands = false,
        supportsVoltage = false, supportsHeaders = true, certified = true,
    )

    /** Adds one ECU answer to [request]; call several times for several ECUs or K-line messages. */
    fun on(request: String, sourceId: Int?, response: String): FakeLink {
        script.getOrPut(normalize(request)) { ArrayList() } += EcuResponse(sourceId, response.hexToBytes())
        return this
    }

    private fun normalize(hex: String) = hex.hexToBytes().toHex()

    override suspend fun initialize(): AdapterInfo = adapterInfo

    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> {
        val key = payload.toHex()
        sent += key
        if (key in failing) throw LinkException("scripted failure for $key")
        return script[key].orEmpty().toList()
    }

    override suspend fun readBatteryVoltage(): Double? = null

    override suspend fun close() {}
}
