package io.carreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.carreader.app.R
import io.carreader.app.ui.CoreText
import io.carreader.app.ui.MainViewModel
import io.carreader.app.ui.MileageState
import io.carreader.app.ui.UiState
import io.carreader.app.ui.theme.LocalStatusColors
import io.carreader.core.mileage.CounterReading
import io.carreader.core.mileage.CounterRole
import io.carreader.core.mileage.MileageAnalyzer
import io.carreader.core.mileage.MileageCheck
import io.carreader.core.mileage.MileageFinding
import io.carreader.core.mileage.MileageVerdict
import io.carreader.core.mileage.ReadingStatus
import java.util.Locale
import kotlin.math.abs

/**
 * Mileage check: the dashboard total next to every other copy the car keeps, with the difference to the
 * dashboard on each row and a verdict on top. Values come from [io.carreader.core.mileage.MileageAnalyzer].
 */
@Composable
fun MileageScreen(state: UiState, vm: MainViewModel) {
    val m = state.mileage
    ScreenScaffold(
        title = stringResource(R.string.mileage_title),
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = vm::startMileageCheck, enabled = !m.running && !state.connectionLost) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionLostBanner(state, vm)
            Text(stringResource(R.string.mileage_intro), style = MaterialTheme.typography.bodyMedium)
            if (m.running) {
                Loading(
                    if (m.expected > 0) stringResource(R.string.mileage_reading, m.readings.map { it.source.id }.distinct().size.coerceAtMost(m.expected), m.expected)
                    else stringResource(R.string.mileage_reading_start),
                )
            }
            m.error?.let { ErrorText(stringResource(R.string.mileage_failed, CoreText.message(it)), onRetry = vm::startMileageCheck) }
            m.check?.let { VerdictCard(it) }
            MileageBody(m)
        }
    }
}

