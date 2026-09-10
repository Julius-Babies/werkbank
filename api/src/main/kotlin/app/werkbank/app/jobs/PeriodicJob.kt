package app.werkbank.app.jobs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Runs [execute] while active, sleeping [interval] in between.
 *
 * A failing tick is logged and skipped instead of ending the loop. The delay runs after [execute]
 * returns, so a slow tick pushes the next one back rather than overlapping.
 */
abstract class PeriodicJob(
    override val name: String,
    private val interval: Duration,
    private val initialDelay: Duration = Duration.ZERO,
) : BackgroundJob {

    private val logger = LoggerFactory.getLogger(javaClass)

    final override suspend fun run() {
        if (initialDelay > Duration.ZERO) delay(initialDelay)
        while (currentCoroutineContext().isActive) {
            try {
                execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Periodic job '$name' failed, retrying in $interval", e)
            }
            delay(interval)
        }
    }

    /** One pass of the job. */
    protected abstract suspend fun execute()
}
