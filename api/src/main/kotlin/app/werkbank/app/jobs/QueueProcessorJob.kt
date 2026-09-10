package app.werkbank.app.jobs

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drains a [JobQueue] with [workers] parallel workers until the surrounding scope is cancelled.
 *
 * A failing item is logged and skipped. Raise [workers] only for work that is safe to run
 * concurrently, and stay under the database pool size when it hits Postgres.
 */
abstract class QueueProcessorJob<T : Any>(
    override val name: String,
    private val queue: JobQueue<T>,
    private val workers: Int = 1,
) : BackgroundJob {

    private val logger = LoggerFactory.getLogger(javaClass)

    final override suspend fun run() = coroutineScope {
        repeat(workers) {
            launch {
                queue.consumeEach { item ->
                    try {
                        process(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Job '$name' failed to process an item", e)
                    }
                }
            }
        }
    }

    /** Handles a single item. Exceptions are logged and the item skipped. */
    protected abstract suspend fun process(item: T)
}
