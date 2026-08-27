package plugins

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.propagation.TextMapGetter
import io.opentelemetry.kotlin.semconv.ClientAttributes
import io.opentelemetry.kotlin.semconv.ErrorAttributes
import io.opentelemetry.kotlin.semconv.HttpAttributes
import io.opentelemetry.kotlin.semconv.NetworkAttributes
import io.opentelemetry.kotlin.semconv.ServerAttributes
import io.opentelemetry.kotlin.semconv.UrlAttributes
import io.opentelemetry.kotlin.semconv.UserAgentAttributes
import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import io.opentelemetry.kotlin.tracing.StatusData
import io.opentelemetry.kotlin.tracing.Tracer
import org.koin.ktor.ext.inject

private val callSpanKey = AttributeKey<Span>("werkbank.otel.call-span")
private val callContextKey = AttributeKey<Context>("werkbank.otel.call-context")
private val telemetryKey = AttributeKey<CallTelemetry>("werkbank.otel.telemetry")
private val callFailedKey = AttributeKey<Unit>("werkbank.otel.call-failed")

/** The application-wide pieces the per-call tracing helpers need. */
private class CallTelemetry(val tracer: Tracer, val noopSpan: Span)

/**
 * The span covering this call. A no-op span once the call's span has ended, so a late writer (e.g. a
 * proxied WebSocket that outlives its 101 response) never has to null-check.
 */
val ApplicationCall.span: Span
    get() = attributes.getOrNull(callSpanKey) ?: application.attributes[telemetryKey].noopSpan

val ApplicationCall.tracer: Tracer
    get() = application.attributes[telemetryKey].tracer

/**
 * Starts a span nested under this call's span. Sub-steps of a call belong in child spans so a failed
 * request shows *which* step broke and how long the steps before it took.
 */
fun ApplicationCall.startChildSpan(name: String, spanKind: SpanKind = SpanKind.INTERNAL): Span =
    tracer.startSpan(name, parentContext = attributes.getOrNull(callContextKey), spanKind = spanKind)

/**
 * Runs [block] in a child span of this call's span, recording any exception on it before rethrowing.
 * The span is passed in so [block] can attach its own attributes.
 */
suspend fun <T> ApplicationCall.traceStep(name: String, block: suspend (Span) -> T): T {
    val span = startChildSpan(name)
    try {
        return block(span)
    } catch (error: Throwable) {
        span.recordException(error)
        throw error
    } finally {
        span.end()
    }
}

/** Records [error] as an exception event and marks the span as failed. */
fun Span.recordException(error: Throwable) {
    val type = error::class.qualifiedName ?: "unknown"
    addEvent("exception") {
        setStringAttribute("exception.type", type)
        error.message?.let { setStringAttribute("exception.message", it) }
        setStringAttribute("exception.stacktrace", error.stackTraceToString())
    }
    setStringAttribute(ErrorAttributes.ERROR_TYPE, type)
    setStatus(StatusData.Error(error.message ?: error::class.simpleName ?: "Unknown error"))
}

/**
 * Marks the span as failed with an error that is not an exception here — e.g. a failure the tunnel
 * host reported over the wire, or one that is answered with a rendered error page.
 */
fun Span.recordFailure(errorType: String, message: String) {
    setStringAttribute(ErrorAttributes.ERROR_TYPE, errorType)
    // Semconv's error.message is deprecated in favour of a domain-specific attribute; the span status
    // carries the same text, this is for backends that only index attributes.
    setStringAttribute(ERROR_MESSAGE, message)
    setStatus(StatusData.Error(message))
}

/** Human-readable description of the failure, alongside [ErrorAttributes.ERROR_TYPE]. */
private const val ERROR_MESSAGE = "werkbank.error.message"

/**
 * Marks this call's span as failed. Use this rather than `call.span.recordFailure` so the reason
 * survives: [endCallSpan] would otherwise replace it with the bare response status.
 */
fun ApplicationCall.recordFailure(errorType: String, message: String) {
    attributes.put(callFailedKey, Unit)
    span.recordFailure(errorType, message)
}

/** Records [error] on this call's span, keeping its reason as [recordFailure] does. */
fun ApplicationCall.recordException(error: Throwable) {
    attributes.put(callFailedKey, Unit)
    span.recordException(error)
}

