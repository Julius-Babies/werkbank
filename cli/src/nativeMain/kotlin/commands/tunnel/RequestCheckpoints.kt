package commands.tunnel

import app.werkbank.shared.tunnel.TunnelCheckpoint
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Collects the steps a single proxied request passes through on the tunnel host, each stamped with the
 * time elapsed since the request was received. On an unexpected failure the collected checkpoints are
 * sent back to the server (as [TunnelCheckpoint]s) so the proxy error page and the request history can
 * show where processing got to before it broke.
 */
class RequestCheckpoints {
    private val start: Instant = Clock.System.now()
    private val checkpoints = mutableListOf<TunnelCheckpoint>()

    fun mark(label: String) {
        checkpoints.add(
            TunnelCheckpoint(
                label = label,
                elapsedMs = (Clock.System.now() - start).inWholeMilliseconds,
            )
        )
    }

    fun toList(): List<TunnelCheckpoint> = checkpoints.toList()
}
