package io.carreader.app.transport

import io.carreader.core.transport.Transport
import io.carreader.sim.Scenarios
import io.carreader.sim.SimTransport

/** Demo mode: an in-process simulated adapter and car from the `sim` module. */
object DemoTransports {
    fun create(car: DemoCar): Transport = when (car) {
        DemoCar.PASSAT -> SimTransport(Scenarios.passatB8())
        DemoCar.Q5 -> SimTransport(Scenarios.audiQ5())
    }
}
