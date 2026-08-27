package app.werkbank.util

import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.tracing.export.InMemorySpanExporter
import io.opentelemetry.kotlin.tracing.export.inMemorySpanExporter
import io.opentelemetry.kotlin.tracing.export.simpleSpanProcessor
import io.opentelemetry.kotlin.tracing.sampling.alwaysOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AnchoredWallClockTest {

    /**
     * The SDK's default JVM clock has millisecond resolution, which reports every span shorter than a
     * millisecond with a duration of 0 — Jaeger then logs "negative duration detected" and sanitizes
     * the end timestamp.
     */
    @Test
    fun `gives a span that starts and ends immediately a positive duration`() = runBlocking {
        lateinit var exporter: InMemorySpanExporter
        val openTelemetry = createOpenTelemetry(clock = AnchoredWallClock()) {
            tracerProvider {
                sampler { alwaysOn() }
                export { simpleSpanProcessor(inMemorySpanExporter().also { exporter = it }) }
            }
        }
        val tracer = openTelemetry.tracerProvider.getTracer("werkbank-test")

        repeat(SPANS) { tracer.startSpan("fast-$it").end() }

        val spans = withTimeout(5.seconds) {
            while (exporter.exportedSpans.size < SPANS) delay(10)
            exporter.exportedSpans
        }
        spans.forEach { span ->
            val duration = span.endTimestamp!! - span.startTimestamp
            assertTrue(duration > 0, "${span.name} was reported with a duration of $duration ns")
        }
    }

    @Test
    fun `stays close to the wall clock`() {
        val clock = AnchoredWallClock()
        val deviationMs = clock.now() / 1_000_000 - System.currentTimeMillis()
        assertTrue(deviationMs in -1_000..1_000, "clock deviates from the wall clock by $deviationMs ms")
    }

    private companion object {
        /** Enough back-to-back spans that some of them land in the same millisecond. */
        const val SPANS = 20
    }
}
