package io.carreader.core.guard

import io.carreader.core.link.AdapterInfo
import io.carreader.core.link.BusProtocol
import io.carreader.core.link.DiagnosticLink
import io.carreader.core.link.EcuAddress
import io.carreader.core.link.EcuResponse
import io.carreader.core.link.RequestOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** What a mutating operation does, for checks, confirmation text, the audit log and the permit scope. */
enum class MutationKind(val label: String, val serviceIds: Set<Int>) {
    CLEAR_EMISSION_DTC("Clear OBD-II emission DTCs (service 04)", setOf(0x04)),
    CLEAR_ECU_DTC("Clear ECU DTCs (UDS/KWP 14)", setOf(0x14)),
    WRITE_DATA("Write data by identifier (UDS 2E)", setOf(0x2E)),
    ROUTINE("Routine control (UDS 31)", setOf(0x31)),
}

data class MutationRequest(
    val kind: MutationKind,
    /** The only address the permit lets the mutating service reach. */
    val target: EcuAddress,
    /** Who is affected, for people, e.g. "01 Engine (7E0)" or "All emission ECUs". */
    val targetLabel: String,
    /** One-line human description shown in the confirmation dialog. */
    val description: String,
)

/** Result of one safety check. [Warn] is shown to the user but does not block. */
sealed interface CheckResult {
    data object Pass : CheckResult
    data class Warn(val reason: String) : CheckResult
    data class Fail(val reason: String) : CheckResult
}

/** One precondition a mutation must satisfy. Implementations are composable and individually testable. */
interface SafetyCheck {
    val name: String
    suspend fun evaluate(request: MutationRequest, context: GuardContext): CheckResult
}

/** Read-only view the checks use to probe the vehicle. Read requests pass the gate without a permit. */
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

/** Port: durable record of every mutation attempt and its outcome. Failures to record never change the outcome. */
fun interface AuditLog {
    suspend fun record(event: AuditEvent)
}

data class AuditEvent(
    val timestampMs: Long,
    val request: MutationRequest,
    val outcome: String,
    val checks: List<Pair<String, CheckResult>> = emptyList(),
)

/** User-level switch. Implementations must default to disabled: test builds run on real cars. */
interface GuardPolicy {
    val mutationsEnabled: Boolean
    val allowedKinds: Set<MutationKind>
    fun allows(kind: MutationKind): Boolean = mutationsEnabled && kind in allowedKinds

    /** Never allows anything. */
    object Disabled : GuardPolicy {
        override val mutationsEnabled = false
        override val allowedKinds = emptySet<MutationKind>()
    }
}

/**
 * Policy of the first test builds: only DTC clears can ever be enabled, and only when the user switched the
 * setting on. Writes and routines stay impossible whatever the setting says.
 */
class ClearOnlyPolicy(private val enabled: () -> Boolean) : GuardPolicy {
    override val mutationsEnabled: Boolean get() = enabled()
    override val allowedKinds = setOf(MutationKind.CLEAR_EMISSION_DTC, MutationKind.CLEAR_ECU_DTC)
}

class GuardDenied(val request: MutationRequest?, val reasons: List<String>) :
    Exception("Blocked ${request?.kind ?: "request"}: ${reasons.joinToString("; ")}")

/**
 * Proof, carried in the coroutine context, that [WriteGuard.execute] approved one mutation. It is scoped to
 * the coroutine running the action (not global), to the approved service ids and to the approved target.
 */
class MutationPermit internal constructor(val request: MutationRequest, internal val guard: WriteGuard) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MutationPermit>

    fun covers(target: EcuAddress, sid: Int): Boolean = sid in request.kind.serviceIds && target == request.target
}

/**
 * The single gate for every mutating request. [GatedLink] (handed to all upper layers) refuses state-changing
 * requests unless they run inside [execute], which runs the policy, every [SafetyCheck], the user confirmation,
 * every check again (the dialog may stay open for minutes), and audits every exit.
 */
