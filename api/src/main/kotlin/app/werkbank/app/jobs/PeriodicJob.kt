package app.werkbank.app.jobs

import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import plugins.recordException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Runs [execute] while active, sleeping [interval] in between.
 *
 * A failing tick is logged, recorded on its span and skipped instead of ending the loop. The delay
 * runs after [execute] returns, so a slow tick pushes the next one back rather than overlapping.
 */
abstract class PeriodicJob(
    override val name: String,
    private val interval: Duration,
    private val initialDelay: Duration = Duration.ZERO,
) : BackgroundJob {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val tracing = JobTracing(name)

    final override suspend fun run() {
        if (initialDelay > Duration.ZERO) delay(initialDelay)
        while (currentCoroutineContext().isActive) {
            val span = tracing.start("tick", SpanKind.INTERNAL)
            try {
                execute(span)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                span.recordException(e)
                logger.warn("Periodic job '$name' failed, retrying in $interval", e)
            } finally {
                span.end()
            }
            delay(interval)
        }
    }

    /** One pass of the job. [span] covers the tick; attach events and attributes to it. */
    protected abstract suspend fun execute(span: Span)
}
