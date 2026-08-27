package app.werkbank.plugins.proxy

import app.werkbank.app.tunnel.RequestKind
import app.werkbank.app.tunnel.TunnelRequestRecord
import app.werkbank.shared.tunnel.TunnelCheckpoint
import io.opentelemetry.kotlin.semconv.HttpAttributes
import io.opentelemetry.kotlin.semconv.UrlAttributes
import io.opentelemetry.kotlin.tracing.Span
import plugins.recordException
import plugins.recordFailure

/**
 * Writes the finished [record] of a tunnelled request onto this span: identity, timings, outcome and,
 * when the tunnel host reported one, its checkpoint timeline. The record already carries every
 * diagnostic the request history and the proxy error page show, so a failed request is as detailed in
 * the trace as it is in the UI.
 */
fun Span.recordTunnelRequest(record: TunnelRequestRecord) {
    setStringAttribute(TUNNEL_REQUEST_ID, record.requestId.toString())
    setStringAttribute(TUNNEL_REQUEST_KIND, record.kind.name.lowercase())
    setStringAttribute(HttpAttributes.HTTP_REQUEST_METHOD, record.method)
    setStringAttribute(UrlAttributes.URL_PATH, record.uri.substringBefore('?'))
    record.uri.substringAfter('?', "").takeIf { it.isNotEmpty() }
        ?.let { setStringAttribute(UrlAttributes.URL_QUERY, it) }
    setStringAttribute(PROJECT_ID, record.projectId)
    setStringAttribute(PROJECT_NAME, record.projectName)
    record.serviceName?.let { setStringAttribute(SERVICE_NAME, it) }
    record.statusCode?.let { setLongAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, it.toLong()) }

    // Split into the three phases that fail for different reasons: reaching the tunnel host at all,
    // the host answering, and the response finishing.
    record.sentToTunnelAt?.let { setLongAttribute(TUNNEL_SENT_MS, it - record.startedAt) }
    record.responseStartedAt?.let { setLongAttribute(TUNNEL_RESPONSE_MS, it - record.startedAt) }
    record.completedAt?.let { setLongAttribute(TUNNEL_TOTAL_MS, it - record.startedAt) }

    if (record.kind == RequestKind.WEBSOCKET) {
        setLongAttribute(WS_FRAMES_SENT, record.wsFramesSent.toLong())
        setLongAttribute(WS_FRAMES_RECEIVED, record.wsFramesReceived.toLong())
    }

    // The host's checkpoints are relative to the moment it received the request, which is the point
    // the frame left this server, so they line up with the rest of the trace on the same timeline.
    recordCheckpoints(record.checkpoints, baseTimestampMs = record.sentToTunnelAt ?: record.startedAt)

    record.error?.let { recordFailure(TUNNEL_ERROR_TYPE, it) }
}

/**
 * Records everything known about the finished request and ends the span. [error] is for a failure that
 * never made it onto the [record] itself, e.g. one thrown while the WebSocket was still being opened.
 */
fun Span.finishWith(record: TunnelRequestRecord, error: Throwable? = null) {
    recordTunnelRequest(record)
    error?.let { recordException(it) }
    end()
}

/** Adds one event per [checkpoint][TunnelCheckpoint] the tunnel host reported, on its own timeline. */
fun Span.recordCheckpoints(checkpoints: List<TunnelCheckpoint>?, baseTimestampMs: Long) {
    checkpoints?.forEach { checkpoint ->
        addEvent(
            name = "tunnel.checkpoint",
            timestamp = (baseTimestampMs + checkpoint.elapsedMs) * NANOS_PER_MS,
        ) {
            setStringAttribute("werkbank.tunnel.checkpoint.label", checkpoint.label)
            setLongAttribute("werkbank.tunnel.checkpoint.elapsed_ms", checkpoint.elapsedMs)
        }
    }
}

/** [error.type][io.opentelemetry.kotlin.semconv.ErrorAttributes.ERROR_TYPE] of a failure the tunnel host reported. */
const val TUNNEL_ERROR_TYPE = "werkbank.tunnel.error"

const val TUNNEL_REQUEST_ID = "werkbank.request.id"
const val TUNNEL_REQUEST_KIND = "werkbank.request.kind"
const val PROJECT_ID = "werkbank.project.id"
const val PROJECT_NAME = "werkbank.project.name"
const val SERVICE_NAME = "werkbank.service.name"
const val SUBDOMAIN = "werkbank.subdomain"
const val DESTINATION = "werkbank.destination"
const val USER_NAME = "werkbank.user.name"
const val TUNNEL_CONNECTED = "werkbank.tunnel.connected"
const val TUNNEL_ALIVE = "werkbank.tunnel.alive"
const val TUNNEL_PING_MS = "werkbank.tunnel.ping_ms"
const val TUNNEL_SENT_MS = "werkbank.tunnel.sent_ms"
const val TUNNEL_RESPONSE_MS = "werkbank.tunnel.response_ms"
const val TUNNEL_TOTAL_MS = "werkbank.tunnel.total_ms"
const val WS_FRAMES_SENT = "werkbank.ws.frames_sent"
const val WS_FRAMES_RECEIVED = "werkbank.ws.frames_received"
const val PROXY_ERROR_PAGE = "werkbank.proxy.error_page"

private const val NANOS_PER_MS = 1_000_000L
