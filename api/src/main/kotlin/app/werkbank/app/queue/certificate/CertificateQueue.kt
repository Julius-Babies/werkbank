package app.queue.certificate

import app.certificates.CertificateManager
import app.werkbank.database.Certificate
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import io.opentelemetry.kotlin.tracing.StatusData
import io.opentelemetry.kotlin.tracing.Tracer
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CertificateQueue: KoinComponent {

    private val certificateManager by inject<CertificateManager>()
    private val tracer by inject<Tracer>()
    private val db by inject<DatabaseManager>()

    suspend fun start() {
        for (request in queue) {
            val span = tracer.startSpan("certificate-request")
            val requestId = Uuid.random()
            val certificateFile = File(System.getProperty("java.io.tmpdir"), "certificate-$requestId.crt")
            val keyFile = File(System.getProperty("java.io.tmpdir"), "key-$requestId.key")
            try {
                certificateManager.requestCertificate(
                    domains = request.domains,
                    targetCertFile = certificateFile,
                    targetKeyFile = keyFile,
                    span = span,
                )

                span.addEvent("certificate-downloaded")

                db.query {
                    Certificate.new {
                        this.user = request.targetUser
                        this.privateKey = ExposedBlob(keyFile.readBytes())
                        this.certificate = ExposedBlob(certificateFile.readBytes())
                    }
                }

                span.addEvent("certificate-stored")
            } catch (e: Exception) {
                span.addEvent("exception", attributes = { setStringAttribute("stacktrace", e.stackTraceToString()) })
                span.setStatus(StatusData.Error(e.message ?: "Unknown error"))
            } finally {
                span.end()
                certificateFile.delete()
                keyFile.delete()
            }
        }
    }

    suspend fun submit(request: Request) {
        queue.send(request)
    }

    private val queue = Channel<Request>()

    data class Request(
        val domains: List<String>,
        val createdAt: Instant,
        val targetUser: User,
    )
}
