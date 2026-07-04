package plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.ktor.util.logging.KtorSimpleLogger
import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import io.opentelemetry.kotlin.tracing.StatusData
import io.opentelemetry.kotlin.tracing.Tracer
import org.koin.ktor.ext.inject

val rootSpanKey = AttributeKey<Span>("rootSpan")
val tracerKey = AttributeKey<Tracer>("tracer")

val ApplicationCall.span: Span
    get() = this.attributes[rootSpanKey]

val ApplicationCall.tracer: Tracer
    get() = this.attributes[tracerKey]

fun Application.configureOpenTelemetry() {
    val logger = KtorSimpleLogger("OpenTelemetry")
    val tracer by inject<Tracer>()

    createApplicationPlugin("OpenTelemetry") {
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val host = call.request.host()

            val span = tracer.startSpan(
                name = "$method $host$path",
                spanKind = SpanKind.CLIENT,
            )
            span.setStringAttribute("http.request.uri", call.request.uri)
            span.setStringAttribute("http.request.method", method)
            span.setStringAttribute("http.request.path", path)
            span.setStringAttribute("http.request.host", host)
            span.setLongAttribute("http.request.port", call.request.origin.remotePort.toLong())
            span.setStringAttribute("http.request.user_agent", call.request.header(HttpHeaders.UserAgent) ?: "")
            call.attributes[rootSpanKey] = span
            call.attributes[tracerKey] = tracer
        }

        on(CallFailed) { call, error ->
            try {
                call.span.addEvent("exception", attributes = { setStringAttribute("stacktrace", error.stackTraceToString()) })
                call.span.setStatus(StatusData.Error(error.message ?: "Unknown error"))
            } catch (_: IllegalStateException) {
                logger.warn("Failed to record exception on span.")
            }
        }

        on(ResponseSent) { call ->
            try {
                call.span.setLongAttribute("http.response.status_code", call.response.status()?.value?.toLong() ?: 0)
                call.span.end()
            } catch (_: IllegalStateException) {
                logger.warn("Failed to end span as it is not available. Check for errors related to span initialization.")
            }
        }
    }
        .let { install(it) }
}

