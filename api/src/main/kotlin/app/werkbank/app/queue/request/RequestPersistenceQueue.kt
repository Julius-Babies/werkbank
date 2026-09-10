package app.werkbank.app.queue.request

import app.werkbank.app.jobs.JobQueue
import app.werkbank.app.tunnel.TunnelRequestRecord
import app.werkbank.app.tunnel.WsFrameRecord
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.io.File
import kotlin.uuid.Uuid

/**
 * Finished proxy requests waiting to be written to the database by [RequestPersistenceProcessorJob],
 * off the request hot path: submitting returns immediately, so a slow database never delays proxied
 * traffic. Under a backlog captures are dropped instead of pushing backpressure onto the proxy —
 * diagnostic history is worth degrading, request latency is not.
 */
class RequestPersistenceQueue : JobQueue<PersistJob>(
    name = "request-persistence",
    onDrop = { it.deleteBodies() },
)

/** Owns its temp body files: the queue deletes them on drop, the processor once persisted. */
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
