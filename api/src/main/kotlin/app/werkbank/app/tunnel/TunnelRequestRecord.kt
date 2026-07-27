package app.werkbank.app.tunnel

import app.werkbank.shared.tunnel.TunnelCheckpoint
import kotlin.uuid.Uuid

enum class RequestKind {
    HTTP,
    WEBSOCKET,
}

data class TunnelRequestRecord(
    val requestId: Uuid,
    val kind: RequestKind,
    val method: String,
    val uri: String,
    val projectId: String,
    val projectName: String,
    val serviceName: String?,
    val requestHeaders: Map<String, List<String>>,
    val responseHeaders: Map<String, List<String>>?,
    val statusCode: Int?,
    val error: String?,
    // Diagnostic timeline reported by the tunnel host when a request failed unexpectedly. Persisted
    // alongside the error so the request history can show where processing got to before it broke.
    val checkpoints: List<TunnelCheckpoint>? = null,
    val startedAt: Long,
    val sentToTunnelAt: Long?,
    val responseStartedAt: Long?,
    val completedAt: Long?,
    val requestBodyPath: String?,
    val responseBodyPath: String?,
    // WebSocket-only frame counters, kept live on the record so the overview (popover/list) can show
    // the ↑ outgoing / ↓ incoming counts without streaming frame contents.
    val wsFramesSent: Int = 0,
    val wsFramesReceived: Int = 0,
)
