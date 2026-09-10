package app.werkbank.app.queue.certificate

import app.certificates.CertificateManager
import app.certificates.CertificateResult
import app.werkbank.app.jobs.QueueProcessorJob
import app.werkbank.database.Certificate
import app.werkbank.database.DatabaseManager
import io.opentelemetry.kotlin.tracing.Span
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.uuid.Uuid

/** Issues the certificates queued in [CertificateQueue] and stores them on the requesting user. */
class CertificateProcessorJob(queue: CertificateQueue) :
    QueueProcessorJob<CertificateQueue.Request>("certificate", queue), KoinComponent {

    private val certificateManager by inject<CertificateManager>()
    private val db by inject<DatabaseManager>()

    override suspend fun process(item: CertificateQueue.Request, span: Span) {
        span.setStringAttribute("certificate.domains", item.domains.joinToString())
        val requestId = Uuid.random()
        val certificateFile = File(System.getProperty("java.io.tmpdir"), "certificate-$requestId.crt")
        val keyFile = File(System.getProperty("java.io.tmpdir"), "key-$requestId.key")
        try {
            val result = certificateManager.requestCertificate(
                span = span,
                domains = item.domains,
            ) as CertificateResult.Success

            span.addEvent("certificate-downloaded")

            db.query {
                Certificate.new {
                    this.user = item.targetUser
                    this.privateKey = ExposedBlob(result.privateKey)
                    this.certificate = ExposedBlob(result.certificate)
                    this.validUntil = result.validUntil
                }
            }

            span.addEvent("certificate-stored")
        } finally {
            // Failures are recorded on the span and logged by QueueProcessorJob.
            certificateFile.delete()
            keyFile.delete()
        }
    }
}
