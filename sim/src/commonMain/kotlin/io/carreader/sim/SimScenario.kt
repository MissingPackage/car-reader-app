package io.carreader.sim

import io.carreader.core.link.BusProtocol

/**
 * How the simulated adapter identifies itself and which commands it lacks.
 * [unsupported] holds command prefixes (no spaces, upper case, e.g. "ATCRA", "AT@1") answered with `?`.
 * ST commands are answered only when [sti] is set.
 */
data class SimAdapterProfile(
    val ati: String,
    val at1: String?,
    val sti: String? = null,
    val stdi: String? = null,
    val unsupported: Set<String> = emptySet(),
) {
    companion object {
        /** The app's own simulator identity: classified as SIMULATOR (certified) by the driver. */
        val SIMULATOR = SimAdapterProfile(ati = "ELM327 v2.3", at1 = "Car Reader Simulator")

        /** Behaves like an OBDLink MX+ (STN chip). Use it in tests of STN-specific paths, never in demo mode. */
        val OBDLINK_MX = SimAdapterProfile(
            ati = "ELM327 v1.4b",
            at1 = "OBDLink MX+",
            sti = "STN2255 v5.10.3",
            stdi = "OBDLink MX+ r2",
        )

        /** A typical cheap "v1.5" clone: no ST commands, no AT@1, no adaptive timing, no CAN receive filter. */
        val CLONE_V15 = SimAdapterProfile(
            ati = "ELM327 v1.5",
            at1 = null,
            unsupported = setOf("AT@1", "AT@2", "ATAT", "ATCRA", "ATCP", "ATPP", "ATCSM", "ATKW"),
        )

        /** A "v2.1" clone: no ST commands, otherwise the usual AT set. */
        val CLONE_V21 = SimAdapterProfile(ati = "ELM327 v2.1", at1 = "OBDII to RS232 Interpreter")
    }
}

/**
 * A simulated car behind a simulated adapter. [vehicle] is live state that ECUs read (and tests or the
 * demo UI may change); [ecus] are reached by CAN id or, on serial buses, all answer the OBD broadcast.
 * [canPadding] fills unused CAN data bytes (VAG pads with 0xAA, many others with 0x00 or 0x55).
 */
class SimScenario(
    val name: String,
    val description: String,
    val protocol: BusProtocol,
    val adapter: SimAdapterProfile,
    val ecus: List<SimEcu>,
    val vehicle: SimVehicleState = SimVehicleState(),
    val canPadding: Int = 0x00,
    /** The live values the ECUs report, for tests and UI that want to compare against what was read. */
    val signals: SignalSource? = null,
) {
    init {
        require(protocol != BusProtocol.UNKNOWN) { "Scenario needs a concrete protocol" }
    }

    fun ecu(requestId: Int): SimEcu? = ecus.firstOrNull { it.requestId == requestId }

    /** The behaviour of type [T] in the ECU at [requestId], e.g. to inspect DTCs after a clear. */
    inline fun <reified T : EcuBehavior> behavior(requestId: Int): T? = (ecu(requestId) as? ComposedEcu)?.behavior<T>()
}
