package io.carreader.core.link.elm

/** One raw CAN frame as printed by the adapter with headers on: identifier plus data bytes (PCI included). */
class CanFrame(val id: Int, val data: ByteArray) {
    override fun toString(): String = "CanFrame(${id.toString(16)}: ${data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }})"
}

/** A complete ISO-TP message from [sourceId]. */
class IsoTpMessage(val sourceId: Int, val payload: ByteArray)

/** A multi-frame message that started but did not finish (missing consecutive frames, bad sequence). */
data class IncompleteMessage(val sourceId: Int, val expectedLength: Int, val receivedLength: Int, val reason: String)

data class ReassemblyResult(val messages: List<IsoTpMessage>, val incomplete: List<IncompleteMessage>)

/**
 * ISO 15765-2 reassembly of the frames one request produced. Pure and stateless between calls.
 *
 * Frames are grouped by source identifier, so answers from several ECUs to a functional request may be
 * interleaved. Each source can deliver several messages in sequence (e.g. `7F 22 78` then the real answer).
 * Padding bytes after the declared length are ignored. Flow-control frames (PCI 0x3N) are skipped: the
 * adapter sends its own and does not print them, but an ECU-to-ECU exchange could show up on a busy bus.
 *
 * Supported PCI types: single frame (0x0N, plus the CAN FD escape `00 LL`), first frame (0x1N NN) and
 * consecutive frames (0x2N).
 */
object IsoTpReassembler {
    private class Pending(val expected: Int) {
        val buffer = ArrayList<Byte>(expected)
        var nextSeq = 1
    }

    fun reassemble(frames: List<CanFrame>): ReassemblyResult {
        val messages = ArrayList<IsoTpMessage>()
        val incomplete = ArrayList<IncompleteMessage>()
        val pending = LinkedHashMap<Int, Pending>()

        fun abandon(id: Int, reason: String) {
            val p = pending.remove(id) ?: return
            incomplete += IncompleteMessage(id, p.expected, p.buffer.size, reason)
        }

        for (frame in frames) {
            val d = frame.data
            if (d.isEmpty()) continue
            val pci = d[0].toInt() and 0xFF
            when (pci shr 4) {
                0x0 -> {
                    abandon(frame.id, "single frame received before the multi-frame message completed")
                    var len = pci and 0x0F
                    var start = 1
                    if (len == 0 && d.size >= 2) { // CAN FD escape sequence
                        len = d[1].toInt() and 0xFF
                        start = 2
                    }
                    if (len == 0 || start + len > d.size) continue
                    messages += IsoTpMessage(frame.id, d.copyOfRange(start, start + len))
                }
                0x1 -> {
                    abandon(frame.id, "new first frame before the previous message completed")
                    if (d.size < 2) continue
                    val total = ((pci and 0x0F) shl 8) or (d[1].toInt() and 0xFF)
                    val p = Pending(total)
                    for (i in 2 until d.size) if (p.buffer.size < total) p.buffer += d[i]
                    pending[frame.id] = p
                }
                0x2 -> {
                    val p = pending[frame.id] ?: continue
                    val seq = pci and 0x0F
                    if (seq != p.nextSeq) {
                        abandon(frame.id, "consecutive frame sequence ${seq} where ${p.nextSeq} was expected")
                        continue
                    }
                    p.nextSeq = (p.nextSeq + 1) and 0x0F
                    for (i in 1 until d.size) if (p.buffer.size < p.expected) p.buffer += d[i]
                    if (p.buffer.size >= p.expected) {
                        pending.remove(frame.id)
                        messages += IsoTpMessage(frame.id, p.buffer.toByteArray())
                    }
                }
                else -> Unit // flow control or invalid PCI
            }
        }
        for ((id, p) in pending) incomplete += IncompleteMessage(id, p.expected, p.buffer.size, "consecutive frames missing")
        return ReassemblyResult(messages, incomplete)
    }
}
