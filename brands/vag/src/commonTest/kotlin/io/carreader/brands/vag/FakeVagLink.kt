package io.carreader.brands.vag

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex

/** Test link: fixed answers keyed by (request id, request hex); anything else gets no answer. */
class FakeVagLink(private val answers: Map<Pair<Int, String>, String>) : io.carreader.core.guard.GatedDiagnosticLink {
    val sent = ArrayList<Pair<Int, String>>()

    override suspend fun initialize(): AdapterInfo = error("not used")
    override val busProtocol: BusProtocol = BusProtocol.CAN_11_500
    override val adapterInfo: AdapterInfo? = null

    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> {
        val key = target.requestId to payload.toHex()
        sent += key
        val answer = answers[key] ?: return emptyList()
        return listOf(EcuResponse(target.responseId, answer.hexToBytes()))
    }

    override suspend fun readBatteryVoltage(): Double? = null
    override suspend fun close() = Unit

    companion object {
        fun of(vararg entries: Pair<Pair<Int, String>, String>) =
            FakeVagLink(entries.associate { (k, v) -> (k.first to k.second.hexToBytes().toHex()) to v })
    }
}
