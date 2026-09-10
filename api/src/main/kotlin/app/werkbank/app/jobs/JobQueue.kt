package app.werkbank.app.jobs

import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Bounded in-memory work queue drained by a [QueueProcessorJob].
 *
 * [submit] never suspends: a saturated queue drops the item and calls [onDrop] rather than pushing
 * backpressure onto request handling.
 *
 * With [deduplicateBy], an item whose key is already queued or being processed is dropped. The key
 * stays claimed until processing finished, so a key never runs twice concurrently — in exchange a
 * submission arriving mid-flight is lost, so dedupe by a key whose handler re-reads current state.
 *
 * Subclass it to get a distinct type for Koin:
 * `class CertificateQueue : JobQueue<CertificateQueue.Request>("certificate")`
 */
@OptIn(ExperimentalAtomicApi::class)
open class JobQueue<T : Any>(
    val name: String,
    capacity: Int = DEFAULT_CAPACITY,
    private val deduplicateBy: ((T) -> Any)? = null,
    private val onDrop: ((T) -> Unit)? = null,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val channel = Channel<Queued<T>>(capacity)
    private val claimedKeys = mutableSetOf<Any>()
    private val dropCount = AtomicLong(0)

    /** Items dropped so far, as duplicates or because the queue was full. */
    val dropped: Long get() = dropCount.load()

    /** Offers [item] and returns immediately. `false` means it was dropped and [onDrop] has run. */
    fun submit(item: T): Boolean {
        val key = deduplicateBy?.invoke(item)
        if (key != null && !synchronized(claimedKeys) { claimedKeys.add(key) }) {
            drop(item, "duplicate of an item already in flight")
            return false
        }

        if (channel.trySend(Queued(item, TimeSource.Monotonic.markNow())).isFailure) {
            if (key != null) release(key)
            drop(item, "queue is saturated")
            return false
        }
        return true
    }

    /**
     * Takes items until the queue closes or the caller is cancelled, releasing the dedupe key after
     * [handler] returned. Safe to call from several coroutines to fan out onto workers. [handler]
     * also gets how long the item waited, which is what a saturated queue shows up as.
     */
    internal suspend fun consumeEach(handler: suspend (T, Duration) -> Unit) {
        for (queued in channel) {
            try {
                handler(queued.item, queued.enqueuedAt.elapsedNow())
            } finally {
                deduplicateBy?.invoke(queued.item)?.let(::release)
            }
        }
    }

    private class Queued<T>(val item: T, val enqueuedAt: TimeSource.Monotonic.ValueTimeMark)

    private fun release(key: Any) {
        synchronized(claimedKeys) { claimedKeys.remove(key) }
    }

    private fun drop(item: T, reason: String) {
        onDrop?.invoke(item)
        // Log the first drop and then sparsely, so a saturated queue stays visible without flooding.
        val total = dropCount.incrementAndFetch()
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
