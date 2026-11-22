package io.carreader.core.guard

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a mutating operation does, for checks, confirmation text and the audit log. */
enum class MutationKind(val label: String) {
    CLEAR_EMISSION_DTC("Clear OBD-II emission DTCs (service 04)"),
    CLEAR_ECU_DTC("Clear ECU DTCs (UDS 14 / KWP 14)"),
    WRITE_DATA("Write data by identifier (UDS 2E)"),
    ROUTINE("Routine control (UDS 31)"),
}

data class MutationRequest(
    val kind: MutationKind,
    /** Who is affected, e.g. "Engine (7E0)" or "All emission ECUs". */
    val target: String,
    /** One-line human description shown in the confirmation dialog. */
    val description: String,
)

/** Result of one safety check. */
sealed interface CheckResult {
    data object Pass : CheckResult
    data class Fail(val reason: String) : CheckResult
}

/** One precondition a mutation must satisfy. Implementations are composable and individually testable. */
interface SafetyCheck {
    val name: String
    suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult
}

/** Read-only view the checks use to probe the vehicle. It goes through the raw link, never the gate. */
interface GuardContext {
    val adapter: AdapterInfo?
    val busProtocol: BusProtocol?
    suspend fun vehicleSpeedKmh(): Int?
    suspend fun engineRpm(): Int?
    suspend fun batteryVoltage(): Double?
}

/** Port: asks the user to confirm a mutation. The Android UI implements it with a dialog. */
fun interface ConfirmationPort {
    suspend fun confirm(request: MutationRequest, checks: List<Pair<String, CheckResult>>): Boolean
}

/** Port: durable record of every mutation attempt and its outcome. */
fun interface AuditLog {
    fun record(event: AuditEvent)
}

data class AuditEvent(
    val timestampMs: Long,
    val request: MutationRequest,
    val outcome: String,
    val checks: List<Pair<String, CheckResult>> = emptyList(),
)

/** User-level switch. Defaults to disabled: the first test builds run on real cars. */
interface GuardPolicy {
    val mutationsEnabled: Boolean
    fun allows(kind: MutationKind): Boolean = mutationsEnabled && kind in allowedKinds
    val allowedKinds: Set<MutationKind>
}

class GuardDenied(val request: MutationRequest, val reasons: List<String>) :
    Exception("Blocked ${request.kind}: ${reasons.joinToString("; ")}")

/**
 * The single gate for every mutating request. [GatedLink] (handed to all upper layers) refuses mutating
 * service ids unless they run inside [execute], which first runs the policy, every [SafetyCheck], then the
 * user confirmation, and audits the outcome.
 */
class WriteGuard(
    private val policy: GuardPolicy,
    private val checks: List<SafetyCheck>,
    private val confirmation: ConfirmationPort,
    private val audit: AuditLog,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    internal var permit: MutationRequest? = null
        private set

    suspend fun <T> execute(request: MutationRequest, context: GuardContext, action: suspend () -> T): T = mutex.withLock {
        if (!policy.allows(request.kind)) {
            audit.record(AuditEvent(clock(), request, "denied: disabled in settings"))
            throw GuardDenied(request, listOf("Operations that modify the vehicle are disabled in Settings"))
        }
        val results = checks.map { it.name to it.evaluate(request, context) }
        val failures = results.mapNotNull { (_, r) -> (r as? CheckResult.Fail)?.reason }
        if (failures.isNotEmpty()) {
            audit.record(AuditEvent(clock(), request, "denied: checks failed", results))
            throw GuardDenied(request, failures)
        }
        if (!confirmation.confirm(request, results)) {
            audit.record(AuditEvent(clock(), request, "cancelled by user", results))
            throw GuardDenied(request, listOf("Cancelled by user"))
        }
        permit = request
        try {
            val value = action()
            audit.record(AuditEvent(clock(), request, "executed", results))
            value
        } catch (t: Throwable) {
            audit.record(AuditEvent(clock(), request, "failed: ${t.message}", results))
            throw t
        } finally {
            permit = null
        }
    }

    /** Called by [GatedLink]; true only inside [execute] after every check passed. */
    internal fun isPermitted(): Boolean = permit != null
}

/**
 * Decorator over the adapter driver: the only [DiagnosticLink] the rest of the app receives.
 * Mutating service ids are refused unless a [WriteGuard.execute] block is active.
 */
class GatedLink(private val inner: DiagnosticLink, private val guard: WriteGuard) : DiagnosticLink by inner {
    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> {
        if (payload.isNotEmpty() && MutatingServices.isMutating(payload) && !guard.isPermitted()) {
            throw GuardDenied(
                MutationRequest(MutationKind.WRITE_DATA, "0x${target.requestId.toString(16)}", "Unguarded mutating request"),
                listOf("Service 0x${(payload[0].toInt() and 0xFF).toString(16)} must go through WriteGuard"),
            )
        }
        return inner.request(target, payload, options)
    }
}

/** Service ids that can change ECU state. Deny-list kept deliberately broad. */
object MutatingServices {
    private val ids = setOf(
        0x04, // OBD clear DTC / MIL
        0x08, // OBD on-board control
        0x11, // UDS ECUReset
        0x14, // UDS/KWP ClearDiagnosticInformation
        0x27, // SecurityAccess
        0x28, // CommunicationControl
        0x29, // Authentication
        0x2E, // WriteDataByIdentifier
        0x2F, // InputOutputControlByIdentifier
        0x31, // RoutineControl
        0x34, 0x35, 0x36, 0x37, 0x38, // transfer / download / upload
        0x3B, // KWP WriteDataByLocalIdentifier
        0x3D, // WriteMemoryByAddress
        0x85, // ControlDTCSetting
        0x87, // LinkControl
    )

    fun isMutating(payload: ByteArray): Boolean {
        val sid = payload[0].toInt() and 0xFF
        if (sid !in ids) return false
        // DiagnosticSessionControl (0x10) is not listed: default/extended sessions are needed for reads.
        return true
    }

    fun isMutatingSid(sid: Int): Boolean = sid in ids
}
