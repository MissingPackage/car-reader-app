package io.carreader.app.logging

import io.carreader.core.guard.AuditEvent
import io.carreader.core.guard.AuditLog
import io.carreader.core.guard.CheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit log of every mutation attempt: kept in memory for the diagnostics screen and appended to [file] so the
 * record survives app restarts. [file] is null in tests.
 */
class AppAuditLog(private val file: File?, private val maxInMemory: Int = 500) : AuditLog {
    private val events = ArrayDeque<AuditEvent>()

    override suspend fun record(event: AuditEvent) {
        synchronized(this) {
            if (events.size >= maxInMemory) events.removeFirst()
            events.addLast(event)
        }
        val f = file ?: return
        withContext(Dispatchers.IO) { runCatching { f.appendText(format(event) + "\n") } }
    }

    fun snapshot(): List<AuditEvent> = synchronized(this) { events.toList() }

    /** Persisted lines, including previous sessions. Falls back to the in-memory events. */
    fun persistedLines(maxLines: Int = 200): List<String> {
        val fromFile = file?.takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() }
        return (fromFile ?: snapshot().map(::format)).takeLast(maxLines)
    }

    companion object {
        fun format(event: AuditEvent): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(event.timestampMs))
            val checks = event.checks.joinToString("; ") { (name, result) ->
                when (result) {
                    CheckResult.Pass -> "$name: ok"
                    is CheckResult.Warn -> "$name: WARN ${result.reason}"
                    is CheckResult.Fail -> "$name: FAIL ${result.reason}"
                }
            }
            val suffix = if (checks.isEmpty()) "" else " [$checks]"
            return "$time ${event.request.kind.name} target=${event.request.targetLabel} -> ${event.outcome}$suffix"
        }
    }
}
