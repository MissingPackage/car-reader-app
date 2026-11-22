package io.carreader.app.transport

import io.carreader.core.log.Direction
import io.carreader.core.log.TrafficEntry
import io.carreader.core.log.TrafficLog
import io.carreader.core.transport.TransportException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking call (socket connect) that ignores thread interruption, and calls [abort] when the calling
 * coroutine is cancelled while [block] is still running. Closing the socket from that sibling coroutine is what
 * makes the blocked call return: `invokeOnCompletion` would fire only after the call had returned by itself.
 * [abort] is not called when [block] finishes first.
 */
internal suspend fun <T> abortOnCancel(abort: () -> Unit, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) runCatching(abort)
        }
    }
    try {
        block()
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}

/** Writes transport-level events (which socket worked, BLE services, MTU) to the diagnostics log. */
fun interface TransportLog {
    fun info(text: String)

    companion object {
        val None = TransportLog { }

        fun of(traffic: TrafficLog, clock: () -> Long) = TransportLog { traffic.record(TrafficEntry(clock(), Direction.INFO, it)) }
    }
}

/** [io.carreader.core.transport.Transport.read] on top of a channel fed by a reader (BLE notifications, SPP reader). */
internal object ChannelReader {
    /**
     * Waits at most [timeoutMs] for the first chunk, then appends whatever else is already queued. Returns an
     * empty array on timeout; throws [TransportException] once the channel is closed (connection lost).
     */
    suspend fun read(channel: ReceiveChannel<ByteArray>, timeoutMs: Long, closedMessage: String): ByteArray {
        val first: ByteArray = if (timeoutMs <= 0) {
            val r = channel.tryReceive()
            r.getOrNull() ?: run {
                if (r.isClosed) throw closed(r.exceptionOrNull(), closedMessage)
                return ByteArray(0)
            }
        } else {
            try {
                withTimeoutOrNull(timeoutMs) { channel.receive() } ?: return ByteArray(0)
            } catch (e: TransportException) {
                throw e
            } catch (e: ClosedReceiveChannelException) {
                throw closed(e, closedMessage)
            }
        }
        var result = first
        while (true) {
            val more = channel.tryReceive().getOrNull() ?: break
            result += more
        }
        return result
    }

    private fun closed(cause: Throwable?, message: String): TransportException =
        cause as? TransportException ?: TransportException(message, cause)
}
