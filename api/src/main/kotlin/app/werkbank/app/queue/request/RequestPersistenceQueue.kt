package app.werkbank.app.queue.request

import app.werkbank.app.tunnel.RequestKind
import app.werkbank.app.tunnel.TunnelRequestRecord
import app.werkbank.app.tunnel.WsFrameRecord
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.database.Service
import app.werkbank.database.Services
import app.werkbank.database.TunnelRequest
import app.werkbank.database.TunnelRequestFrames
import app.werkbank.database.TunnelRequestResult
import app.werkbank.plugins.proxy.DecodedBody
import app.werkbank.plugins.proxy.contentEncoding
import app.werkbank.plugins.proxy.decodeHttpBody
import app.werkbank.plugins.proxy.withoutBodyEncodingHeaders
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persists finished proxy requests (headers, bodies, WebSocket frames) into the database off the
 * request hot path.
 *
 * The proxy handler used to persist inline in its `finally` block, which held the client connection
 * and a database pool slot while it decompressed bodies and streamed potentially large blobs into
 * Postgres — work the browser was already done waiting for. [submit] instead hands the finished
 * capture to this queue and returns immediately, so proxied traffic is never slowed by persistence
 * or by a database that is momentarily slow.
 *
 * The queue is bounded: under a persistence backlog captures are dropped (and their temp bodies
 * deleted) rather than applying backpressure onto the proxy. Diagnostic history is worth degrading;
 * request latency is not.
 */
class RequestPersistenceQueue : KoinComponent {

    private val db by inject<DatabaseManager>()
    private val logger = LoggerFactory.getLogger(RequestPersistenceQueue::class.java)

    private val queue = Channel<PersistJob>(capacity = QUEUE_CAPACITY)
    private val dropped = AtomicLong(0)

    /**
     * Hands a finished request off for persistence and returns immediately without blocking the
     * request coroutine. Ownership of the temp body files transfers to the queue: it deletes them
     * once persisted, or right away if the capture is dropped. If the queue is saturated (or already
     * shut down) the capture is dropped and counted so a slow database can never stall the proxy.
     */
    fun submit(job: PersistJob) {
        val result = queue.trySend(job)
        if (result.isFailure) {
            job.deleteBodies()
            val total = dropped.incrementAndGet()
            // Log on the first drop and then sparsely, so a saturated queue is visible without
            // flooding the log for every dropped request.
            if (total == 1L || total % 100L == 0L) {
                logger.warn("Request persistence queue saturated; dropped {} captures so far", total)
            }
        }
    }

    /** Drains the queue with a small pool of workers until the channel is closed. */
    suspend fun start() = coroutineScope {
        repeat(WORKER_COUNT) {
            launch {
                for (job in queue) {
                    try {
                        persist(job)
                    } catch (e: Exception) {
                        logger.warn("Failed to persist request ${job.record.requestId}", e)
                    } finally {
                        job.deleteBodies()
                    }
                }
            }
        }
    }

