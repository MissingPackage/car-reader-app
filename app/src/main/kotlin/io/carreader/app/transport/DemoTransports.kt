package io.carreader.app.transport

import io.carreader.core.transport.Transport
import io.carreader.sim.Scenarios
import io.carreader.sim.SimTransport

/** Demo mode: an in-process simulated adapter and car from the `sim` module. */
object DemoTransports {
    /** How much the rolled-back demo Passat's cluster hides. */
    const val ROLLBACK_KM = 60_000

    fun create(car: DemoCar): Transport = when (car) {
        DemoCar.PASSAT -> SimTransport(Scenarios.passatB8())
        DemoCar.Q5 -> SimTransport(Scenarios.audiQ5())
        DemoCar.PASSAT_ROLLED_BACK -> SimTransport(Scenarios.passatB8(clusterRollbackKm = ROLLBACK_KM))
    }
}
