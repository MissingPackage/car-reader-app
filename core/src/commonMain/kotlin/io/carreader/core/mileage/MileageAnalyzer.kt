package io.carreader.core.mileage

import kotlin.math.abs
import kotlin.math.max

/** Overall result of a mileage comparison. */
enum class MileageVerdict {
    /** Every comparable value agrees with the dashboard within the tolerance. */
    CONSISTENT,

    /** At least one module holds a higher mileage than the dashboard: a typical sign of a rolled-back odometer. */
    SUSPICIOUS,

    /** Fewer than two comparable distance values: nothing to compare. */
    NOT_ENOUGH_DATA,
}

/** One observation about a reading, relative to the reference mileage. */
sealed interface MileageFinding {
    val reading: CounterReading

    /** Module total above the dashboard: suspicious. [deltaKm] > 0. */
    data class TotalAboveDashboard(override val reading: CounterReading, val deltaKm: Double) : MileageFinding

    /**
     * Module total below the dashboard: not a rollback sign by itself (a replaced or rarely updated unit), but
     * worth knowing. [deltaKm] < 0.
     */
    data class TotalBelowDashboard(override val reading: CounterReading, val deltaKm: Double) : MileageFinding

    /** A past event recorded at a mileage above today's dashboard: suspicious. [deltaKm] > 0. */
    data class StampAboveDashboard(override val reading: CounterReading, val deltaKm: Double) : MileageFinding

    /** Two dashboard counters of the cluster disagree with each other. */
    data class DashboardsDisagree(override val reading: CounterReading, val deltaKm: Double) : MileageFinding

    /** No dashboard value: module totals disagree with the highest total by [deltaKm] (< 0). */
    data class TotalsDisagree(override val reading: CounterReading, val deltaKm: Double) : MileageFinding

    /** No dashboard value: a past event recorded above the highest module total. */
    data class StampAboveTotals(override val reading: CounterReading, val deltaKm: Double) : MileageFinding

    val suspicious: Boolean
        get() = this is TotalAboveDashboard || this is StampAboveDashboard || this is StampAboveTotals
}

/** Distance divided by operating time; [hoursSource] says which hour counter was used. */
data class AverageSpeed(val kmh: Double, val distanceKm: Double, val hours: Double, val hoursSource: CounterReading) {
    /** Below this a car has barely moved for its hours: the odometer may have been reduced while the hours were not. */
    val unusuallyLow: Boolean get() = kmh < LOW_AVERAGE_KMH

    companion object {
        const val LOW_AVERAGE_KMH = 12.0
    }
}

data class MileageCheck(
    val readings: List<CounterReading>,
    val verdict: MileageVerdict,
    /** The dashboard mileage, null when the cluster gave none. */
    val dashboardKm: Double?,
    /** The value everything was compared with: the dashboard, else the highest module total. */
    val referenceKm: Double?,
    val toleranceKm: Double,
    val findings: List<MileageFinding>,
    val averageSpeed: AverageSpeed?,
    /** Brand notes on what cannot be read (English text from the catalog). */
    val notes: List<String> = emptyList(),
) {
    fun findingFor(reading: CounterReading): MileageFinding? = findings.firstOrNull { it.reading === reading }
}

/**
 * Tolerance for differences between modules. Units copy the cluster's total at different moments (power-up,
 * every few km, when a fault is stored), so small gaps are normal. The default is deliberately generous: a
 * rollback is usually tens of thousands of km, and a false alarm on a sold car is worse than a missed 1000 km.
 */
data class MileageTolerance(val minimumKm: Double = 100.0, val fraction: Double = 0.01) {
    fun at(referenceKm: Double): Double = max(minimumKm, abs(referenceKm) * fraction)
}

/**
 * Pure comparison of counter readings: no I/O, so every rule is unit-tested. The rules:
 * - dashboard present: a module total or an event stamp above it (beyond the tolerance) is suspicious; a
 *   module total below it is reported but is not suspicious on its own;
 * - no dashboard: totals are compared with the highest total, stamps above every total are suspicious;
 * - average speed = reference distance / the first operating-time counter that has a value.
 */
class MileageAnalyzer(private val tolerance: MileageTolerance = MileageTolerance()) {

    fun analyze(readings: List<CounterReading>, notes: List<String> = emptyList()): MileageCheck {
        val ok = readings.filter { it.status == ReadingStatus.OK }
        val distances = ok.filter { it.source.kind == CounterKind.DISTANCE }
        val dashboards = distances.filter { it.source.role == CounterRole.DASHBOARD }
        val totals = distances.filter { it.source.role == CounterRole.TOTAL }
        val stamps = distances.filter { it.source.role == CounterRole.EVENT_STAMP }

        val dashboard = dashboards.firstOrNull()?.value
        val reference = dashboard ?: totals.maxOfOrNull { it.value!! }
        val tol = tolerance.at(reference ?: 0.0)
        val findings = ArrayList<MileageFinding>()

        if (dashboard != null) {
            for (d in dashboards.drop(1)) {
                val delta = d.value!! - dashboard
                if (abs(delta) > tol) findings += MileageFinding.DashboardsDisagree(d, delta)
            }
            for (t in totals) {
                val delta = t.value!! - dashboard
                if (delta > tol) findings += MileageFinding.TotalAboveDashboard(t, delta)
                else if (delta < -tol) findings += MileageFinding.TotalBelowDashboard(t, delta)
            }
            for (s in stamps) {
                val delta = s.value!! - dashboard
                if (delta > tol) findings += MileageFinding.StampAboveDashboard(s, delta)
            }
        } else if (reference != null) {
            for (t in totals) {
                val delta = t.value!! - reference
                if (delta < -tol) findings += MileageFinding.TotalsDisagree(t, delta)
            }
            for (s in stamps) {
                val delta = s.value!! - reference
                if (delta > tol) findings += MileageFinding.StampAboveTotals(s, delta)
            }
        }

        // Comparable pairs: the reference plus at least one other distance value.
        val comparable = distances.size >= 2 && reference != null
        val verdict = when {
            findings.any { it.suspicious } -> MileageVerdict.SUSPICIOUS
            !comparable -> MileageVerdict.NOT_ENOUGH_DATA
            else -> MileageVerdict.CONSISTENT
        }

        val hours = ok.firstOrNull { it.source.kind == CounterKind.OPERATING_TIME && it.value!! > 0.0 }
        val average = if (reference != null && reference > 0.0 && hours != null) {
            AverageSpeed(reference / hours.value!!, reference, hours.value, hours)
        } else null

        return MileageCheck(readings, verdict, dashboard, reference, tol, findings, average, notes)
    }
}
