package io.carreader.core.mileage

import io.carreader.core.brand.DataItem
import io.carreader.core.brand.EcuDescriptor
import io.carreader.core.brand.VehicleContext
import io.carreader.core.obd2.PidDefinition

/** What a counter measures. Values are normalized to kilometres and hours. */
enum class CounterKind { DISTANCE, OPERATING_TIME }

/** How a counter relates to the car's current mileage; decides how the [MileageAnalyzer] compares it. */
enum class CounterRole {
    /** The instrument cluster's total: what the driver sees, and what a rollback changes. */
    DASHBOARD,

    /** Another module's own copy of the running total: it should track the dashboard. */
    TOTAL,

    /** Mileage recorded at a past event (last adaptation, last warning): it can never exceed the current total. */
    EVENT_STAMP,

    /** An operating-time total (engine hours, gearbox drive time). */
    OPERATING_TIME,
}

/** Where a counter is read from. The session knows how to read each kind; catalogs only describe them. */
sealed interface CounterOrigin {
    /** A standard OBD-II service 01 PID, answered by every emission ECU that supports it. */
    data class ObdPid(val pid: PidDefinition) : CounterOrigin

    /** A brand measurement (UDS 0x22) on one control unit, read through the brand module. */
    data class BrandData(val ecu: EcuDescriptor, val item: DataItem) : CounterOrigin
}

/**
 * One counter a car may hold. [label] is the English name from the source table (OBDb or J1979);
 * [provenance] says where its identifier and scaling come from, so every value on screen is traceable.
 * A decoded value at or above [ceiling] is the top of the decoded range (clamped or "no value"), not a reading.
 */
data class CounterSource(
    val id: String,
    val kind: CounterKind,
    val role: CounterRole,
    val label: String,
    val origin: CounterOrigin,
    val provenance: String,
    val ceiling: Double? = null,
) {
    init {
        require((kind == CounterKind.OPERATING_TIME) == (role == CounterRole.OPERATING_TIME)) {
            "Operating-time counters use the OPERATING_TIME role, distances the others: $id"
        }
    }

    /** The control unit for brand counters; null for standard OBD-II PIDs (the answering ECU is in the reading). */
    val ecu: EcuDescriptor? get() = (origin as? CounterOrigin.BrandData)?.ecu
}

/** Why a counter has no usable value. */
enum class ReadingStatus {
    OK,

    /** The control unit did not answer at all: not fitted, or on a bus this adapter cannot reach. */
    MODULE_ABSENT,

    /** The control unit answered but refused or does not know this identifier (other variant or model year). */
    NOT_SUPPORTED,

    /** The counter is zero or marked empty: the event never happened, or the unit was never updated. */
    NOT_RECORDED,

    /** At the top of the decoded range: the true value cannot be told. */
    OUT_OF_RANGE,

    /** The answer had a unit this check cannot convert. */
    UNKNOWN_UNIT,
}

/**
 * The value of one counter, in km ([CounterKind.DISTANCE]) or hours ([CounterKind.OPERATING_TIME]).
 * [module] names the unit that answered ("01 Engine", or the OBD-II responder "7E8").
 */
data class CounterReading(
    val source: CounterSource,
    val module: String,
    val value: Double?,
    val status: ReadingStatus,
) {
    init {
        require((status == ReadingStatus.OK) == (value != null)) { "A value only with status OK" }
    }
}

/**
 * Port: the mileage and operating-time counters one brand knows. A brand adapter lists only identifiers it
 * can source; anything else is left out and named in [notes], never guessed.
 */
interface CounterCatalog {
    fun sources(vehicle: VehicleContext): List<CounterSource>

    /** What this brand cannot read yet (e.g. "the airbag mileage stamp has no public identifier"). English. */
    fun notes(vehicle: VehicleContext): List<String> = emptyList()
}

/** Brand-independent counters from SAE J1979 that any car may support. */
object StandardCounters : CounterCatalog {
    val odometer = CounterSource(
        id = "obd.A6",
        kind = CounterKind.DISTANCE,
        role = CounterRole.TOTAL,
        label = "Odometer (OBD-II)",
        origin = CounterOrigin.ObdPid(io.carreader.core.obd2.StandardPids.odometer),
        provenance = "SAE J1979 service 01 PID A6",
    )

    val engineRunTime = CounterSource(
        id = "obd.7F",
        kind = CounterKind.OPERATING_TIME,
        role = CounterRole.OPERATING_TIME,
        label = "Total engine run time (OBD-II)",
        origin = CounterOrigin.ObdPid(io.carreader.core.obd2.StandardPids.engineRunTimeTotal),
        provenance = "SAE J1979 service 01 PID 7F",
    )

    override fun sources(vehicle: VehicleContext): List<CounterSource> = listOf(odometer, engineRunTime)
}

/** Unit symbols ([DataItem.unit], [PidDefinition.unit]) → factor to km or hours; null when not convertible. */
object CounterUnits {
    fun toKm(unit: String): Double? = when (unit) {
        "km" -> 1.0
        "m" -> 0.001
        "mi" -> 1.609344
        else -> null
    }

    fun toHours(unit: String): Double? = when (unit) {
        "h" -> 1.0
        "min" -> 1.0 / 60.0
        "s" -> 1.0 / 3600.0
        else -> null
    }

    fun factor(kind: CounterKind, unit: String): Double? = when (kind) {
        CounterKind.DISTANCE -> toKm(unit)
        CounterKind.OPERATING_TIME -> toHours(unit)
    }
}
