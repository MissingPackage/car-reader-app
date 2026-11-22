package io.carreader.sim

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.TimeSource

/**
 * The simulated car's mutable state. Tests and the app's demo mode can flip it at runtime, e.g. switch the
 * engine off to satisfy the WriteGuard checks, or turn the ignition off to get `UNABLE TO CONNECT`.
 */
class SimVehicleState(
    var ignitionOn: Boolean = true,
    var engineRunning: Boolean = true,
    /** True: speed and rpm follow a slow urban drive cycle. False: idle, 0 km/h. */
    var driving: Boolean = false,
    var fuelLevelPercent: Double = 62.0,
    var ambientC: Double = 14.0,
    /** Battery voltage with the engine off; the alternator adds about 1.6 V when running. */
    var restingBatteryVolts: Double = 12.6,
    var odometerKm: Int = 87_412,
)

/** Physical values an ECU reports. [LiveSignals] varies them over time; [FrozenSignals] is a snapshot. */
interface SignalSource {
    val rpm: Double
    val speedKmh: Double
    val coolantC: Double
    val engineLoadPct: Double
    val mafGs: Double
    val shortTrimPct: Double
    val longTrimPct: Double
    val o2Volts: Double
    val intakeC: Double
    val throttlePct: Double
    val moduleVolts: Double
    val fuelLevelPct: Double
    val ambientC: Double
    val oilC: Double
    val manifoldKpa: Double
    val baroKpa: Double
    val railPressureKpa: Double
    val runTimeS: Double
    val boostBar: Double
    val dpfOutletC: Double
}

/**
 * Plausible, time-varying engine values derived from [state] and the elapsed time. Coolant and oil warm up
 * over the first minutes after the simulation starts; idle rpm wobbles; when driving, speed follows a
 * 0-80 km/h cycle with rpm and load following it.
 */
class LiveSignals(
    private val state: SimVehicleState,
    private val diesel: Boolean,
    private val clock: () -> Long = monotonicClock(),
) : SignalSource {
    private val startMs = clock()
    private val t: Double get() = (clock() - startMs) / 1000.0
    private val running get() = state.ignitionOn && state.engineRunning

    override val speedKmh: Double
        get() = if (!running || !state.driving) 0.0 else max(0.0, 40.0 + 40.0 * sin(2 * PI * t / 90.0))

    override val rpm: Double
        get() = when {
            !running -> 0.0
            speedKmh < 3 -> (if (diesel) 820.0 else 760.0) + 25 * sin(t * 1.7)
            else -> (if (diesel) 1100.0 else 1300.0) + speedKmh * (if (diesel) 22.0 else 30.0) + 40 * sin(t * 0.9)
        }

    private val warmUp: Double get() = min(1.0, t / 480.0)

    override val coolantC get() = if (!state.ignitionOn) state.ambientC else state.ambientC + (90.0 - state.ambientC) * (if (running) warmUp else 0.3)
    override val oilC get() = if (!state.ignitionOn) state.ambientC else state.ambientC + (98.0 - state.ambientC) * (if (running) warmUp * 0.9 else 0.25)
    override val engineLoadPct get() = if (!running) 0.0 else if (speedKmh < 3) 18.0 + 2 * sin(t) else 25.0 + speedKmh * 0.5
    override val mafGs get() = if (!running) 0.0 else rpm * engineLoadPct / 2600.0
    override val shortTrimPct get() = if (!running || diesel) 0.0 else 1.6 * sin(t * 2.3)
    override val longTrimPct get() = if (!running || diesel) 0.0 else 2.3
    override val o2Volts get() = if (!running) 0.0 else 0.45 + 0.4 * sin(t * 6.0)
    override val intakeC get() = state.ambientC + if (running) 8.0 * warmUp else 0.0
    override val throttlePct get() = if (!running) (if (state.ignitionOn) 14.9 else 0.0) else if (speedKmh < 3) 15.7 else 15.7 + speedKmh * 0.3
    override val moduleVolts get() = if (!state.ignitionOn) 0.0 else if (running) state.restingBatteryVolts + 1.6 else state.restingBatteryVolts - 0.1
    override val fuelLevelPct get() = state.fuelLevelPercent
    override val ambientC get() = state.ambientC
    override val manifoldKpa get() = if (!running) baroKpa else baroKpa + (if (diesel) boostBar * 100 else -60.0 + engineLoadPct * 0.6)
    override val baroKpa get() = 101.0
    override val railPressureKpa get() = if (!running || !diesel) 0.0 else 28_000.0 + rpm * 20.0
    override val runTimeS get() = if (running) t else 0.0
    override val boostBar get() = if (!running || !diesel) 0.0 else max(0.0, (rpm - 1000.0) / 2000.0 * 0.9)
    override val dpfOutletC get() = if (!running) state.ambientC else 180.0 + engineLoadPct * 3.0
}

/** A fixed snapshot, e.g. the freeze frame stored when a DTC set. */
data class FrozenSignals(
    override val rpm: Double = 2150.0,
    override val speedKmh: Double = 78.0,
    override val coolantC: Double = 91.0,
    override val engineLoadPct: Double = 64.0,
    override val mafGs: Double = 38.5,
    override val shortTrimPct: Double = 3.1,
    override val longTrimPct: Double = 2.3,
    override val o2Volts: Double = 0.62,
    override val intakeC: Double = 27.0,
    override val throttlePct: Double = 31.0,
    override val moduleVolts: Double = 14.2,
    override val fuelLevelPct: Double = 70.0,
    override val ambientC: Double = 16.0,
    override val oilC: Double = 96.0,
    override val manifoldKpa: Double = 168.0,
    override val baroKpa: Double = 101.0,
    override val railPressureKpa: Double = 72_000.0,
    override val runTimeS: Double = 1260.0,
    override val boostBar: Double = 0.67,
    override val dpfOutletC: Double = 365.0,
) : SignalSource

/** Milliseconds from a monotonic source; common code, works on Android and the JVM. */
fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
