package app.werkbank.shared.tunnel

import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Hand-off from a tunnel's reader loop to the coroutine that serves a single stream.
 *
 * The reader loop demultiplexes every stream of the tunnel, so it must never suspend on one of them:
 * while it waits, no frame is processed, pongs and all other requests included. [offer] therefore
 * never suspends. A stream whose consumer falls more than [maxBufferedBytes] behind overflows instead,
 * and the caller aborts that one stream rather than stalling the whole tunnel.
 */
@OptIn(ExperimentalAtomicApi::class)
class StreamQueue<T : Any>(
    private val maxBufferedBytes: Long,
    private val sizeOf: (T) -> Int,
) {
    private val channel = Channel<T>(Channel.UNLIMITED)
    private val bufferedBytes = AtomicLong(0L)

    /**
     * Queues [item] without suspending. Returns false only if it would push the stream past
     * [maxBufferedBytes], in which case nothing is queued. Items for a closed queue are dropped.
     */
    fun offer(item: T): Boolean {
        val size = sizeOf(item).toLong()
        if (bufferedBytes.addAndFetch(size) > maxBufferedBytes && size > 0) {
            bufferedBytes.addAndFetch(-size)
            return false
        }
        if (channel.trySend(item).isFailure) bufferedBytes.addAndFetch(-size)
        return true
    }

    /** Consumes items in order until the queue is closed; rethrows the cause it was closed with, if any. */
    suspend fun forEach(block: suspend (T) -> Unit) {
        for (item in channel) {
            try {
                block(item)
            } finally {
                // Released only once handled, so bytes still being written downstream count as buffered.
                bufferedBytes.addAndFetch(-sizeOf(item).toLong())
            }
        }
    }

    /** No more items follow; the queued ones are still delivered before [forEach] ends (or throws [cause]). */
    fun close(cause: Throwable? = null) {
        channel.close(cause)
    }

    /** Like [close], but drops everything still queued, so [forEach] ends (or throws [cause]) right away. */
    fun cancel(cause: Throwable? = null) {
        channel.close(cause)
        while (true) {
            val item = channel.tryReceive().getOrNull() ?: break
            bufferedBytes.addAndFetch(-sizeOf(item).toLong())
        }
    }
}

/** A stream's consumer fell so far behind that its queue overflowed; see [StreamQueue]. */
class StreamOverflowException(message: String) : Exception(message)
