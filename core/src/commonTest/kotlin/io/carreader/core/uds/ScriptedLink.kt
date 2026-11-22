package io.carreader.core.uds

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import io.carreader.core.util.hexToBytes
import io.carreader.core.util.toHex

/**
 * Test link: answers by (request id, request hex). A queued list of answers is consumed in order (the last
 * one repeats); unknown requests get no answer. Records every request with its options.
 */
class ScriptedLink : DiagnosticLink {
    private val script = HashMap<Pair<Int, String>, ArrayDeque<List<String>>>()
    val sent = ArrayList<Triple<Int, String, RequestOptions>>()

    /** [answers]: one entry per exchange; each entry is the hex payloads of the answering ECUs ("" = none). */
    fun on(requestId: Int, request: String, vararg answers: String): ScriptedLink {
        script[requestId to norm(request)] = ArrayDeque(answers.map { a -> if (a.isEmpty()) emptyList() else listOf(a) })
        return this
    }

    override suspend fun initialize(): AdapterInfo = error("not used")
    override val busProtocol: BusProtocol = BusProtocol.CAN_11_500
    override val adapterInfo: AdapterInfo? = null

    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> {
        val key = target.requestId to payload.toHex()
        sent += Triple(target.requestId, key.second, options)
        val queue = script[key] ?: return emptyList()
        val answer = if (queue.size > 1) queue.removeFirst() else queue.first()
        return answer.map { EcuResponse(target.responseId, it.hexToBytes()) }
    }

    override suspend fun readBatteryVoltage(): Double? = null
    override suspend fun close() = Unit

    fun count(requestId: Int, request: String): Int = sent.count { it.first == requestId && it.second == norm(request) }

    private fun norm(hex: String) = hex.hexToBytes().toHex()
}