fun Application.configureOpenTelemetry() {
    val openTelemetry by inject<OpenTelemetry>()
    val tracer by inject<Tracer>()

    val plugin = createApplicationPlugin("OpenTelemetry") {
        application.attributes.put(telemetryKey, CallTelemetry(tracer, openTelemetry.span.invalid))

        // CallSetup, not RoutingRoot.RoutingCallStarted: proxied tunnel requests are answered by
        // SubdomainHandler and never reach routing, so a routing-scoped span left every request
        // through the tunnel — the ones we care about most — untraced, and made ResponseSent below
        // log "failed to end span" for each of them.
        on(CallSetup) { call ->
            if (!tracer.enabled()) return@on
            val request = call.request
            val method = request.httpMethod.value
            val host = request.host()
            val path = request.path()
            val origin = request.origin

            val span = tracer.startSpan(
                name = "$method $host$path",
                parentContext = openTelemetry.propagator.extract(
                    openTelemetry.context.root(),
                    request.headers,
                    HeadersTextMapGetter,
                ),
                spanKind = SpanKind.SERVER,
            ) {
                setStringAttribute(HttpAttributes.HTTP_REQUEST_METHOD, method)
                setStringAttribute(UrlAttributes.URL_PATH, path)
                setStringAttribute(UrlAttributes.URL_SCHEME, origin.scheme)
                setStringAttribute(ServerAttributes.SERVER_ADDRESS, host)
                setLongAttribute(ServerAttributes.SERVER_PORT, origin.serverPort.toLong())
                setStringAttribute(ClientAttributes.CLIENT_ADDRESS, origin.remoteAddress)
                setLongAttribute(ClientAttributes.CLIENT_PORT, origin.remotePort.toLong())
                setStringAttribute(NetworkAttributes.NETWORK_PROTOCOL_VERSION, origin.version.removePrefix("HTTP/"))
                request.queryString().takeIf { it.isNotEmpty() }
                    ?.let { setStringAttribute(UrlAttributes.URL_QUERY, it) }
                request.header(HttpHeaders.UserAgent)
                    ?.let { setStringAttribute(UserAgentAttributes.USER_AGENT_ORIGINAL, it) }
            }

            call.attributes[callSpanKey] = span
            call.attributes[callContextKey] = openTelemetry.context.root().storeSpan(span)
        }

        // A routed call is named after its path template, so /api/projects/<id> does not become one
        // operation per project id in the trace backend.
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            val template = call.route.pathTemplate
            call.span.setStringAttribute(HttpAttributes.HTTP_ROUTE, template)
            call.span.setName("${call.request.httpMethod.value} $template")
        }

        on(CallFailed) { call, error -> call.endCallSpan(error) }

        on(ResponseSent) { call -> call.endCallSpan(null) }
    }

    install(plugin)
}

/**
 * Ends the call's span, at most once: the span is removed as it is ended, so whichever of
 * [CallFailed] and [ResponseSent] fires second is a no-op.
 */
private fun ApplicationCall.endCallSpan(error: Throwable?) {
    val span = attributes.getOrNull(callSpanKey) ?: return
    attributes.remove(callSpanKey)
    attributes.remove(callContextKey)

    error?.let { span.recordException(it) }

    val status = response.status()?.value
    if (status != null) {
        span.setLongAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, status.toLong())
        // 5xx is the server's own fault, so it fails the span. A 4xx is a valid outcome of a
        // well-behaved server and stays unset. A failure recorded earlier keeps its own reason: it
        // knows more than the status code does.
        val failedEarlier = error != null || attributes.contains(callFailedKey)
        if (!failedEarlier && status >= 500) span.recordFailure(status.toString(), "HTTP $status")
    }
    span.end()
}

/**
 * The route's path template, e.g. `/api/projects/{id}`. [RoutingNode.toString] renders non-path
 * selectors as their own segments (`/(method:GET)`, `/(authenticate "user-jwt")`); those are dropped.
 */
private val RoutingNode.pathTemplate: String
    get() = toString().replace(NON_PATH_SELECTOR, "").ifEmpty { "/" }

private val NON_PATH_SELECTOR = Regex("""/\([^)]*\)""")

/** Reads the propagation headers (`traceparent`, `baggage`, …) off an incoming request. */
private object HeadersTextMapGetter : TextMapGetter<Headers> {
    override fun keys(carrier: Headers): Collection<String> = carrier.names()
    override fun get(carrier: Headers?, key: String): String? = carrier?.get(key)
    override fun getAll(carrier: Headers?, key: String): List<String> = carrier?.getAll(key) ?: emptyList()
}
