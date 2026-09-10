package app.werkbank.app.jobs

import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import io.opentelemetry.kotlin.tracing.Tracer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Attribute namespace for background jobs; there is no semconv for in-process work queues. */
const val JOB_NAME = "werkbank.job.name"
const val JOB_QUEUE_WAIT_MS = "werkbank.job.queue.wait_ms"

/**
 * Starts the span around one unit of background work.
 *
 * Each run is its own root span rather than a child of a long-lived job span: a job that ticks for
 * weeks would otherwise produce one unbounded trace that no backend can display.
 */
internal class JobTracing(private val jobName: String) : KoinComponent {

    private val tracer by inject<Tracer>()
    private val openTelemetry by inject<OpenTelemetry>()

    fun start(operation: String, spanKind: SpanKind): Span {
        if (!tracer.enabled()) return openTelemetry.span.invalid
        return tracer.startSpan("job $jobName $operation", spanKind = spanKind) {
            setStringAttribute(JOB_NAME, jobName)
        }
    }
}
