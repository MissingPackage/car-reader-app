package io.carreader.core.link.elm

import io.carreader.core.link.EcuAddress
import io.carreader.core.link.LinkException

/** One adapter setting the driver wants: [key] groups settings that overwrite each other. */
data class ElmSetting(val key: Key, val value: String, val command: String) {
    enum class Key { HEADER, PRIORITY, RECEIVE_FILTER, FC_HEADER, FC_DATA, FC_MODE, TIMEOUT }
}

/**
 * Pure planner for CAN addressing and timing commands, with a cache of what the adapter currently has, so
 * `ATSH` & co. are sent only when they change. Reset it whenever the adapter is reset (`ATZ`).
 *
 * Rules (ELM327 datasheet conventions):
 * - functional 0x7DF (or 29-bit 18DB33F1): `ATAR` (automatic receive) and `ATFCSM0` (automatic flow control);
 * - physical 0x7E0-0x7E7: automatic flow control works (the adapter answers 0x7E8-0x7EF itself), receive
 *   filter `ATCRA <response id>` when known;
 * - any other physical id: explicit flow control `ATFCSH <request id>`, `ATFCSD 300000`, `ATFCSM1`, then
 *   `ATCRA <response id>`. Without it the ECU sends only the first frame of a long answer;
 * - 29-bit: `ATCP <priority>` + `ATSH <low 24 bits>`, automatic flow control (the adapter handles 18DAF1xx).
 * - timing: `ATST32` (about 200 ms, the adapter default) for OBD services 0x01-0x0A, longer for UDS/KWP
 *   requests (`ATSTFF` = about 1 s) since ECUs take longer to answer them.
 */
class ElmAddressing {
    private val current = HashMap<ElmSetting.Key, String>()

    /** Forget every cached value, then record the adapter defaults after `ATZ`. */
    fun reset() {
        current.clear()
        current[ElmSetting.Key.RECEIVE_FILTER] = AUTO
        current[ElmSetting.Key.FC_MODE] = "0"
        current[ElmSetting.Key.TIMEOUT] = "32"
    }

    /** Record that [setting] is now active (call after the adapter answered OK). */
    fun applied(setting: ElmSetting) {
        current[setting.key] = setting.value
    }

    /** Forget one key, e.g. after the adapter rejected it, so it is retried next time. */
    fun forget(key: ElmSetting.Key) {
        current.remove(key)
    }

    /** Settings needed before sending [payload] to [target] on a CAN bus; only the ones that change. */
    fun plan(target: EcuAddress, payload: ByteArray, timeoutMs: Long, extendedBus: Boolean, profile: AdapterProfile): List<ElmSetting> {
        val wanted = ArrayList<ElmSetting>()
        val resolved = resolve(target, extendedBus)
        if (resolved.extended) {
            val id = resolved.requestId
            val priority = ((id ushr 24) and 0x1F).toString(16).uppercase().padStart(2, '0')
            val low = (id and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
            if (profile.supportsCanPriority) wanted += ElmSetting(ElmSetting.Key.PRIORITY, priority, "ATCP$priority")
            wanted += ElmSetting(ElmSetting.Key.HEADER, low, "ATSH$low")
            wanted += fcMode("0")
            wanted += receiveFilter(if (resolved.functional) null else resolved.responseId?.let { it.toString(16).uppercase().padStart(8, '0') }, profile)
        } else {
            val id = resolved.requestId
            val header = id.toString(16).uppercase().padStart(3, '0')
            wanted += ElmSetting(ElmSetting.Key.HEADER, header, "ATSH$header")
            when {
                resolved.functional -> {
                    wanted += receiveFilter(null, profile)
                    wanted += fcMode("0")
                }
                id in OBD_PHYSICAL -> {
                    wanted += fcMode("0")
                    wanted += receiveFilter(resolved.responseId?.toString(16)?.uppercase()?.padStart(3, '0'), profile)
                }
                else -> {
                    if (!profile.supportsFlowControl) {
                        throw LinkException("Adapter cannot set CAN flow control (ATFCSH): ECU 0x$header is unreachable with it")
                    }
                    wanted += ElmSetting(ElmSetting.Key.FC_HEADER, header, "ATFCSH$header")
                    wanted += ElmSetting(ElmSetting.Key.FC_DATA, "300000", "ATFCSD300000")
                    wanted += fcMode("1")
                    wanted += receiveFilter(resolved.responseId?.toString(16)?.uppercase()?.padStart(3, '0'), profile)
                }
            }
        }
        if (profile.supportsTimeout) {
            val st = timeoutFor(payload, timeoutMs)
            wanted += ElmSetting(ElmSetting.Key.TIMEOUT, st, "ATST$st")
        }
        return wanted.filter { current[it.key] != it.value }
    }

    private fun fcMode(mode: String) = ElmSetting(ElmSetting.Key.FC_MODE, mode, "ATFCSM$mode")

    private fun receiveFilter(id: String?, profile: AdapterProfile): ElmSetting =
        if (id == null || !profile.supportsReceiveFilter) ElmSetting(ElmSetting.Key.RECEIVE_FILTER, AUTO, "ATAR")
        else ElmSetting(ElmSetting.Key.RECEIVE_FILTER, id, "ATCRA$id")

    /** A target after mapping 11-bit OBD ids onto 29-bit ones when the bus is extended. */
    data class Resolved(val requestId: Int, val responseId: Int?, val extended: Boolean, val functional: Boolean)

    companion object {
        const val AUTO = "auto"
        val OBD_PHYSICAL = 0x7E0..0x7E7
        const val FUNCTIONAL_11 = 0x7DF
        const val FUNCTIONAL_29 = 0x18DB33F1

        fun resolve(target: EcuAddress, extendedBus: Boolean): Resolved {
            if (!extendedBus) {
                if (target.extendedId) throw LinkException("29-bit target 0x${target.requestId.toString(16)} on an 11-bit CAN bus")
                return Resolved(target.requestId, target.responseId, false, target.requestId == FUNCTIONAL_11)
            }
            if (target.extendedId) {
                return Resolved(target.requestId, target.responseId ?: physicalResponse29(target.requestId), true, target.requestId == FUNCTIONAL_29)
            }
            // 11-bit OBD addresses used by the OBD layer, mapped onto ISO 15765-4 29-bit normal fixed addressing.
            return when (target.requestId) {
                FUNCTIONAL_11 -> Resolved(FUNCTIONAL_29, null, true, true)
                0x7E0 -> Resolved(0x18DA10F1, 0x18DAF110, true, false)
                0x7E1 -> Resolved(0x18DA18F1, 0x18DAF118, true, false)
                else -> throw LinkException("No 29-bit mapping for 11-bit id 0x${target.requestId.toString(16)}")
            }
        }

        /** 18DA<tgt><src> → 18DA<src><tgt>. */
        private fun physicalResponse29(requestId: Int): Int? {
            if ((requestId ushr 16) and 0xFF != 0xDA) return null
            val tgt = (requestId ushr 8) and 0xFF
            val src = requestId and 0xFF
            return (requestId and 0xFFFF0000.toInt()) or (src shl 8) or tgt
        }

        /** ATST value in 4 ms units, as two hex digits. */
        fun timeoutFor(payload: ByteArray, timeoutMs: Long): String {
            val sid = if (payload.isEmpty()) 0 else payload[0].toInt() and 0xFF
            if (sid in 0x01..0x0A) return "32"
            val units = (timeoutMs / 4).coerceIn(0x32L, 0xFFL).toInt()
            return units.toString(16).uppercase().padStart(2, '0')
        }
    }
}
