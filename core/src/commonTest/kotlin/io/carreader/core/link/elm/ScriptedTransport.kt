package io.carreader.core.link.elm

import io.carreader.core.transport.Transport
import kotlinx.coroutines.delay

/**
 * Fake adapter for unit tests: each command written gets the next scripted answer for it (the last one
 * repeats). Unscripted AT/ST commands answer `OK`, anything else `NO DATA`. Answers are written the way an
 * ELM327 prints them: lines ending in CR, then a blank line and the `>` prompt.
 */
class ScriptedTransport(private val echo: Boolean = false) : Transport {
    override val description = "scripted"
    private val scripts = HashMap<String, ArrayDeque<String>>()
    private val pending = StringBuilder()
    val sent = ArrayList<String>()
    var connected = false
        private set
    var closed = false
        private set

    /** Answers for [command] (compared without spaces, upper case); lines separated by '\n' in [answers]. */
    fun on(command: String, vararg answers: String): ScriptedTransport {
        scripts[norm(command)] = ArrayDeque(answers.toList())
        return this
    }

    fun sentCount(command: String) = sent.count { norm(it) == norm(command) }

    override suspend fun connect() { connected = true }

    override suspend fun write(data: ByteArray) {
        val cmd = data.decodeToString().trimEnd('\r')
        sent += cmd
        val key = norm(cmd)
        val queue = scripts[key]
        val answer = when {
            queue != null && queue.size > 1 -> queue.removeFirst()
            queue != null && queue.size == 1 -> queue.first()
            key.startsWith("AT") || key.startsWith("ST") -> "OK"
            else -> "NO DATA"
        }
        if (echo) pending.append(cmd).append('\r')
        answer.split('\n').forEach { pending.append(it).append('\r') }
        pending.append("\r>")
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (pending.isEmpty()) {
            delay(timeoutMs)
            return ByteArray(0)
        }
        // Deliver in small chunks to exercise reassembly of split reads.
        val n = minOf(7, pending.length)
        val out = pending.substring(0, n)
        pending.deleteRange(0, n)
        return out.encodeToByteArray()
    }

    override suspend fun close() { closed = true }

    private fun norm(s: String) = s.filter { it != ' ' }.uppercase()
}
