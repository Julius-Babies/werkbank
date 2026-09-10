package app.werkbank.app.queue.request

import app.werkbank.app.jobs.QueueProcessorJob
import io.opentelemetry.kotlin.tracing.Span
import app.werkbank.app.tunnel.RequestKind
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import kotlin.time.Instant

/** Writes the captures from [RequestPersistenceQueue] into the database. */
class RequestPersistenceProcessorJob(queue: RequestPersistenceQueue) :
    QueueProcessorJob<PersistJob>("request-persistence", queue, workers = WORKER_COUNT), KoinComponent {

    private val db by inject<DatabaseManager>()
    private val logger = LoggerFactory.getLogger(RequestPersistenceProcessorJob::class.java)

    override suspend fun process(item: PersistJob, span: Span) {
        span.setStringAttribute("request.id", item.record.requestId.toString())
        try {
            persist(item)
        } finally {
            item.deleteBodies()
        }
    }

    /**
     * Bodies are streamed from their temp files into the blob columns, never materialised on the
     * heap. The service may be unresolved (the CLI picks it), so requests persist even without one.
     */
    private suspend fun persist(job: PersistJob) {
        val record = job.record

        // Bodies arrive still compressed. Decode them and drop the now-wrong Content-Encoding header.
        // `decode = false` is the fallback for a mislabelled body (header claims gzip, bytes are not);
        // the transaction rolls back, so an unreadable body never costs us the request record.
        suspend fun writeRecord(decode: Boolean) {
            // Closed in the finally so the fds are released even if a decoder throws before the insert.
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
        /** Enough to keep up while staying well under the HikariCP pool size. */
        private const val WORKER_COUNT = 4
    }
}
