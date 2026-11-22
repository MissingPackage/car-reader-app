package io.carreader.core.link.elm

import io.carreader.core.link.AdapterChip
import io.carreader.core.link.AdapterInfo

/** Raw identity answers collected at init. Null = the adapter answered `?` or nothing. */
data class AdapterIdentity(
    val ati: String?,
    val at1: String?,
    val sti: String?,
    val stdi: String?,
)

/**
 * What the driver may rely on for this adapter. Kept separate from [AdapterInfo] (the public summary) so
 * chip-specific optimizations (STN `STPX`, response-count hints, larger buffers) can be switched on here
 * without touching the request code paths.
 */
data class AdapterProfile(
    val chip: AdapterChip,
    val elmVersion: String?,
    val supportsStCommands: Boolean,
    /** `ATFCSH`/`ATFCSD`/`ATFCSM`: required for physical requests outside 0x7E0-0x7E7. */
    val supportsFlowControl: Boolean = true,
    /** `ATCRA`: receive filter. Without it the driver filters in software. */
    val supportsReceiveFilter: Boolean = true,
    /** `ATST`: response timeout. */
    val supportsTimeout: Boolean = true,
    /** `ATCP`: CAN priority bits for 29-bit headers. */
    val supportsCanPriority: Boolean = true,
    val notes: List<String> = emptyList(),
) {
    companion object {
        private val ELM_VERSION = Regex("ELM327\\s*v?(\\d+\\.\\d+[a-z]?)", RegexOption.IGNORE_CASE)
        private val SIM_MARKER = Regex("SIMULATOR", RegexOption.IGNORE_CASE)

        /**
         * Classifies the chip. STN: `STI` answered. SIMULATOR: any identity string says so. Otherwise ELM327:
         * v1.5 and v2.1 never shipped as genuine firmware in volume and are the classic clone identities;
         * genuine ELM327 is end-of-life (2022, last firmware v2.3), so any ELM327 identity without ST support
         * is reported with a "likely clone" note, never as a certainty.
         */
        fun classify(identity: AdapterIdentity): AdapterProfile {
            val version = identity.ati?.let { ELM_VERSION.find(it)?.groupValues?.get(1) }
            val all = listOfNotNull(identity.ati, identity.at1, identity.sti, identity.stdi)
            if (all.any { SIM_MARKER.containsMatchIn(it) }) {
                return AdapterProfile(AdapterChip.SIMULATOR, version, supportsStCommands = identity.sti != null)
            }
            if (identity.sti != null) {
                return AdapterProfile(AdapterChip.STN, version, supportsStCommands = true)
            }
            if (version == null) {
                return AdapterProfile(
                    AdapterChip.UNKNOWN, null, supportsStCommands = false,
                    notes = listOf("Adapter identity not recognized as ELM327-compatible: '${identity.ati ?: "none"}'"),
                )
            }
            val cloneVersions = setOf("1.5", "2.1")
            return if (version.lowercase() in cloneVersions) {
                AdapterProfile(
                    AdapterChip.ELM327_CLONE, version, supportsStCommands = false,
                    notes = listOf("ELM327 v$version without ST commands: likely a clone (not certain)"),
                )
            } else {
                AdapterProfile(
                    AdapterChip.ELM327_GENUINE, version, supportsStCommands = false,
                    notes = listOf(
                        "Reports ELM327 v$version. Genuine ELM327 chips are end-of-life since 2022 (last firmware v2.3) " +
                            "and clones copy this identity: likely a clone, it cannot be verified",
                    ),
                )
            }
        }
    }

    val certified: Boolean get() = chip == AdapterChip.STN || chip == AdapterChip.SIMULATOR

    fun toAdapterInfo(identity: AdapterIdentity, supportsVoltage: Boolean, supportsHeaders: Boolean, extraNotes: List<String>): AdapterInfo {
        val name = identity.stdi ?: identity.at1?.takeIf { it.isNotBlank() } ?: identity.ati ?: "Unknown adapter"
        val firmware = identity.sti ?: elmVersion?.let { "ELM327 v$it" }
        return AdapterInfo(
            identity = name,
            firmware = firmware,
            chip = chip,
            supportsStCommands = supportsStCommands,
            supportsVoltage = supportsVoltage,
            supportsHeaders = supportsHeaders,
            certified = certified,
            notes = notes + extraNotes,
        )
    }
}
