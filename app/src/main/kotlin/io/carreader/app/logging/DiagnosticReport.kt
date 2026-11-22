package io.carreader.app.logging

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.log.TrafficEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppInfo(
    val versionName: String,
    val versionCode: Int,
    val device: String,
    val androidVersion: String,
)

/** Builds the plain-text log the tester shares with us: header, audit trail, raw adapter traffic. */
object DiagnosticReport {
    fun build(
        app: AppInfo,
        nowMs: Long,
        transport: String?,
        adapter: AdapterInfo?,
        protocol: BusProtocol?,
        vin: String?,
        manufacturer: String?,
        brandModule: String?,
        audit: List<String>,
        traffic: List<TrafficEntry>,
    ): String = buildString {
        appendLine("Car Reader diagnostics log")
        appendLine("Generated: ${timestamp(nowMs, "yyyy-MM-dd HH:mm:ss")}")
        appendLine("App: ${app.versionName} (${app.versionCode})")
        appendLine("Phone: ${app.device}, Android ${app.androidVersion}")
        appendLine("Connection: ${transport ?: "-"}")
        if (adapter != null) {
            appendLine("Adapter: ${adapter.identity}, firmware ${adapter.firmware ?: "-"}, chip ${adapter.chip}")
            appendLine(
                "Adapter capabilities: ST commands=${adapter.supportsStCommands}, voltage=${adapter.supportsVoltage}, " +
                    "headers=${adapter.supportsHeaders}, certified=${adapter.certified}",
            )
            adapter.notes.forEach { appendLine("Adapter note: $it") }
        } else {
            appendLine("Adapter: -")
        }
        appendLine("Protocol: ${protocol?.let { "${it.label} (${it.elmCode})" } ?: "-"}")
        appendLine("VIN: ${vin ?: "-"}")
        appendLine("Manufacturer: ${manufacturer ?: "-"}")
        appendLine("Brand module: ${brandModule ?: "-"}")
        appendLine()
        appendLine("== Audit log (operations that modify the car) ==")
        if (audit.isEmpty()) appendLine("(none)") else audit.forEach { appendLine(it) }
        appendLine()
        appendLine("== Adapter traffic (${traffic.size} entries) ==")
        traffic.forEach { appendLine(formatEntry(it)) }
    }

    fun formatEntry(entry: TrafficEntry): String =
        "${timestamp(entry.timestampMs, "HH:mm:ss.SSS")} ${entry.direction.name.padEnd(5)} ${entry.text}"

    private fun timestamp(ms: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.ROOT).format(Date(ms))
}