    /**
     * Persists a single captured request into [TunnelRequest]. Bodies are streamed from their temp
     * files straight into the blob columns via [ExposedBlob], so they are never fully materialised on
     * the heap. For WebSocket connections the captured frames are written into [TunnelRequestFrames].
     * The service may be unresolved (the CLI picks it), so requests are persisted even without one.
     */
    private suspend fun persist(job: PersistJob) {
        val record = job.record

        // Bodies are stored exactly as they came over the tunnel, i.e. still compressed. Decode them
        // so the stored copy is the plain body and drop the Content-Encoding header that no longer
        // applies. `decode = false` reproduces the raw behaviour and is used as a fallback if decoding
        // blows up on a mislabelled body (e.g. header claims gzip but the bytes are not) — the
        // transaction rolls back so we never lose the request record over an unreadable body.
        suspend fun writeRecord(decode: Boolean) {
            // The raw file streams are the underlying resource; closing them in the finally releases
            // the fds even if a decoder constructor throws on a mislabelled body before the insert.
            val rawRequestBody = job.requestBodyFile?.takeIf { it.isFile && it.length() > 0 }?.inputStream()
            val rawResponseBody = job.responseBodyFile?.takeIf { it.isFile && it.length() > 0 }?.inputStream()

            try {
                val requestBody = rawRequestBody?.let {
                    if (decode) decodeHttpBody(it, record.requestHeaders.contentEncoding()) else DecodedBody(it, false)
                }
                val responseBody = rawResponseBody?.let {
                    if (decode) decodeHttpBody(it, record.responseHeaders?.contentEncoding()) else DecodedBody(it, false)
                }

                db.query {
                    val serviceEntity = job.explicitServiceId?.let { Service.findById(it) }
                        ?: record.serviceName?.let { name ->
                            Service.find {
                                (Services.project eq job.projectId) and (Services.serviceKey.lowerCase() eq name.lowercase())
                            }.firstOrNull()
                        }

                    val statusCode = record.statusCode
                    val error = record.error
                    val outcome = when {
                        error != null -> TunnelRequestResult.Failure(error, record.checkpoints)
                        statusCode != null -> TunnelRequestResult.Success(statusCode)
                        else -> TunnelRequestResult.Failure("Request did not complete")
                    }

                    val entity = TunnelRequest.new(record.requestId) {
                        this.service = serviceEntity
                        this.project = Project[job.projectId]
                        this.kind = when (record.kind) {
                            RequestKind.HTTP -> "http"
                            RequestKind.WEBSOCKET -> "websocket"
                        }
                        this.method = record.method
                        this.uri = record.uri
                        this.requestHeaders =
                            if (requestBody?.decoded == true) record.requestHeaders.withoutBodyEncodingHeaders()
                            else record.requestHeaders
                        this.responseHeaders = record.responseHeaders?.let {
                            if (responseBody?.decoded == true) it.withoutBodyEncodingHeaders() else it
                        }
                        this.result = outcome
                        this.requestBody = requestBody?.let { ExposedBlob(it.stream) }
                        this.responseBody = responseBody?.let { ExposedBlob(it.stream) }
                        this.startedAt = Instant.fromEpochMilliseconds(record.startedAt)
                        this.responseReadyAt = record.responseStartedAt?.let { Instant.fromEpochMilliseconds(it) }
                        this.wsFramesSent = record.wsFramesSent
                        this.wsFramesReceived = record.wsFramesReceived
                    }

                    if (job.frames.isNotEmpty()) {
                        TunnelRequestFrames.batchInsert(job.frames) { frame ->
                            this[TunnelRequestFrames.request] = entity.id
                            this[TunnelRequestFrames.sequence] = frame.sequence
                            this[TunnelRequestFrames.direction] = frame.direction.name.lowercase()
                            this[TunnelRequestFrames.opcode] = frame.opcode.name.lowercase()
                            this[TunnelRequestFrames.text] = frame.text
                            this[TunnelRequestFrames.binaryBase64] = frame.binaryBase64
                            this[TunnelRequestFrames.size] = frame.size
                            this[TunnelRequestFrames.timestamp] = Instant.fromEpochMilliseconds(frame.timestamp)
                            this[TunnelRequestFrames.closeCode] = frame.closeCode
                            this[TunnelRequestFrames.closeReason] = frame.closeReason
                        }
                    }
                }
            } finally {
                rawRequestBody?.close()
                rawResponseBody?.close()
            }
        }

        try {
            writeRecord(decode = true)
        } catch (e: Exception) {
            logger.warn("Failed to persist decoded bodies for ${record.requestId}, storing raw instead: ${e.message}")
            writeRecord(decode = false)
        }
    }

    companion object {
        /** Bounded so a persistence backlog can never grow without limit; excess captures are dropped. */
        private const val QUEUE_CAPACITY = 1024

        /** A handful of workers; enough to keep up while staying well under the HikariCP pool size. */
        private const val WORKER_COUNT = 4
    }
}

/**
 * A finished proxy request awaiting persistence. Owns its temp body files — the queue deletes them
 * once done (see [RequestPersistenceQueue.submit]).
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
