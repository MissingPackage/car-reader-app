package io.carreader.app.session

import io.carreader.brands.stellantis.StellantisBrandModule
import io.carreader.brands.vag.VagBrandModule
import io.carreader.core.brand.BrandRegistry
import io.carreader.core.guard.AuditLog
import io.carreader.core.guard.ConfirmationPort
import io.carreader.core.guard.GuardPolicy
import io.carreader.core.log.TrafficLog
import io.carreader.core.session.DefaultVehicleSession
import io.carreader.core.session.VehicleSession
import io.carreader.core.transport.Transport

/** Everything a session needs besides the transport; built once by the app container. */
class SessionDependencies(
    val policy: GuardPolicy,
    val confirmation: ConfirmationPort,
    val audit: AuditLog,
    val traffic: TrafficLog,
    val clock: () -> Long,
)

/**
 * The single place where the app creates a [VehicleSession]. [PreviewVehicleSession] stays for Compose previews
 * and JVM tests.
 */
object SessionFactory {
    /** Brand modules shipped in this build. */
    fun brandRegistry(): BrandRegistry = BrandRegistry(listOf(VagBrandModule(), StellantisBrandModule()))

    fun create(transport: Transport, deps: SessionDependencies): VehicleSession =
        DefaultVehicleSession(transport, brandRegistry(), deps.policy, deps.confirmation, deps.audit, deps.traffic, deps.clock)
}
