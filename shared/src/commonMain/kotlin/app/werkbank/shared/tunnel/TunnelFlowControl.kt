package app.werkbank.shared.tunnel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

/**
 * Credit-based flow control for one tunnel connection, modeled on HTTP/2. Each side runs one of these.
 *
 * Without it a sender pushes data as fast as its source allows. The data piles up in socket buffers
 * (every ping waits behind it) and in the receiver's per-stream queues (a slow browser or local
 * service has to be aborted). With it, a sender keeps at most [CONNECTION_WINDOW] bytes unacknowledged
 * on the whole connection and [STREAM_WINDOW] bytes per stream:
 * - connection credit comes back as soon as the peer's reader loop took the bytes off the socket, so
 *   it bounds what sits in the pipe;
 * - stream credit comes back once the stream's consumer handled them, so a slow consumer pauses its
 *   own stream only.
 *
 * Flow-controlled are HTTP body chunks and WebSocket frames in both directions, counted as payload
 * bytes (text frames by [String.length], which both sides compute from the same string). A sender
 * waits for credit above zero and may then overdraw by one chunk, so frames never have to be split.
 *
 * Only active when both sides support it: the CLI announces it with [HEADER], the server confirms
 * with [ServerMessage.FlowControl] as its first message. Otherwise [enabled] is false and every
 * call here is a no-op, which is the old unbounded behavior.
 */
@OptIn(ExperimentalAtomicApi::class)
class TunnelFlowControl(val enabled: Boolean) {

    private val connectionWindow = SendWindow(CONNECTION_WINDOW)
    private val streamWindows = MutableStateFlow<Map<Uuid, SendWindow>>(emptyMap())
    private val connectionCredit = CreditBatcher(CONNECTION_WINDOW / 4) { credits.trySend(CONNECTION_ID to it) }
    private val credits = Channel<Pair<Uuid, Long>>(Channel.UNLIMITED)

    /** Starts accounting for the data this side sends on stream [requestId]. */
    fun openStream(requestId: Uuid) {
        if (!enabled) return
        streamWindows.update { it + (requestId to SendWindow(STREAM_WINDOW)) }
    }

    /**
     * Ends stream [requestId] on the sending side. Whoever still waits in [awaitSend] for it gets
     * [cause], so the send loop stops instead of waiting for credit that will never come.
     */
    fun closeStream(requestId: Uuid, cause: Throwable = StreamCancelledException()) {
        streamWindows.getAndUpdate { it - requestId }[requestId]?.close(cause)
    }

    /** Ends everything, e.g. when the tunnel is gone. */
    fun closeAll(cause: Throwable = StreamCancelledException("The tunnel closed")) {
        streamWindows.getAndUpdate { emptyMap() }.values.forEach { it.close(cause) }
        connectionWindow.close(cause)
        credits.close()
    }

    /**
     * Waits until [bytes] of payload may be sent on stream [requestId]. Throws [StreamCancelledException]
     * once the stream was closed via [closeStream] (or was never opened).
     */
    suspend fun awaitSend(requestId: Uuid, bytes: Int) {
        if (!enabled) return
        val window = streamWindows.value[requestId] ?: throw StreamCancelledException()
        window.acquire(bytes.toLong())
        connectionWindow.acquire(bytes.toLong())
    }

    /** The peer returned credit; [requestId] is [CONNECTION_ID] for connection credit. */
    fun onCredit(requestId: Uuid, bytes: Long) {
        if (requestId == CONNECTION_ID) connectionWindow.grant(bytes)
        else streamWindows.value[requestId]?.grant(bytes)
    }

    /**
     * The reader loop took [bytes] of flow-controlled payload off the socket, whether or not a stream
     * still wants them. Never suspends.
     */
    fun onReceived(bytes: Int) {
        if (enabled) connectionCredit.add(bytes.toLong())
    }

    /** What a stream's consumer reports the bytes it handled to; see [StreamCredit.onConsumed]. */
    fun streamCredit(requestId: Uuid): StreamCredit =
        StreamCredit(if (enabled) CreditBatcher(STREAM_WINDOW / 4) { credits.trySend(requestId to it) } else null)

    /** Sends the credit this side returns through [send], until [closeAll]. Run it for the connection's lifetime. */
    suspend fun sendCredits(send: suspend (requestId: Uuid, bytes: Long) -> Unit) {
        for ((requestId, bytes) in credits) send(requestId, bytes)
    }

    class StreamCredit internal constructor(private val batcher: CreditBatcher?) {
        /** The consumer is done with [bytes] of this stream's payload. Never suspends. */
        fun onConsumed(bytes: Int) {
            batcher?.add(bytes.toLong())
        }
    }

    /** Collects returned bytes and emits them once there are at least [threshold], so not every chunk costs a message. */
    internal class CreditBatcher(private val threshold: Long, private val emit: (Long) -> Unit) {
        private val pending = AtomicLong(0L)

        fun add(bytes: Long) {
            if (pending.addAndFetch(bytes) < threshold) return
            val taken = pending.exchange(0L)
            if (taken > 0) emit(taken)
        }
    }

    private class SendWindow(initial: Long) {
        private data class State(val credit: Long, val closed: Throwable?)

        private val state = MutableStateFlow(State(initial, null))

        suspend fun acquire(bytes: Long) {
            while (true) {
                val current = state.first { it.closed != null || it.credit > 0 }
                current.closed?.let { throw it }
                if (state.compareAndSet(current, current.copy(credit = current.credit - bytes))) return
            }
        }

        fun grant(bytes: Long) = state.update { it.copy(credit = it.credit + bytes) }

        fun close(cause: Throwable) = state.update { if (it.closed == null) it.copy(closed = cause) else it }
    }

    companion object {
        /** Request header of the tunnel WebSocket the CLI announces flow control support with. */
        const val HEADER = "X-Werkbank-Tunnel-Flow-Control"
        const val VERSION = "1"

        /** Credit messages with this id refer to the whole connection rather than a stream. */
        val CONNECTION_ID: Uuid = Uuid.NIL

        /** Unacknowledged bytes on the whole connection; what a ping may have to wait behind. */
        const val CONNECTION_WINDOW = 1024L * 1024

        /** Unconsumed bytes per stream; one stream's throughput is capped at about this per round trip. */
        const val STREAM_WINDOW = 512L * 1024
    }
}

/** The stream ended (or the peer cancelled it) while data for it was still waiting to be sent. */
class StreamCancelledException(message: String = "The stream was cancelled") : Exception(message)
