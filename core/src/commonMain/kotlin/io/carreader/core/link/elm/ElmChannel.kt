package io.carreader.core.link.elm

import io.carreader.core.link.LinkException
import io.carreader.core.log.Direction
import io.carreader.core.log.TrafficEntry
import io.carreader.core.log.TrafficLog
import io.carreader.core.transport.Transport

/** The cleaned lines of one adapter reply. [promptSeen] is false when the idle timeout expired first. */
class ElmReply(val command: String, val lines: List<String>, val promptSeen: Boolean)

/**
 * Line-level I/O with an ELM327/STN: writes `command\r`, reads until the `>` prompt, splits on CR/LF,
 * removes NUL bytes, blank lines and the command echo. Every TX and RX line goes to the [TrafficLog].
 *
 * The timeout is an idle timeout: each read waits at most `timeoutMs` for the next bytes, so a slow but
 * steady multi-ECU answer is never cut short. [MAX_REPLY_CHARS] bounds a runaway stream. Not thread safe:
 * [ElmLink] serializes access.
 */
class ElmChannel(
    private val transport: Transport,
    private val log: TrafficLog,
    private val clock: () -> Long,
) {
    suspend fun command(command: String, timeoutMs: Long): ElmReply {
        transport.write((command + "\r").encodeToByteArray())
        log.record(TrafficEntry(clock(), Direction.TX, command))
        val text = StringBuilder()
        var prompt = false
        while (true) {
            val chunk = transport.read(timeoutMs)
            if (chunk.isEmpty()) break
            for (b in chunk) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '>') { prompt = true; break }
                if (c != '\u0000') text.append(c)
            }
            if (prompt) break
            if (text.length > MAX_REPLY_CHARS) throw LinkException("Adapter reply to '$command' exceeds $MAX_REPLY_CHARS characters")
        }
        val lines = splitLines(text.toString())
        lines.forEach { log.record(TrafficEntry(clock(), Direction.RX, it)) }
        if (!prompt) log.record(TrafficEntry(clock(), Direction.ERROR, "No prompt after '$command' within ${timeoutMs} ms"))
        return ElmReply(command, stripEcho(command, lines), prompt)
    }

    /** Discards anything pending (a late answer, a banner after power-up). */
    suspend fun drain(timeoutMs: Long = 50) {
        repeat(20) {
            val chunk = transport.read(timeoutMs)
            if (chunk.isEmpty()) return
            log.record(TrafficEntry(clock(), Direction.INFO, "Discarded: " + chunk.decodeToString().replace("\r", "\\r").replace("\n", "\\n")))
        }
    }

    fun note(text: String, direction: Direction = Direction.INFO) = log.record(TrafficEntry(clock(), direction, text))

    companion object {
        const val MAX_REPLY_CHARS = 64 * 1024

        fun splitLines(text: String): List<String> =
            text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** Drops a leading echo of [command] (echo on before ATE0, or adapters that ignore ATE0). */
        fun stripEcho(command: String, lines: List<String>): List<String> {
            if (lines.isEmpty()) return lines
            val norm = { s: String -> s.filter { it != ' ' }.uppercase() }
            return if (norm(lines[0]) == norm(command)) lines.drop(1) else lines
        }
    }
}
