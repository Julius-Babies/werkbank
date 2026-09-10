package app.werkbank.app.jobs

import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded in-memory work queue drained by a [QueueProcessorJob].
 *
 * [submit] never suspends and never fails the caller: if the queue is saturated the item is dropped,
 * counted and passed to [onDrop], so a stalled processor can never apply backpressure onto request
 * handling. Callers that must know use the returned flag.
 *
 * With [deduplicateBy] set, an item whose key is already queued or currently being processed is
 * dropped. The key stays claimed until processing finished, so the same key is never processed twice
 * concurrently and a burst collapses into a single run. In exchange, a submission arriving while its
 * key is in flight is lost — dedupe by a key whose handler re-reads current state, not by one whose
 * payload must not be missed.
 *
 * Subclass it to get a distinct type for Koin instead of registering the generic class:
 * ```
 * class CertificateQueue : JobQueue<CertificateQueue.Request>("certificate")
 * ```
 */
open class JobQueue<T : Any>(
    val name: String,
    capacity: Int = DEFAULT_CAPACITY,
    private val deduplicateBy: ((T) -> Any)? = null,
    private val onDrop: ((T) -> Unit)? = null,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val channel = Channel<T>(capacity)
    private val claimedKeys = mutableSetOf<Any>()
    private val dropCount = AtomicLong(0)

    /** Number of items dropped so far, either as duplicates or because the queue was full. */
    val dropped: Long get() = dropCount.get()

    /**
     * Offers [item] to the processor and returns immediately. `false` means the item was dropped —
     * a duplicate of one already in flight, or the queue was full — and [onDrop] has run.
     */
    fun submit(item: T): Boolean {
        val key = deduplicateBy?.invoke(item)
        if (key != null && !synchronized(claimedKeys) { claimedKeys.add(key) }) {
            drop(item, "duplicate of an item already in flight")
            return false
        }

        if (channel.trySend(item).isFailure) {
            if (key != null) release(key)
            drop(item, "queue is saturated")
            return false
        }
        return true
    }

    /**
     * Takes items until the queue is closed or the caller is cancelled, releasing the dedupe key
     * after [handler] returned. Safe to call from several coroutines to fan out onto workers.
     */
    internal suspend fun consumeEach(handler: suspend (T) -> Unit) {
        for (item in channel) {
            try {
                handler(item)
            } finally {
                deduplicateBy?.invoke(item)?.let(::release)
            }
        }
    }

    private fun release(key: Any) {
        synchronized(claimedKeys) { claimedKeys.remove(key) }
    }

    private fun drop(item: T, reason: String) {
        onDrop?.invoke(item)
        val total = dropCount.incrementAndGet()
        // Log the first drop and then sparsely, so a saturated queue stays visible without flooding
        // the log once per dropped item.
        if (total == 1L || total % DROP_LOG_INTERVAL == 0L) {
            logger.warn("Queue '{}' dropped an item ({}); {} dropped so far", name, reason, total)
        }
    }

    companion object {
        /** Bounded so a backlog can never grow without limit. */
        const val DEFAULT_CAPACITY = 1024
        private const val DROP_LOG_INTERVAL = 100L
    }
}
