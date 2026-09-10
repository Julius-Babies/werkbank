package app.werkbank.app.jobs

import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.tracing.Tracer
import io.opentelemetry.kotlin.tracing.export.InMemorySpanExporter
import io.opentelemetry.kotlin.tracing.export.inMemorySpanExporter
import io.opentelemetry.kotlin.tracing.export.simpleSpanProcessor
import io.opentelemetry.kotlin.tracing.sampling.alwaysOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Runs a job test against an in-memory exporter. [JobTracing] resolves its tracer through Koin like
 * the running app does, so the spans asserted on here are the ones production emits.
 */
fun jobTest(block: suspend CoroutineScope.(InMemorySpanExporter) -> Unit) {
    lateinit var exporter: InMemorySpanExporter
    val openTelemetry = createOpenTelemetry {
        tracerProvider {
            sampler { alwaysOn() }
            export { simpleSpanProcessor(inMemorySpanExporter().also { exporter = it }) }
        }
    }
    startKoin {
        modules(module {
            single<OpenTelemetry> { openTelemetry }
            single<Tracer> { openTelemetry.tracerProvider.getTracer("werkbank-test") }
        })
    }
    try {
        runBlocking { block(exporter) }
    } finally {
        stopKoin()
    }
}
