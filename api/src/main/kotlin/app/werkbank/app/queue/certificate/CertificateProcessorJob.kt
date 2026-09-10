package app.werkbank.app.queue.certificate

import app.certificates.CertificateManager
import app.certificates.CertificateResult
import app.werkbank.app.certificates.ServerKeyManager
import app.werkbank.app.jobs.QueueProcessorJob
import app.werkbank.database.Certificate
import app.werkbank.database.DatabaseManager
import io.opentelemetry.kotlin.tracing.Span
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Issues the certificates queued in [CertificateQueue] and stores them on the requesting user. */
class CertificateProcessorJob(queue: CertificateQueue) :
    QueueProcessorJob<CertificateQueue.Request>("certificate", queue), KoinComponent {

    private val certificateManager by inject<CertificateManager>()
    private val db by inject<DatabaseManager>()
    private val serverKeyManager by inject<ServerKeyManager>()

    // No try/finally: there is nothing to clean up, and QueueProcessorJob records the failure on the
    // span, logs it and ends the span.
    override suspend fun process(item: CertificateQueue.Request, span: Span) {
        span.setStringAttribute("certificate.domains", item.domains.joinToString())

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

        // The TLS layer caches per user; without this it keeps serving the replaced certificate.
        serverKeyManager.invalidateForUser(item.targetUser.username)

        span.addEvent("certificate-stored")
    }
}
