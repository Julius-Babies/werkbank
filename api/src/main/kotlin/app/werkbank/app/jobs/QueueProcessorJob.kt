package app.werkbank.app.jobs

import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import plugins.recordException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drains a [JobQueue] with [workers] parallel workers until the surrounding scope is cancelled.
 *
 * A failing item is logged, recorded on its span and skipped. Raise [workers] only for work that is
 * safe to run concurrently, and stay under the database pool size when it hits Postgres.
 */
abstract class QueueProcessorJob<T : Any>(
    override val name: String,
    private val queue: JobQueue<T>,
    private val workers: Int = 1,
) : BackgroundJob {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val tracing = JobTracing(name)

    final override suspend fun run() = coroutineScope {
        repeat(workers) {
            launch {
                queue.consumeEach { item, waited ->
                    val span = tracing.start("process", SpanKind.CONSUMER)
                    // How long the item sat in the queue; the first thing that grows under a backlog.
                    span.setLongAttribute(JOB_QUEUE_WAIT_MS, waited.inWholeMilliseconds)
                    try {
                        process(item, span)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        span.recordException(e)
                        logger.warn("Job '$name' failed to process an item", e)
                    } finally {
                        span.end()
                    }
                }
            }
        }
    }

    /** Handles a single item. [span] covers this item; attach events and attributes to it. */
    protected abstract suspend fun process(item: T, span: Span)
}
