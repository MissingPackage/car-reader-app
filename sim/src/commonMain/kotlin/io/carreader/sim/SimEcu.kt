package io.carreader.sim

/**
 * A simulated control unit on the bus. [handle] receives a request payload (service id + parameters, no
 * transport framing) and returns zero or more response payloads in order, so it can answer `7F xx 78`
 * (response pending) before the real answer. An empty list means the ECU stays silent.
 */
interface SimEcu {
    val name: String
    /** CAN id the ECU listens on (11 or 29 bit). */
    val requestId: Int
    /** CAN id the ECU answers from. */
    val responseId: Int
    /** Answers the OBD functional broadcast (0x7DF / 18DB33F1), and every request on a K-line bus. */
    val answersObdFunctional: Boolean
    /** Source address in K-line / J1850 headers (0x10 = engine). */
    val serialAddress: Int get() = 0x10

    fun handle(request: ByteArray): List<ByteArray>
}

/**
 * One reusable piece of ECU behaviour (OBD services, UDS services...). [accepts] decides by request whether
 * this behaviour owns it, so several behaviours can be composed into one ECU with [ComposedEcu].
 */
interface EcuBehavior {
    fun accepts(request: ByteArray): Boolean
    fun respond(request: ByteArray): List<ByteArray>
}

/**
 * An ECU made of behaviours: the first that accepts a request answers it; none → `7F <sid> 11`
 * (serviceNotSupported). A VAG engine ECU is `ComposedEcu(0x7E0, 0x7E8, ObdEngineEcu(...), UdsEcu(...))`.
 */
class ComposedEcu(
    override val name: String,
    override val requestId: Int,
    override val responseId: Int,
    val behaviors: List<EcuBehavior>,
    override val answersObdFunctional: Boolean = behaviors.any { it is ObdEngineEcu },
    override val serialAddress: Int = 0x10,
) : SimEcu {
    constructor(name: String, requestId: Int, responseId: Int, vararg behaviors: EcuBehavior) :
        this(name, requestId, responseId, behaviors.toList())

    override fun handle(request: ByteArray): List<ByteArray> {
        if (request.isEmpty()) return emptyList()
        val owner = behaviors.firstOrNull { it.accepts(request) }
            ?: return listOf(negative(request.u8(0), Nrcs.SERVICE_NOT_SUPPORTED))
        return owner.respond(request)
    }

    inline fun <reified T : EcuBehavior> behavior(): T? = behaviors.filterIsInstance<T>().firstOrNull()
}

/** ISO 14229 negative response codes the simulator uses. */
object Nrcs {
    const val SERVICE_NOT_SUPPORTED = 0x11
    const val SUB_FUNCTION_NOT_SUPPORTED = 0x12
    const val INCORRECT_LENGTH = 0x13
    const val CONDITIONS_NOT_CORRECT = 0x22
    const val REQUEST_OUT_OF_RANGE = 0x31
    const val SECURITY_ACCESS_DENIED = 0x33
    const val RESPONSE_PENDING = 0x78
    const val SERVICE_NOT_SUPPORTED_IN_SESSION = 0x7F
}

fun negative(sid: Int, nrc: Int): ByteArray = bytesOf(0x7F, sid, nrc)

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun Int.u16(): ByteArray = bytesOf((this shr 8) and 0xFF, this and 0xFF)
/** "P0401" → 0x0401 via the core codec; fails loudly on a typo in a scenario. */
internal fun dtcCode(code: String): Int = requireNotNull(io.carreader.core.obd2.DtcCodec.parse(code)) { "Bad DTC '$code'" }
