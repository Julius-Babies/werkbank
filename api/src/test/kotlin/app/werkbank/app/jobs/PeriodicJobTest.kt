package app.werkbank.app.jobs

import io.opentelemetry.kotlin.tracing.SpanKind
import io.opentelemetry.kotlin.tracing.StatusCode
import io.opentelemetry.kotlin.tracing.Span
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class PeriodicJobTest {

    private class CountingJob(
        interval: Duration,
        initialDelay: Duration = Duration.ZERO,
        private val onTick: suspend (Int) -> Unit = {},
    ) : PeriodicJob("test", interval, initialDelay) {
        val ticks = AtomicInt(0)
        override suspend fun execute(span: Span) = onTick(ticks.incrementAndFetch())
    }

    @Test
    fun `runs repeatedly until cancelled`() = jobTest {
        val job = CountingJob(interval = 10.milliseconds)
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (job.ticks.load() < 3) delay(5) }
        running.cancel()

        val ticksAtCancel = job.ticks.load()
        delay(50)
        assertTrue(job.ticks.load() == ticksAtCancel, "job kept ticking after cancellation")
    }

    @Test
    fun `keeps running after a failing tick`() = jobTest {
        val job = CountingJob(interval = 10.milliseconds, onTick = { tick ->
            if (tick == 1) error("boom")
        })
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (job.ticks.load() < 3) delay(5) }
        running.cancel()
    }

    @Test
    fun `waits for the initial delay before the first run`() = jobTest {
        val job = CountingJob(interval = 10.milliseconds, initialDelay = 300.milliseconds)
        val running = launch { job.run() }

        delay(50)
        assertTrue(job.ticks.load() == 0, "job ran before its initial delay elapsed")

        withTimeout(5.seconds) { while (job.ticks.load() < 1) delay(5) }
        running.cancel()
    }

    @Test
    fun `emits one span per tick, named and tagged with the job`() = jobTest { exporter ->
        val job = CountingJob(interval = 10.milliseconds)
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (exporter.exportedSpans.size < 2) delay(5) }
        running.cancel()

        val span = exporter.exportedSpans.first()
        assertEquals("job test tick", span.name)
        assertEquals(SpanKind.INTERNAL, span.spanKind)
        assertEquals("test", span.attributes[JOB_NAME])
        assertEquals(StatusCode.UNSET, span.status.statusCode)
    }

    @Test
    fun `records a failing tick on its span`() = jobTest { exporter ->
        val job = CountingJob(interval = 10.milliseconds, onTick = { error("boom") })
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (exporter.exportedSpans.isEmpty()) delay(5) }
        running.cancel()

        val span = exporter.exportedSpans.first()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals("boom", span.status.description)
        assertTrue(span.events.any { it.name == "exception" }, "no exception event on the span")
    }
}
