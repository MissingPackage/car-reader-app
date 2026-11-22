package io.carreader.core.link.elm

import io.carreader.core.link.LinkException
import io.carreader.core.log.Direction
import io.carreader.core.log.TrafficEntry
import io.carreader.core.log.TrafficLog
import io.carreader.core.transport.Transport

/**
 * The cleaned lines of one adapter reply. [promptSeen] is false when the idle timeout expired first.
 * [echoed] is true when the first line was the echo of the command (echo on). [raw] is everything read,
 * unprocessed (used to look for reset banners in stale output).
 */
class ElmReply(
    val command: String,
    val lines: List<String>,
    val promptSeen: Boolean,
    val echoed: Boolean = false,
    val raw: String = "",
)

/**
 * Line-level I/O with an ELM327/STN: writes `command\r`, reads until the `>` prompt, splits on CR/LF,
 * removes NUL bytes, blank lines and the command echo. Every TX and RX line goes to the [TrafficLog].
 *
 * The timeout is an idle timeout: each read waits at most `timeoutMs` for the next bytes, so a slow but
 * steady multi-ECU answer is never cut short. While the adapter reports a protocol search in progress
 * (`SEARCHING...`, `BUS INIT: ...`), silent gaps are tolerated up to `searchBudgetMs` in total, because a
 * K-line 5-baud init takes seconds per protocol. [MAX_REPLY_CHARS] bounds a runaway stream.
 *
 * [dirty]: set before every write and cleared only when the reply ended with a prompt and nothing followed
 * it. A missed prompt, a timeout, a transport error or a cancellation in the middle of an exchange leave it
 * set, so the next exchange knows the adapter may still be printing an old answer and resynchronizes first.
 * Not thread safe: [ElmLink] serializes access.
 */
class ElmChannel(
    private val transport: Transport,
    private val log: TrafficLog,
    private val clock: () -> Long,
) {
    var dirty: Boolean = false
        private set

    fun markDirty() { dirty = true }

    fun markClean() { dirty = false }

    suspend fun command(command: String, timeoutMs: Long, searchBudgetMs: Long = 0): ElmReply {
        dirty = true
        transport.write((command + "\r").encodeToByteArray())
        log.record(TrafficEntry(clock(), Direction.TX, command))
        val text = StringBuilder()
        val after = StringBuilder()
        var prompt = false
        var idleWaited = 0L
        var searchNoted = false
        while (!prompt) {
            val chunk = transport.read(timeoutMs)
            if (chunk.isEmpty()) {
                idleWaited += timeoutMs
                if (searchBudgetMs > 0 && idleWaited < searchBudgetMs && searchInProgress(text)) {
                    if (!searchNoted) {
                        log.record(TrafficEntry(clock(), Direction.INFO, "Protocol search in progress: waiting up to $searchBudgetMs ms"))
                        searchNoted = true
                    }
                    continue
                }
                break
            }
            for (i in chunk.indices) {
                val c = (chunk[i].toInt() and 0xFF).toChar()
                if (prompt) {
                    after.append(c)
                    continue
                }
                if (c == '>') { prompt = true; continue }
                if (c != '\u0000') text.append(c)
            }
            if (text.length > MAX_REPLY_CHARS) throw LinkException("Adapter reply to '$command' exceeds $MAX_REPLY_CHARS characters")
        }
        val lines = splitLines(text.toString())
        lines.forEach { log.record(TrafficEntry(clock(), Direction.RX, it)) }
        if (!prompt) log.record(TrafficEntry(clock(), Direction.ERROR, "No prompt after '$command' within ${timeoutMs} ms"))
        val trailing = after.filter { it != '\r' && it != '\n' && it != ' ' && it != '\u0000' }
        if (trailing.isNotEmpty()) {
            log.record(TrafficEntry(clock(), Direction.ERROR, "Bytes after the prompt (out of step): " + escape(after.toString())))
        }
        if (prompt && trailing.isEmpty()) dirty = false
        val stripped = stripEcho(command, lines)
        return ElmReply(command, stripped, prompt, echoed = stripped.size != lines.size, raw = text.toString() + after)
    }

    /**
     * Reads and discards whatever the adapter is still printing, until [silenceMs] pass with no byte (at most
     * [maxReads] reads). Returns what was discarded.
     */
    suspend fun drainText(silenceMs: Long = 50, maxReads: Int = 100): String {
        val out = StringBuilder()
        repeat(maxReads) {
            val chunk = transport.read(silenceMs)
            if (chunk.isEmpty()) return out.toString()
            val s = chunk.decodeToString()
            out.append(s)
            log.record(TrafficEntry(clock(), Direction.INFO, "Discarded: " + escape(s)))
        }
        return out.toString()
    }

    fun note(text: String, direction: Direction = Direction.INFO) = log.record(TrafficEntry(clock(), direction, text))

    companion object {
        const val MAX_REPLY_CHARS = 64 * 1024

        fun splitLines(text: String): List<String> =
            text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** Drops a leading echo of [command] (echo on before ATE0, or adapters that ignore ATE0). */
        fun stripEcho(command: String, lines: List<String>): List<String> {
            if (lines.isEmpty()) return lines
            return if (norm(lines[0]) == norm(command)) lines.drop(1) else lines
        }

        fun norm(s: String): String = s.filter { it != ' ' }.uppercase()

        private fun searchInProgress(text: CharSequence): Boolean {
            val t = text.toString().uppercase()
            return "SEARCHING" in t || "BUS INIT" in t
        }

        private fun escape(s: String) = s.replace("\r", "\\r").replace("\n", "\\n")
    }
}
