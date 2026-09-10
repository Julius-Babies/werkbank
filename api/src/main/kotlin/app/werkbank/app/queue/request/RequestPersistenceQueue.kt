package app.werkbank.app.queue.request

import app.werkbank.app.jobs.JobQueue
import app.werkbank.app.tunnel.TunnelRequestRecord
import app.werkbank.app.tunnel.WsFrameRecord
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.io.File
import kotlin.uuid.Uuid

/**
 * Finished proxy requests (headers, bodies, WebSocket frames) waiting to be written to the database
 * off the request hot path by [RequestPersistenceProcessorJob].
 *
 * The proxy handler used to persist inline in its `finally` block, which held the client connection
 * and a database pool slot while it decompressed bodies and streamed potentially large blobs into
 * Postgres — work the browser was already done waiting for. Submitting here returns immediately, so
 * proxied traffic is never slowed by persistence or by a database that is momentarily slow.
 *
 * Under a persistence backlog captures are dropped (and their temp bodies deleted) rather than
 * applying backpressure onto the proxy. Diagnostic history is worth degrading; request latency
 * is not.
 */
class RequestPersistenceQueue : JobQueue<PersistJob>(
    name = "request-persistence",
    onDrop = { it.deleteBodies() },
)

/**
 * A finished proxy request awaiting persistence. Owns its temp body files — the queue deletes them
 * on drop, the processor once persisted.
 */
data class PersistJob(
    val record: TunnelRequestRecord,
    val projectId: EntityID<Uuid>,
    val explicitServiceId: EntityID<Uuid>?,
    val requestBodyFile: File?,
    val responseBodyFile: File?,
    val frames: List<WsFrameRecord> = emptyList(),
) {
    fun deleteBodies() {
        requestBodyFile?.delete()
        responseBodyFile?.delete()
    }
}
