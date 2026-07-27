package app.werkbank.shared.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One diagnostic step the tunnel host (CLI) passed through while handling a request. Collected as the
 * request is processed and, on an unexpected failure, sent back to the server so the proxy error page
 * and the request history can show where processing got to before it broke.
 */
@Serializable
data class TunnelCheckpoint(
    @SerialName("label") val label: String,
    // Milliseconds since the CLI first received this request, so the steps read as a timeline.
    @SerialName("elapsed_ms") val elapsedMs: Long,
)
