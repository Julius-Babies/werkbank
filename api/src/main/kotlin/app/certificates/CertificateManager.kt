package app.certificates

import io.opentelemetry.kotlin.tracing.Span
import java.io.File

interface CertificateManager {
    suspend fun init()
    suspend fun requestCertificate(
        span: Span,
        domains: List<String>,
        targetCertFile: File,
        targetKeyFile: File,
    )
}