@Composable
private fun MileageBody(m: MileageState) {
    val check = m.check
    val ok = m.readings.filter { it.status == ReadingStatus.OK }
    fun byRole(role: CounterRole) = ok.filter { it.source.role == role }

    val dashboards = byRole(CounterRole.DASHBOARD)
    if (dashboards.isNotEmpty()) {
        Section(title = stringResource(R.string.mileage_section_dashboard)) {
            dashboards.forEachIndexed { i, r -> CounterRow(r, check, primary = i == 0) }
        }
    } else if (check != null && check.referenceKm != null) {
        WarningBox(stringResource(R.string.mileage_no_dashboard))
    }

    val totals = byRole(CounterRole.TOTAL)
    if (totals.isNotEmpty()) {
        Section(title = stringResource(R.string.mileage_section_totals)) { totals.forEach { CounterRow(it, check) } }
    }

    val stamps = byRole(CounterRole.EVENT_STAMP)
    if (stamps.isNotEmpty()) {
        Section(title = stringResource(R.string.mileage_section_stamps)) {
            Text(stringResource(R.string.mileage_stamps_hint), style = MaterialTheme.typography.bodySmall)
            stamps.forEach { CounterRow(it, check) }
        }
    }

    val hours = byRole(CounterRole.OPERATING_TIME)
    if (hours.isNotEmpty() || check != null) {
        Section(title = stringResource(R.string.mileage_section_time)) {
            hours.forEach { CounterRow(it, check) }
            if (check != null) AverageSpeedRow(check)
        }
    }

    // Only once the check is complete: while reading, "not available" rows would flicker in one by one.
    val missing = m.readings.filter { it.status != ReadingStatus.OK }
    if (check != null && missing.isNotEmpty()) {
        Section(title = stringResource(R.string.mileage_section_unavailable)) {
            missing.forEach { r ->
                Column(Modifier.fillMaxWidth()) {
                    Text(CoreText.counterModule(r.module) + " · " + r.source.label, style = MaterialTheme.typography.bodyMedium)
                    Text(statusText(r.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (check != null && check.notes.isNotEmpty()) {
        Section(title = stringResource(R.string.mileage_section_notes)) {
            check.notes.forEach { Text("• " + CoreText.counterNote(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (check != null) {
        Text(
            stringResource(R.string.mileage_disclaimer, km(check.toleranceKm)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerdictCard(check: MileageCheck) {
    val status = LocalStatusColors.current
    val analyzer = MileageAnalyzer()
    val suspicious = check.findings.filter { it.suspicious }.map { analyzer.unitKey(it.reading) }.toSet().size
    val comparedKm = check.readings.count { it.status == ReadingStatus.OK && it.source.role != CounterRole.OPERATING_TIME }
    val (icon, color, title, body) = when (check.verdict) {
        MileageVerdict.CONSISTENT -> Quad(
            Icons.Filled.CheckCircle, status.ok,
            stringResource(R.string.mileage_verdict_consistent_title),
            pluralStringResource(R.plurals.mileage_verdict_consistent_body, comparedKm, comparedKm, km(check.toleranceKm)),
        )
        MileageVerdict.DIFFERENCES -> Quad(
            Icons.Filled.Info, status.warn,
            stringResource(R.string.mileage_verdict_differences_title),
            stringResource(R.string.mileage_verdict_differences_body),
        )
        MileageVerdict.SUSPICIOUS -> Quad(
            Icons.Filled.Warning, status.bad,
            stringResource(R.string.mileage_verdict_suspicious_title),
            pluralStringResource(R.plurals.mileage_verdict_suspicious_body, suspicious, suspicious, km(check.toleranceKm)),
        )
        MileageVerdict.NOT_ENOUGH_DATA -> Quad(
            Icons.Filled.Help, MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.mileage_verdict_nodata_title),
            stringResource(R.string.mileage_verdict_nodata_body),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (check.verdict == MileageVerdict.SUSPICIOUS) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun CounterRow(r: CounterReading, check: MileageCheck?, primary: Boolean = false) {
    val status = LocalStatusColors.current
    val finding = check?.findingFor(r)
    val value = r.value ?: return
    val valueText = if (r.source.role == CounterRole.OPERATING_TIME) hours(value) else km(value)
    val (note, color) = when (finding) {
        is MileageFinding.TotalAboveDashboard -> stringResource(R.string.mileage_delta_above, km(abs(finding.deltaKm))) to status.bad
        is MileageFinding.StampAboveDashboard -> stringResource(R.string.mileage_delta_above, km(abs(finding.deltaKm))) to status.bad
        is MileageFinding.StampAboveTotals -> stringResource(R.string.mileage_delta_above_totals, km(abs(finding.deltaKm))) to status.bad
        is MileageFinding.TotalBelowDashboard -> stringResource(R.string.mileage_delta_below, km(abs(finding.deltaKm))) to status.warn
        is MileageFinding.TotalsDisagree -> stringResource(R.string.mileage_delta_below_totals, km(abs(finding.deltaKm))) to status.warn
        is MileageFinding.DashboardsDisagree -> stringResource(R.string.mileage_delta_dashboards, km(abs(finding.deltaKm))) to status.warn
        null -> when {
            check == null || primary || check.dashboardKm == null -> null to Color.Unspecified
            r.source.role == CounterRole.TOTAL || r.source.role == CounterRole.DASHBOARD -> stringResource(R.string.mileage_delta_ok) to status.ok
            r.source.role == CounterRole.EVENT_STAMP -> stringResource(R.string.mileage_stamp_ok) to status.ok
            else -> null to Color.Unspecified
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(CoreText.counterModule(r.module), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            valueText,
            style = if (primary) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (finding?.suspicious == true) status.bad else Color.Unspecified,
        )
        if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = color)
        Text(r.source.label + " · " + r.source.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AverageSpeedRow(check: MileageCheck) {
    val avg = check.averageSpeed
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(stringResource(R.string.mileage_average), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (avg == null) {
            Text(stringResource(R.string.mileage_average_none), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            String.format(Locale.getDefault(), "%.1f km/h", avg.kmh),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (avg.unusuallyLow) LocalStatusColors.current.warn else Color.Unspecified,
        )
        Text(stringResource(R.string.mileage_average_value, km(avg.distanceKm), hours(avg.hours)), style = MaterialTheme.typography.bodySmall)
        if (avg.unusuallyLow) Text(stringResource(R.string.mileage_average_low), style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.warn)
    }
}

@Composable
private fun statusText(status: ReadingStatus): String = stringResource(
    when (status) {
        ReadingStatus.MODULE_ABSENT -> R.string.mileage_status_absent
        ReadingStatus.NOT_SUPPORTED -> R.string.mileage_status_not_supported
        ReadingStatus.NOT_RECORDED -> R.string.mileage_status_not_recorded
        ReadingStatus.OUT_OF_RANGE -> R.string.mileage_status_out_of_range
        ReadingStatus.UNKNOWN_UNIT -> R.string.mileage_status_unknown_unit
        ReadingStatus.OK -> R.string.mileage_none
    },
)

/** Whole kilometres with the locale's grouping: "87,412 km" / "87.412 km". */
private fun km(value: Double): String = String.format(Locale.getDefault(), "%,.0f km", value)

private fun hours(value: Double): String = String.format(Locale.getDefault(), "%,.0f h", value)
