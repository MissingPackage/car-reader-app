package io.carreader.core.obd2

/**
 * Use-case port for standard SAE J1979 / ISO 15031-5 diagnostics. Implemented over a DiagnosticLink by
 * [Obd2Service]; the UI and the session depend on this interface only.
 */
interface Obd2Port {
    /** Service 01 PIDs 00/20/40/... bitmaps, merged across answering ECUs. */
    suspend fun supportedPids(): Set<Int>

    /** Service 01 single PID; one value per answering ECU. Empty if unsupported. */
    suspend fun readPid(pid: PidDefinition): List<LiveValue>

    suspend fun readiness(): ReadinessReport?

    /** Services 03 / 07 / 0A. */
    suspend fun readDtcs(source: DtcSource): List<Dtc>

    /** Service 02 frame 0: DTC that triggered it (PID 02) plus every supported PID in the frame. */
    suspend fun freezeFrame(frame: Int = 0): FreezeFrame?

    /** Service 09: VIN (PID 02), CALID (04), CVN (06), ECU name (0A). */
    suspend fun identification(): VehicleIdentification

    /** Service 04. Must be called inside WriteGuard.execute; the gated link refuses it otherwise. */
    suspend fun clearEmissionDtcs(): Boolean
}
