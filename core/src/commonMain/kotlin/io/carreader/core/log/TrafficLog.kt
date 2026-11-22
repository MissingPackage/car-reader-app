package io.carreader.core.log

/** Port: receives every raw exchange with the adapter, so a session can be exported for remote debugging. */
interface TrafficLog {
    fun record(entry: TrafficEntry)

    object None : TrafficLog {
        override fun record(entry: TrafficEntry) = Unit
    }
}

enum class Direction { TX, RX, INFO, ERROR }

data class TrafficEntry(val timestampMs: Long, val direction: Direction, val text: String)

/** In-memory ring buffer; the app exports its content as a text file. */
class RingTrafficLog(private val capacity: Int = 5000, private val clock: () -> Long) : TrafficLog {
    private val entries = ArrayDeque<TrafficEntry>()

    override fun record(entry: TrafficEntry) {
        synchronizedCompat(this) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    fun snapshot(): List<TrafficEntry> = synchronizedCompat(this) { entries.toList() }

    fun clear() = synchronizedCompat(this) { entries.clear() }
}

/** Common-code stand-in for synchronized(); implemented per platform. */
expect inline fun <T> synchronizedCompat(lock: Any, block: () -> T): T
