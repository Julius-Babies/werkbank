package app.werkbank.app.queue.certificate

import app.certificates.CertificateManager
import app.werkbank.app.jobs.QueueProcessorJob
import app.werkbank.database.Certificate
import app.werkbank.database.DatabaseManager
import io.opentelemetry.kotlin.tracing.StatusData
import io.opentelemetry.kotlin.tracing.Tracer
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.uuid.Uuid

/** Issues the certificates queued in [CertificateQueue] and stores them on the requesting user. */
class CertificateProcessorJob(queue: CertificateQueue) :
    QueueProcessorJob<CertificateQueue.Request>("certificate", queue), KoinComponent {

    private val certificateManager by inject<CertificateManager>()
    private val tracer by inject<Tracer>()
    private val db by inject<DatabaseManager>()

    override suspend fun process(item: CertificateQueue.Request) {
        val span = tracer.startSpan("certificate-request")
        val requestId = Uuid.random()
        val certificateFile = File(System.getProperty("java.io.tmpdir"), "certificate-$requestId.crt")
        val keyFile = File(System.getProperty("java.io.tmpdir"), "key-$requestId.key")
        try {
            certificateManager.requestCertificate(
                domains = item.domains,
                targetCertFile = certificateFile,
                targetKeyFile = keyFile,
                span = span,
            )

            span.addEvent("certificate-downloaded")

            db.query {
                Certificate.new {
                    this.user = item.targetUser
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
