package io.carreader.core.mileage

import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.link.EcuAddress
import io.carreader.core.obd2.LiveValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MileageAnalyzerTest {
    private val ecu = EcuDescriptor("17", "Instruments", EcuAddress(0x714, 0x77E))

    private class Item(override val unit: String) : DataItem {
        override val key = "k"
        override val name = "n"
        override val did = 0x1234
        override fun decode(data: ByteArray): LiveValue = error("unused")
    }

    private fun source(role: CounterRole, id: String = role.name, unit: String = "km", ceiling: Double? = null) = CounterSource(
        id = id,
        kind = if (role == CounterRole.OPERATING_TIME) CounterKind.OPERATING_TIME else CounterKind.DISTANCE,
        role = role,
        label = id,
        origin = CounterOrigin.BrandData(ecu, Item(unit)),
        provenance = "test",
        ceiling = ceiling,
    )

    private fun ok(role: CounterRole, value: Double, id: String = role.name) = CounterReading(source(role, id), "m", value, ReadingStatus.OK)
    private fun missing(role: CounterRole, status: ReadingStatus) = CounterReading(source(role), "m", null, status)

    private val analyzer = MileageAnalyzer()

    @Test
    fun agreeingValuesAreConsistent() {
        val check = analyzer.analyze(
            listOf(
                ok(CounterRole.DASHBOARD, 87_412.0),
                ok(CounterRole.TOTAL, 87_409.0, "engine"),
                ok(CounterRole.TOTAL, 87_412.1, "gearbox"),
                ok(CounterRole.EVENT_STAMP, 61_240.0),
                ok(CounterRole.OPERATING_TIME, 2_050.0),
            ),
        )
        assertEquals(MileageVerdict.CONSISTENT, check.verdict)
        assertEquals(87_412.0, check.dashboardKm)
        assertTrue(check.findings.isEmpty())
        assertEquals(874.12, check.toleranceKm, 1e-9)
        val avg = assertNotNull(check.averageSpeed)
        assertEquals(87_412.0 / 2_050.0, avg.kmh, 1e-9)
        assertTrue(!avg.unusuallyLow)
    }

    @Test
    fun moduleAboveDashboardIsSuspicious() {
        val check = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 27_412.0), ok(CounterRole.TOTAL, 87_412.0)))
        assertEquals(MileageVerdict.SUSPICIOUS, check.verdict)
        val f = check.findings.single() as MileageFinding.TotalAboveDashboard
        assertEquals(60_000.0, f.deltaKm, 1e-9)
    }

    @Test
    fun stampAboveDashboardIsSuspiciousEvenWhenTotalsAgree() {
        // A tamper that rewrote every running total but forgot an event stamp.
        val check = analyzer.analyze(
            listOf(ok(CounterRole.DASHBOARD, 27_412.0), ok(CounterRole.TOTAL, 27_410.0), ok(CounterRole.EVENT_STAMP, 85_512.0)),
        )
        assertEquals(MileageVerdict.SUSPICIOUS, check.verdict)
        assertTrue(check.findings.single() is MileageFinding.StampAboveDashboard)
    }

    @Test
    fun moduleBelowDashboardIsReportedButNotSuspicious() {
        val check = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 150_000.0), ok(CounterRole.TOTAL, 40_000.0)))
        assertEquals(MileageVerdict.CONSISTENT, check.verdict)
        assertTrue(check.findings.single() is MileageFinding.TotalBelowDashboard)
    }

    @Test
    fun smallDifferencesStayWithinTolerance() {
        // 100 km minimum below 10 000 km; 1 % above.
        val low = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 5_000.0), ok(CounterRole.TOTAL, 5_099.0)))
        assertEquals(MileageVerdict.CONSISTENT, low.verdict)
        val lowOver = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 5_000.0), ok(CounterRole.TOTAL, 5_101.0)))
        assertEquals(MileageVerdict.SUSPICIOUS, lowOver.verdict)
        val high = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 200_000.0), ok(CounterRole.TOTAL, 201_999.0)))
        assertEquals(MileageVerdict.CONSISTENT, high.verdict)
    }

    @Test
    fun withoutDashboardTotalsAreComparedWithEachOther() {
        val check = analyzer.analyze(
            listOf(
                missing(CounterRole.DASHBOARD, ReadingStatus.MODULE_ABSENT),
                ok(CounterRole.TOTAL, 87_412.0, "a"),
                ok(CounterRole.TOTAL, 40_000.0, "b"),
                ok(CounterRole.EVENT_STAMP, 95_000.0),
            ),
        )
        assertNull(check.dashboardKm)
        assertEquals(87_412.0, check.referenceKm)
        assertEquals(MileageVerdict.SUSPICIOUS, check.verdict)
        assertTrue(check.findings.any { it is MileageFinding.TotalsDisagree })
        assertTrue(check.findings.any { it is MileageFinding.StampAboveTotals })
    }

    @Test
    fun oneValueIsNotEnough() {
        assertEquals(MileageVerdict.NOT_ENOUGH_DATA, analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 1_000.0))).verdict)
        assertEquals(MileageVerdict.NOT_ENOUGH_DATA, analyzer.analyze(emptyList()).verdict)
        assertEquals(
            MileageVerdict.NOT_ENOUGH_DATA,
            analyzer.analyze(listOf(ok(CounterRole.OPERATING_TIME, 10.0), missing(CounterRole.TOTAL, ReadingStatus.NOT_SUPPORTED))).verdict,
        )
    }

    @Test
    fun clusterCountersThatDisagreeAreReported() {
        val check = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 50_000.0, "hr"), ok(CounterRole.DASHBOARD, 20_000.0, "dash")))
        assertTrue(check.findings.single() is MileageFinding.DashboardsDisagree)
        assertEquals(MileageVerdict.CONSISTENT, check.verdict)
    }

    @Test
    fun lowAverageSpeedIsFlagged() {
        val check = analyzer.analyze(listOf(ok(CounterRole.DASHBOARD, 27_412.0), ok(CounterRole.OPERATING_TIME, 2_310.0)))
        assertTrue(assertNotNull(check.averageSpeed).unusuallyLow)
    }

    @Test
    fun normalizeConvertsUnitsAndRejectsNonReadings() {
        fun value(v: Double?, unit: String) = LiveValue("k", "n", v, unit, "")
        val km = source(CounterRole.TOTAL, unit = "km", ceiling = 16_777_215.0)
        assertEquals(87_412.0, CounterReader.normalize(km, "m", value(87_412.0, "km")).value)
        assertEquals(ReadingStatus.OUT_OF_RANGE, CounterReader.normalize(km, "m", value(16_777_215.0, "km")).status)
        assertEquals(ReadingStatus.NOT_RECORDED, CounterReader.normalize(km, "m", value(0.0, "km")).status)
        assertEquals(ReadingStatus.NOT_RECORDED, CounterReader.normalize(km, "m", value(null, "km")).status)
        assertEquals(ReadingStatus.UNKNOWN_UNIT, CounterReader.normalize(km, "m", value(12.0, "%")).status)
        assertEquals(1.5, CounterReader.normalize(km, "m", value(1_500.0, "m")).value!!, 1e-9)
        val hours = source(CounterRole.OPERATING_TIME)
        assertEquals(2.0, CounterReader.normalize(hours, "m", value(7_200.0, "s")).value!!, 1e-9)
        assertEquals(ReadingStatus.UNKNOWN_UNIT, CounterReader.normalize(hours, "m", value(7_200.0, "km")).status)
    }

    @Test
    fun readingsKeepValueAndStatusConsistent() {
        assertFailsWith<IllegalArgumentException> { CounterReading(source(CounterRole.TOTAL), "m", null, ReadingStatus.OK) }
        assertFailsWith<IllegalArgumentException> { CounterReading(source(CounterRole.TOTAL), "m", 1.0, ReadingStatus.NOT_RECORDED) }
        assertFailsWith<IllegalArgumentException> {
            CounterSource("x", CounterKind.DISTANCE, CounterRole.OPERATING_TIME, "x", CounterOrigin.BrandData(ecu, Item("km")), "t")
        }
    }
}