class WriteGuard(
    private val policy: GuardPolicy,
    private val checks: List<SafetyCheck>,
    private val confirmation: ConfirmationPort,
    private val audit: AuditLog,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()

    suspend fun <T> execute(request: MutationRequest, context: GuardContext, action: suspend () -> T): T {
        check(currentCoroutineContext()[MutationPermit] == null) { "Nested WriteGuard.execute is not allowed" }
        return mutex.withLock {
            var results: List<Pair<String, CheckResult>> = emptyList()
            var executed = false
            try {
                if (!policy.allows(request.kind)) {
                    throw GuardDenied(request, listOf("Operations that modify the vehicle are disabled in Settings"))
                }
                results = runChecks(request, context)
                if (!confirmation.confirm(request, results)) {
                    throw GuardDenied(request, listOf("Cancelled by user"))
                }
                results = runChecks(request, context)
                val value = withContext(MutationPermit(request, this)) { action() }
                executed = true
                safeAudit(AuditEvent(clock(), request, "executed", results))
                value
            } catch (e: Throwable) {
                if (!executed) {
                    val outcome = when (e) {
                        is GuardDenied -> "denied: ${e.reasons.joinToString("; ")}"
                        is CancellationException -> "cancelled"
                        else -> "failed: ${e::class.simpleName}: ${e.message}"
                    }
                    safeAudit(AuditEvent(clock(), request, outcome, results))
                }
                throw e
            }
        }
    }

    private suspend fun runChecks(request: MutationRequest, context: GuardContext): List<Pair<String, CheckResult>> {
        val results = checks.map { check ->
            val result = try {
                check.evaluate(request, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CheckResult.Fail("${check.name}: could not be verified (${e.message})")
            }
            check.name to result
        }
        val failures = results.mapNotNull { (_, r) -> (r as? CheckResult.Fail)?.reason }
        if (failures.isNotEmpty()) throw GuardDenied(request, failures)
        return results
    }

    internal suspend fun recordGateDenial(target: EcuAddress, sid: Int) {
        val request = MutationRequest(
            MutationKind.entries.firstOrNull { sid in it.serviceIds } ?: MutationKind.WRITE_DATA,
            target,
            "0x${target.requestId.toString(16).uppercase()}",
            "Unguarded state-changing request, service 0x${sid.toString(16).uppercase()}",
        )
        safeAudit(AuditEvent(clock(), request, "denied at gate"))
    }

    private suspend fun safeAudit(event: AuditEvent) {
        try {
            audit.record(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // An audit failure must never turn a successful operation into a reported failure (or vice versa).
        }
    }
}

/**
 * Decorator over the adapter driver: the only [DiagnosticLink] the rest of the app receives.
 * Implemented explicitly (no delegation) so any new port method must be gated consciously.
 */
class GatedLink(private val inner: DiagnosticLink, private val guard: WriteGuard) : GatedDiagnosticLink {
    override suspend fun initialize() = inner.initialize()
    override val busProtocol get() = inner.busProtocol
    override val adapterInfo get() = inner.adapterInfo
    override suspend fun readBatteryVoltage() = inner.readBatteryVoltage()
    override suspend fun close() = inner.close()

    override suspend fun request(target: EcuAddress, payload: ByteArray, options: RequestOptions): List<EcuResponse> {
        require(payload.isNotEmpty()) { "Empty diagnostic request" }
        if (!ReadOnlyServices.isReadOnly(payload)) {
            val sid = payload[0].toInt() and 0xFF
            val permit = currentCoroutineContext()[MutationPermit]
            if (permit == null || permit.guard !== guard || !permit.covers(target, sid)) {
                guard.recordGateDenial(target, sid)
                throw GuardDenied(
                    permit?.request,
                    listOf("Service 0x${sid.toString(16).uppercase()} to 0x${target.requestId.toString(16).uppercase()} is not covered by an approved operation"),
                )
            }
        }
        return inner.request(target, payload, options)
    }
}

/** Marker: a link whose state-changing requests are gated. Protocol clients should require this type. */
interface GatedDiagnosticLink : DiagnosticLink

/**
 * Allow-list of requests that cannot change ECU state. Everything else needs a [MutationPermit].
 * Allow-list rather than deny-list: an unknown or manufacturer service is treated as mutating.
 */
object ReadOnlyServices {
    private val readServices = setOf(
        0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A, // SAE J1979 read services
        0x19, // UDS ReadDTCInformation
        0x22, // UDS ReadDataByIdentifier
        0x3E, // TesterPresent
        0x17, 0x18, // KWP2000 ReadStatusOfDTC / ReadDTCByStatus
        0x1A, // KWP2000 ReadEcuIdentification
        0x21, // KWP2000 ReadDataByLocalIdentifier
    )

    /** DiagnosticSessionControl sub-functions that only open read sessions (suppress-response bit masked). */
    private val readSessions = setOf(
        0x01, // UDS default
        0x03, // UDS extended
        0x81, // KWP2000 default diagnostic mode [VERIFY against ISO 14230-3 before KWP support]
        0x89, // KWP2000 VAG extended diagnostic mode [VERIFY]
    )

    fun isReadOnly(payload: ByteArray): Boolean {
        val sid = payload[0].toInt() and 0xFF
        if (sid in readServices) return true
        if (sid == 0x10 && payload.size >= 2) {
            val sub = payload[1].toInt() and 0xFF
            // UDS sub-functions use bit 7 as suppressPosRspMsgIndicationBit; KWP modes use the whole byte.
            return sub in readSessions || (sub and 0x7F) in setOf(0x01, 0x03)
        }
        return false
    }
}
