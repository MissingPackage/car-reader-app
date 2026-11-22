package io.carreader.core.brand

/** What a scan says about a diagnostic firewall (secure gateway) between the OBD port and the control units. */
enum class GatewayFinding {
    /** Nothing points to a gateway blocking reads. */
    NONE,

    /**
     * A unit other than the always-open ones refused its probe or DTC read with 0x33 securityAccessDenied or
     * 0x34 authenticationRequired.
     */
    ACCESS_DENIED,

    /**
     * Only units that are always reachable (the OBD emission units) answered, or none answered although the car
     * answers standard OBD-II, while every other unit probed stayed silent. A gateway that drops unauthenticated
     * requests looks like this, but so do wrong addresses or an ignition that is not fully on, so it is a
     * suspicion, not a proof.
     */
    ONLY_OPEN_UNITS_ANSWERED,
}

/**
 * Brand-agnostic secure gateway heuristics over the reports of one scan. Brands decide which units count as
 * always reachable ([openUnits], request ids) and how to explain the finding to the user.
 */
object SecureGatewayCheck {
    /** Fewer silent units than this is not enough to suspect a gateway (a car may simply lack them). */
    const val MIN_SILENT_UNITS = 3

    /**
     * [obdReachable]: the car answered standard OBD-II on this connection (e.g. the VIN was read), so a scan
     * where no brand unit answers at all is still informative.
     */
    fun assess(reports: List<EcuReport>, openUnits: Set<Int>, obdReachable: Boolean): GatewayFinding {
        if (reports.any { it.present && it.accessDenied && it.ecu.address.requestId !in openUnits }) return GatewayFinding.ACCESS_DENIED
        val present = reports.filter { it.present }
        if (present.isEmpty() && !obdReachable) return GatewayFinding.NONE
        val others = reports.filter { it.ecu.address.requestId !in openUnits }
        val silent = others.count { !it.present }
        val opened = present.all { it.ecu.address.requestId in openUnits }
        return if (opened && silent >= MIN_SILENT_UNITS && silent == others.size) {
            GatewayFinding.ONLY_OPEN_UNITS_ANSWERED
        } else {
            GatewayFinding.NONE
        }
    }
}
